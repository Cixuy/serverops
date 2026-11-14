from __future__ import annotations

import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from queue import Empty, SimpleQueue

import psutil

from .api import AgentApi, ApiError
from .collector import SystemCollector
from .config import AgentConfig, ConfigStore
from .healing import (
    HealingExecution,
    TriggerConfigError,
    evaluate_trigger,
    execute_rule,
    trigger_detail,
)
from .logging_config import get_logger
from .models import (
    HealingRule,
    MetricSnapshot,
    MonitorConfig,
    ServiceStatus,
    alert_event_payload,
    service_status_report_payload,
)
from .monitor import ServiceMonitor
from .storage import MetricQueue
from .websocket import ConfigWebSocketClient

logger = get_logger("service")

ERROR_EVENTS = {
    "alert_error",
    "config_error",
    "error",
    "healing_error",
    "websocket_error",
}
WARNING_EVENTS = {"offline", "paused"}
DEBUG_EVENTS = {"healing_evaluated", "healing_rules_loaded", "reported", "snapshot"}
EARLY_RECOVERY_INITIAL_DELAY = 3.0
EARLY_RECOVERY_INTERVAL = 1.0


@dataclass(slots=True)
class HealingResult:
    config_id: int
    rule_id: int
    status: str
    output: str = ""
    retry_count: int = 0
    duration_ms: int = 0
    finished_at: int | None = None


@dataclass(slots=True)
class ServiceEvent:
    kind: str
    message: str = ""
    snapshot: MetricSnapshot | None = None
    queue_size: int = 0
    server_code: str | None = None
    monitor_configs: list[MonitorConfig] | None = None
    service_statuses: list[ServiceStatus] | None = None
    healing_rules: dict[int, list[HealingRule]] | None = None
    healing_result: HealingResult | None = None


class AgentService:
    def __init__(
        self,
        config: AgentConfig,
        config_store: ConfigStore,
        callback: Callable[[ServiceEvent], None],
        collector: SystemCollector | None = None,
        monitor: ServiceMonitor | None = None,
    ) -> None:
        self.config = config
        self.config_store = config_store
        self.callback = callback
        self.collector = collector or SystemCollector()
        self.monitor = monitor or ServiceMonitor()
        self.queue = MetricQueue(config_store.data_dir / "metrics.db", config.queue_limit)
        self._stop = threading.Event()
        self._paused = threading.Event()
        self._force_report = threading.Event()
        self._refresh_requested = threading.Event()
        self._thread: threading.Thread | None = None
        self._active_alerts: set[int] = set()
        self._active_alert_ids: dict[int, int] = {}
        self._last_alert_reports = config_store.load_alert_report_times()
        self._last_healing_checks: dict[int, float] = {}
        self._config_messages: SimpleQueue[tuple[str, object]] = SimpleQueue()
        self._config_changed = threading.Event()
        self._healing_rules_changed = threading.Event()
        self._config_websocket: ConfigWebSocketClient | None = None

    @property
    def paused(self) -> bool:
        return self._paused.is_set()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self.config.validate()
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="serverops-agent", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        if self._thread and self._thread is not threading.current_thread():
            self._thread.join(timeout)

    def set_paused(self, paused: bool) -> None:
        if paused:
            self._paused.set()
            self._emit("paused", "监控已暂停")
        else:
            self._paused.clear()
            self._force_report.set()
            self._emit("starting", "正在恢复监控")

    def report_now(self) -> None:
        self._force_report.set()

    def refresh_configs(self) -> None:
        self._refresh_requested.set()
        self._force_report.set()

    def _run(self) -> None:
        server_code = self.config_store.load_server_code()
        next_register = 0.0
        next_sample = 0.0
        next_heartbeat = 0.0
        next_service_report = 0.0
        next_healing_check = 0.0
        monitor_configs: list[MonitorConfig] = []
        latest_snapshot: MetricSnapshot | None = None
        latest_statuses: dict[int, ServiceStatus] = {}
        host = self.collector.host_info()
        self._emit("starting", "正在连接服务器", server_code=server_code)

        with AgentApi(self.config) as api:
            while not self._stop.is_set():
                if self._paused.is_set():
                    self._stop.wait(0.25)
                    continue

                now = time.monotonic()
                forced = self._force_report.is_set()
                if forced:
                    self._force_report.clear()

                monitor_configs = self._drain_config_messages(monitor_configs, server_code)
                if self._config_changed.is_set():
                    self._config_changed.clear()
                    self._last_healing_checks.clear()
                    next_service_report = 0.0
                    next_healing_check = 0.0
                if self._healing_rules_changed.is_set():
                    self._healing_rules_changed.clear()
                    self._last_healing_checks.clear()
                    next_healing_check = 0.0

                if now >= next_register:
                    try:
                        server_code = api.register(host)
                        self.config_store.save_server_code(server_code)
                        next_register = float("inf")
                        self._emit(
                            "connected", "Agent 注册成功", server_code=server_code
                        )
                        self._start_config_websocket(server_code)
                        try:
                            configs = api.get_monitor_configs(server_code)
                            monitor_configs = configs
                            self._emit(
                                "config_loaded",
                                f"已拉取 {len(configs)} 项服务配置",
                                server_code=server_code,
                                monitor_configs=configs,
                            )
                        except ApiError as exc:
                            self._emit("config_error", f"服务配置拉取失败：{exc}", server_code=server_code)
                    except ApiError as exc:
                        next_register = now + 30
                        self._emit("offline", str(exc), server_code=server_code)

                if server_code and self._refresh_requested.is_set():
                    self._refresh_requested.clear()
                    try:
                        configs = api.get_monitor_configs(server_code)
                        monitor_configs = configs
                        latest_statuses = {}
                        self._last_healing_checks.clear()
                        next_service_report = 0.0
                        next_healing_check = 0.0
                        self._emit(
                            "config_refreshed",
                            f"已刷新 {len(configs)} 项服务配置，正在检查自愈规则",
                            server_code=server_code,
                            monitor_configs=configs,
                        )
                    except ApiError as exc:
                        self._emit(
                            "config_error",
                            f"配置刷新失败：{exc}",
                            server_code=server_code,
                        )
                elif self._refresh_requested.is_set():
                    self._refresh_requested.clear()
                    self._emit("config_error", "尚未连接服务器，无法刷新配置")

                if now >= next_sample or forced:
                    try:
                        snapshot = self.collector.collect()
                        latest_snapshot = snapshot
                        if server_code:
                            self.queue.enqueue(snapshot.to_report_payload(server_code))
                        self._emit(
                            "snapshot",
                            snapshot=snapshot,
                            queue_size=self.queue.count(),
                            server_code=server_code,
                        )
                    except (OSError, psutil.Error) as exc:
                        self._emit("error", f"采集失败: {exc}", server_code=server_code)
                    next_sample = now + self.config.sample_interval

                if server_code and now >= next_heartbeat:
                    try:
                        api.heartbeat(server_code, int(time.time() * 1000))
                        next_heartbeat = now + self.config.heartbeat_interval
                        self._emit("connected", "心跳正常", server_code=server_code)
                    except ApiError as exc:
                        next_heartbeat = now + min(self.config.heartbeat_interval, 10)
                        self._emit("offline", str(exc), server_code=server_code)

                if server_code and monitor_configs and now >= next_service_report:
                    try:
                        checked_at = int(time.time() * 1000)
                        statuses = [
                            self.monitor.check(config, checked_at) for config in monitor_configs
                        ]
                        latest_statuses = {status.config_id: status for status in statuses}
                        self._emit(
                            "service_checked",
                            "服务状态检查完成",
                            server_code=server_code,
                            service_statuses=statuses,
                        )
                        self._report_alerts(
                            api,
                            server_code,
                            monitor_configs,
                            statuses,
                            latest_snapshot,
                        )
                        api.report_service_status(
                            service_status_report_payload(server_code, statuses)
                        )
                        interval = self._monitor_check_interval(monitor_configs)
                        next_service_report = now + max(1, interval)
                        self._emit("service_reported", "服务状态已上报", server_code=server_code)
                    except (ApiError, OSError, psutil.Error) as exc:
                        next_service_report = now + min(self.config.sample_interval, 10)
                        self._emit("offline", f"服务状态上报失败：{exc}", server_code=server_code)

                if (
                    server_code
                    and monitor_configs
                    and latest_snapshot is not None
                    and latest_statuses
                    and now >= next_healing_check
                ):
                    next_healing_check = now + self._monitor_check_interval(monitor_configs)
                    self._check_healing_rules(
                        api,
                        server_code,
                        monitor_configs,
                        latest_snapshot,
                        latest_statuses,
                    )

                if server_code:
                    self._flush(api, server_code)
                self._stop.wait(0.25)

        if self._config_websocket:
            self._config_websocket.stop()
        self._emit("stopped", "监控已停止", server_code=server_code)

    def _monitor_check_interval(self, configs: list[MonitorConfig]) -> float:
        return max(
            1,
            min(
                (config.check_interval for config in configs if config.check_interval),
                default=self.config.sample_interval,
            ),
        )

    def _start_config_websocket(self, server_code: str) -> None:
        if self._config_websocket:
            self._config_websocket.stop()
        self._config_websocket = ConfigWebSocketClient(
            self.config,
            server_code,
            self._queue_config_update,
            lambda kind, message: self._config_messages.put((kind, message)),
        )
        self._config_websocket.start()

    def _queue_config_update(self, configs: list[MonitorConfig]) -> None:
        self._config_messages.put(("configs", configs))
        self._config_changed.set()

    def _drain_config_messages(
        self, current: list[MonitorConfig], server_code: str | None
    ) -> list[MonitorConfig]:
        configs = current
        while True:
            try:
                kind, value = self._config_messages.get_nowait()
            except Empty:
                break
            if kind == "configs" and isinstance(value, list):
                configs = value
                config_ids = {config.config_id for config in configs}
                self._active_alerts.intersection_update(config_ids)
                self._active_alert_ids = {
                    config_id: alert_id
                    for config_id, alert_id in self._active_alert_ids.items()
                    if config_id in config_ids
                }
                self._emit(
                    "config_loaded",
                    f"WebSocket 已更新 {len(configs)} 项服务配置",
                    server_code=server_code,
                    monitor_configs=configs,
                )
            elif isinstance(value, str):
                if kind == "healing_rules_changed":
                    self._healing_rules_changed.set()
                self._emit(kind, value, server_code=server_code)
        return configs

    def _flush(self, api: AgentApi, server_code: str) -> None:
        for item_id, payload in self.queue.due():
            try:
                api.report_metrics(payload)
                self.queue.acknowledge(item_id)
                self._emit(
                    "reported",
                    "指标已上报",
                    queue_size=self.queue.count(),
                    server_code=server_code,
                )
            except ApiError as exc:
                self.queue.postpone(item_id)
                self._emit(
                    "offline",
                    str(exc),
                    queue_size=self.queue.count(),
                    server_code=server_code,
                )
                break

    def _report_alerts(
        self,
        api: AgentApi,
        server_code: str,
        configs: list[MonitorConfig],
        statuses: list[ServiceStatus],
        snapshot: MetricSnapshot | None = None,
    ) -> None:
        for config, status in zip(configs, statuses, strict=True):
            abnormal = status.is_alive == 0 or status.port_status == 0
            if not abnormal:
                self._active_alerts.discard(config.config_id)
                self._active_alert_ids.pop(config.config_id, None)
                continue
            if config.config_id in self._active_alerts:
                continue
            now = time.time()
            last_report = self._last_alert_reports.get(config.config_id)
            if (
                last_report is not None
                and now - last_report < self.config.alert_cooldown_minutes * 60
            ):
                continue
            rule_id = self._matching_rule_id(api, server_code, config, status, snapshot)
            self._last_alert_reports[config.config_id] = now
            try:
                self.config_store.save_alert_report_time(config.config_id, now)
            except OSError as exc:
                self._emit(
                    "alert_error",
                    f"告警冷却状态保存失败：{exc}",
                    server_code=server_code,
                )
            try:
                alert_id = api.report_alert_event(
                    alert_event_payload(server_code, config, status, rule_id=rule_id)
                )
                self._active_alerts.add(config.config_id)
                if alert_id is not None:
                    self._active_alert_ids[config.config_id] = alert_id
                self._emit(
                    "alert_reported",
                    f"已上报告警：{config.service_name}"
                    + (f"，关联规则 #{rule_id}" if rule_id is not None else ""),
                    server_code=server_code,
                )
            except ApiError as exc:
                self._emit(
                    "alert_error",
                    f"告警上报失败：{exc}",
                    server_code=server_code,
                )

    def _matching_rule_id(
        self,
        api: AgentApi,
        server_code: str,
        config: MonitorConfig,
        status: ServiceStatus,
        snapshot: MetricSnapshot | None,
    ) -> int | None:
        if snapshot is None:
            return None
        try:
            rules = api.get_bound_healing_rules(server_code, config.config_id)
        except (ApiError, TypeError, ValueError) as exc:
            self._emit(
                "healing_error",
                f"{config.service_name} 告警关联规则拉取失败：{exc}",
                server_code=server_code,
            )
            return None
        for rule in rules:
            try:
                if evaluate_trigger(rule.trigger_config, snapshot, status):
                    return rule.rule_id
            except TriggerConfigError as exc:
                self._emit(
                    "healing_error",
                    f"自愈规则 {rule.rule_name} 无法关联告警：{exc}",
                    server_code=server_code,
                )
        return None

    def _check_healing_rules(
        self,
        api: AgentApi,
        server_code: str,
        configs: list[MonitorConfig],
        snapshot: MetricSnapshot,
        statuses: dict[int, ServiceStatus],
        now: float | None = None,
    ) -> None:
        checked_at = time.monotonic() if now is None else now
        for config in configs:
            interval = max(1, config.check_interval or self.config.sample_interval)
            last_check = self._last_healing_checks.get(config.config_id)
            if last_check is not None and checked_at - last_check < interval:
                continue
            self._last_healing_checks[config.config_id] = checked_at
            status = statuses.get(config.config_id)
            if status is None:
                continue
            try:
                rules = api.get_bound_healing_rules(server_code, config.config_id)
            except (ApiError, TypeError, ValueError) as exc:
                self._emit(
                    "healing_error",
                    f"{config.service_name} 自愈规则拉取失败：{exc}",
                    server_code=server_code,
                )
                continue

            self._emit(
                "healing_rules_loaded",
                server_code=server_code,
                healing_rules={config.config_id: rules},
            )

            for rule in rules:
                try:
                    matched = evaluate_trigger(rule.trigger_config, snapshot, status)
                except TriggerConfigError as exc:
                    self._emit(
                        "healing_evaluated",
                        f"自愈规则 {rule.rule_name} 配置无效",
                        server_code=server_code,
                        healing_result=HealingResult(
                            config_id=config.config_id,
                            rule_id=rule.rule_id,
                            status="CONFIG_ERROR",
                            output=str(exc),
                            finished_at=int(time.time() * 1000),
                        ),
                    )
                    self._emit(
                        "healing_error",
                        f"自愈规则 {rule.rule_name} 配置无效：{exc}",
                        server_code=server_code,
                    )
                    continue
                if not matched:
                    self._emit(
                        "healing_evaluated",
                        f"自愈规则 {rule.rule_name} 条件未满足",
                        server_code=server_code,
                        healing_result=HealingResult(
                            config_id=config.config_id,
                            rule_id=rule.rule_id,
                            status="WAITING",
                            finished_at=int(time.time() * 1000),
                        ),
                    )
                    continue

                self._emit(
                    "healing_started",
                    f"自愈规则 {rule.rule_name} 开始执行，命令："
                    f"{rule.execute_command or '<未配置>'}",
                    server_code=server_code,
                    healing_result=HealingResult(
                        config_id=config.config_id,
                        rule_id=rule.rule_id,
                        status="RUNNING",
                    ),
                )
                execution_error: ValueError | None = None
                try:
                    execution = self._execute_rule_with_early_recovery(
                        rule,
                        config,
                        server_code,
                    )
                except ValueError as exc:
                    failed_at = int(time.time() * 1000)
                    execution_error = exc
                    execution = HealingExecution(
                        status="FAILED",
                        output=str(exc),
                        retry_count=0,
                        started_at=failed_at,
                        finished_at=failed_at,
                    )
                if execution.status == "TIMEOUT":
                    execution = self._resolve_timeout_result(
                        rule,
                        config,
                        execution,
                        server_code,
                    )

                payload = {
                    "serverCode": server_code,
                    "configId": config.config_id,
                    "serviceName": config.service_name,
                    "ruleId": rule.rule_id,
                    "triggerDetail": trigger_detail(snapshot, status),
                    "executeCommand": rule.execute_command,
                    "commandOutput": execution.output,
                    "executionStatus": execution.status,
                    "retryCount": execution.retry_count,
                    "startedAt": _timestamp_ms_to_iso(execution.started_at),
                    "finishedAt": _timestamp_ms_to_iso(execution.finished_at),
                    "durationMs": execution.duration_ms,
                }
                alert_id = self._active_alert_ids.get(config.config_id)
                if alert_id is not None:
                    payload["alertId"] = alert_id
                result = {
                    "SUCCESS": "成功",
                    "TIMEOUT": "超时",
                    "FAILED": "失败",
                }.get(execution.status, execution.status)
                healing_result = HealingResult(
                    config_id=config.config_id,
                    rule_id=rule.rule_id,
                    status=execution.status,
                    output=execution.output,
                    retry_count=execution.retry_count,
                    duration_ms=execution.duration_ms,
                    finished_at=execution.finished_at,
                )
                self._emit(
                    "healing_executed",
                    f"自愈规则 {rule.rule_name} 执行结束：{result}"
                    + (
                        f"，退出码 {execution.exit_code}"
                        if execution.exit_code is not None
                        else ""
                    ),
                    server_code=server_code,
                    healing_result=healing_result,
                )
                report_context = (
                    f"serverCode={server_code}, configId={config.config_id}, "
                    f"ruleId={rule.rule_id}, status={execution.status}"
                )
                logger.info("开始上报自愈日志 (%s)", report_context)
                try:
                    api.report_healing_log(payload)
                except ApiError as exc:
                    self._emit(
                        "healing_error",
                        f"自愈规则 {rule.rule_name} 自愈日志上报失败：{exc}",
                        server_code=server_code,
                        healing_result=healing_result,
                    )
                else:
                    logger.info("自愈日志上报成功 (%s)", report_context)
                if execution_error is not None:
                    self._emit(
                        "healing_error",
                        f"自愈规则 {rule.rule_name} 无法执行：{execution_error}",
                        server_code=server_code,
                        healing_result=healing_result,
                    )

    def _execute_rule_with_early_recovery(
        self,
        rule: HealingRule,
        config: MonitorConfig,
        server_code: str,
    ) -> HealingExecution:
        results: SimpleQueue[HealingExecution | Exception] = SimpleQueue()
        cancelled = threading.Event()
        started_at = int(time.time() * 1000)

        def run_command() -> None:
            try:
                results.put(execute_rule(rule, cancelled=cancelled.is_set))
            except Exception as exc:  # noqa: BLE001 - thread boundary must forward failures
                results.put(exc)

        threading.Thread(
            target=run_command,
            name=f"serverops-healing-{rule.rule_id}",
            daemon=True,
        ).start()

        context = (
            f"serverCode={server_code}, configId={config.config_id}, "
            f"ruleId={rule.rule_id}"
        )
        next_check = time.monotonic() + EARLY_RECOVERY_INITIAL_DELAY
        check_count = 0
        while True:
            now = time.monotonic()
            try:
                result = results.get(timeout=min(0.25, max(0.0, next_check - now)))
            except Empty:
                result = None
            if isinstance(result, Exception):
                raise result
            if isinstance(result, HealingExecution):
                return result

            now = time.monotonic()
            if now < next_check:
                continue
            check_count += 1
            if check_count == 1:
                logger.info(
                    "自愈命令已执行 %.1f 秒，开始每 %.1f 秒检查恢复状态 (%s)",
                    EARLY_RECOVERY_INITIAL_DELAY,
                    EARLY_RECOVERY_INTERVAL,
                    context,
                )
            try:
                recovered = self._trigger_recovered(rule, config)
            except (OSError, psutil.Error, TriggerConfigError) as exc:
                logger.error(
                    "自愈执行中第 %d 次恢复检查失败：%s (%s)",
                    check_count,
                    exc,
                    context,
                )
            else:
                if recovered:
                    cancelled.set()
                    finished_at = int(time.time() * 1000)
                    logger.info(
                        "自愈执行中第 %d 次检查：触发条件已恢复，提前判定执行成功 (%s)",
                        check_count,
                        context,
                    )
                    return HealingExecution(
                        status="SUCCESS",
                        output="",
                        retry_count=0,
                        started_at=started_at,
                        finished_at=finished_at,
                        exit_code=None,
                    )
                logger.info(
                    "自愈执行中第 %d 次检查：触发条件尚未恢复 (%s)",
                    check_count,
                    context,
                )
            next_check = now + EARLY_RECOVERY_INTERVAL

    def _resolve_timeout_result(
        self,
        rule: HealingRule,
        config: MonitorConfig,
        execution: HealingExecution,
        server_code: str,
    ) -> HealingExecution:
        delay = self.config.healing_timeout_recheck_delay
        context = (
            f"serverCode={server_code}, configId={config.config_id}, "
            f"ruleId={rule.rule_id}"
        )
        logger.info(
            "自愈规则 %s 执行超时，等待 %.1f 秒后复检 (%s)",
            rule.rule_name,
            delay,
            context,
        )
        if self._stop.wait(delay):
            logger.warning("自愈超时复检因客户端停止而取消 (%s)", context)
            return execution

        try:
            recovered = self._trigger_recovered(rule, config)
        except (OSError, psutil.Error, TriggerConfigError) as exc:
            logger.error("自愈超时复检失败，保持超时状态：%s (%s)", exc, context)
            return execution

        finished_at = int(time.time() * 1000)
        if recovered:
            logger.info(
                "自愈超时复检完成：触发条件已恢复，判定执行成功 (%s)", context
            )
            return HealingExecution(
                status="SUCCESS",
                output=execution.output,
                retry_count=execution.retry_count,
                started_at=execution.started_at,
                finished_at=finished_at,
                exit_code=execution.exit_code,
            )
        logger.warning("自愈超时复检完成：触发条件仍满足，保持超时状态 (%s)", context)
        return HealingExecution(
            status="TIMEOUT",
            output=execution.output,
            retry_count=execution.retry_count,
            started_at=execution.started_at,
            finished_at=finished_at,
            exit_code=execution.exit_code,
        )

    def _trigger_recovered(self, rule: HealingRule, config: MonitorConfig) -> bool:
        snapshot = self.collector.collect()
        status = self.monitor.check(config, int(time.time() * 1000))
        return not evaluate_trigger(rule.trigger_config, snapshot, status)

    def _emit(
        self,
        kind: str,
        message: str = "",
        snapshot: MetricSnapshot | None = None,
        queue_size: int | None = None,
        server_code: str | None = None,
        monitor_configs: list[MonitorConfig] | None = None,
        service_statuses: list[ServiceStatus] | None = None,
        healing_rules: dict[int, list[HealingRule]] | None = None,
        healing_result: HealingResult | None = None,
    ) -> None:
        event = ServiceEvent(
            kind=kind,
            message=message,
            snapshot=snapshot,
            queue_size=self.queue.count() if queue_size is None else queue_size,
            server_code=server_code,
            monitor_configs=monitor_configs,
            service_statuses=service_statuses,
            healing_rules=healing_rules,
            healing_result=healing_result,
        )
        self._log_event(event)
        self.callback(event)

    @staticmethod
    def _log_event(event: ServiceEvent) -> None:
        if not event.message and event.kind in DEBUG_EVENTS:
            return
        message = event.message or event.kind
        context = []
        if event.server_code:
            context.append(f"serverCode={event.server_code}")
        if event.healing_result:
            result = event.healing_result
            context.extend(
                (
                    f"configId={result.config_id}",
                    f"ruleId={result.rule_id}",
                    f"status={result.status}",
                )
            )
            if result.status not in {"RUNNING", "WAITING", "CONFIG_ERROR"}:
                context.extend(
                    (f"durationMs={result.duration_ms}", f"retryCount={result.retry_count}")
                )
        if context:
            message = f"{message} ({', '.join(context)})"
        if event.kind == "healing_executed" and event.healing_result:
            output = event.healing_result.output or "<无输出>"
            message = f"{message}\n命令执行输出:\n{output}"

        if event.kind in ERROR_EVENTS:
            logger.error(message)
        elif event.kind in WARNING_EVENTS:
            logger.warning(message)
        elif event.kind in DEBUG_EVENTS:
            logger.debug(message)
        else:
            logger.info(message)


def _timestamp_ms_to_iso(timestamp_ms: int) -> str:
    return datetime.fromtimestamp(timestamp_ms / 1000, UTC).astimezone().isoformat(
        timespec="milliseconds"
    )
