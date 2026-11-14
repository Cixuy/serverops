import io
import logging
import sys
import threading
import time
from datetime import datetime

from serverops_client.api import ApiError
from serverops_client.config import AgentConfig
from serverops_client.healing import HealingExecution
from serverops_client.logging_config import configure_logging
from serverops_client.models import HealingRule, MetricSnapshot, MonitorConfig, ServiceStatus
from serverops_client.service import AgentService, HealingResult


class FakeQueue:
    def count(self):
        return 0


class FakeApi:
    def __init__(self):
        self.alerts = []

    def report_alert_event(self, payload):
        self.alerts.append(payload)
        return 10086


class FailingAlertApi:
    def __init__(self):
        self.attempts = []

    def report_alert_event(self, payload):
        self.attempts.append(payload)
        raise ApiError("server unavailable")


class FakeHealingApi:
    def __init__(self):
        self.queries = []
        self.logs = []

    def get_bound_healing_rules(self, server_code, config_id):
        self.queries.append((server_code, config_id))
        return [
            HealingRule(
                9,
                "Restart Nginx",
                {
                    "type": "SERVICE_STATUS",
                    "metric": "process",
                    "operator": "=",
                    "value": 0,
                },
                "restart nginx",
            )
        ]

    def report_healing_log(self, payload):
        self.logs.append(payload)


class FailingHealingLogApi(FakeHealingApi):
    def report_healing_log(self, payload):
        self.logs.append(payload)
        raise ApiError("server unavailable")


class FakeConfigStore:
    def __init__(self):
        self.report_times = {}

    def save_alert_report_time(self, config_id, timestamp):
        self.report_times[config_id] = timestamp


def service_for_alert_test() -> AgentService:
    service = AgentService.__new__(AgentService)
    service._active_alerts = set()
    service._active_alert_ids = {}
    service._last_alert_reports = {}
    service._last_healing_checks = {}
    service.config = AgentConfig(agent_token="x", alert_cooldown_minutes=30)
    service.config_store = FakeConfigStore()
    service.queue = FakeQueue()
    service.callback = lambda _event: None
    service._refresh_requested = threading.Event()
    service._force_report = threading.Event()
    return service


def test_service_refresh_request_is_scheduled() -> None:
    service = service_for_alert_test()

    service.refresh_configs()

    assert service._refresh_requested.is_set()
    assert service._force_report.is_set()


def test_monitor_check_interval_ignores_alert_cooldown() -> None:
    service = service_for_alert_test()
    service.config = AgentConfig(
        agent_token="x", sample_interval=5, alert_cooldown_minutes=1440
    )

    assert service._monitor_check_interval(
        [MonitorConfig(7, "Nginx", check_interval=12)]
    ) == 12


def test_service_event_carries_latest_process_and_port_statuses() -> None:
    service = service_for_alert_test()
    events = []
    service.callback = events.append
    status = ServiceStatus(7, "Nginx", 1, 42, 1, 123)

    service._emit("service_checked", service_statuses=[status])

    assert events[-1].kind == "service_checked"
    assert events[-1].service_statuses == [status]


def test_service_alert_obeys_cooldown_after_recovery(monkeypatch) -> None:
    service = service_for_alert_test()
    api = FakeApi()
    now = [1_000.0]
    monkeypatch.setattr("serverops_client.service.time.time", lambda: now[0])
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    down = ServiceStatus(7, "Nginx", 0, 0, 0, 123, "进程未运行")
    healthy = ServiceStatus(7, "Nginx", 1, 42, 1, 456)

    service._report_alerts(api, "SRV-1", [config], [down])
    service._report_alerts(api, "SRV-1", [config], [down])
    assert len(api.alerts) == 1

    service._report_alerts(api, "SRV-1", [config], [healthy])
    service._report_alerts(api, "SRV-1", [config], [down])
    assert len(api.alerts) == 1

    now[0] += 30 * 60 + 1
    service._report_alerts(api, "SRV-1", [config], [down])
    assert len(api.alerts) == 2
    assert service.config_store.report_times[7] == now[0]


def test_failed_alert_report_obeys_configured_cooldown(monkeypatch) -> None:
    service = service_for_alert_test()
    service.config = AgentConfig(agent_token="x", alert_cooldown_minutes=5)
    api = FailingAlertApi()
    now = [1_000.0]
    monkeypatch.setattr("serverops_client.service.time.time", lambda: now[0])
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    down = ServiceStatus(7, "Nginx", 0, 0, 0, 123, "进程未运行")

    service._report_alerts(api, "SRV-1", [config], [down])
    now[0] += 30
    service._report_alerts(api, "SRV-1", [config], [down])
    assert len(api.attempts) == 1

    now[0] += 5 * 60
    service._report_alerts(api, "SRV-1", [config], [down])
    assert len(api.attempts) == 2
    assert service.config_store.report_times[7] == now[0]


def test_alert_report_includes_first_matching_rule() -> None:
    service = service_for_alert_test()

    class RuleAlertApi(FakeApi, FakeHealingApi):
        def __init__(self):
            FakeApi.__init__(self)
            self.queries = []
            self.logs = []

    api = RuleAlertApi()
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    down = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    snapshot = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)

    service._report_alerts(api, "SRV-1", [config], [down], snapshot)

    assert api.queries == [("SRV-1", 7)]
    assert api.alerts[0]["rule_id"] == 9


def test_service_executes_and_reports_matching_healing_rule(monkeypatch) -> None:
    service = service_for_alert_test()
    api = FakeHealingApi()
    events = []
    service.callback = events.append
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)
    execution = HealingExecution("SUCCESS", "restarted", 0, 1_000, 1_250)
    monkeypatch.setattr(
        "serverops_client.service.execute_rule", lambda _rule, **_kwargs: execution
    )

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    assert api.queries == [("SRV-1", 7)]
    assert api.logs[0]["executionStatus"] == "SUCCESS"
    assert api.logs[0]["triggerDetail"]["service_status"]["process"] == 0
    assert api.logs[0]["durationMs"] == 250
    assert datetime.fromisoformat(api.logs[0]["startedAt"]).timestamp() == 1
    assert datetime.fromisoformat(api.logs[0]["finishedAt"]).timestamp() == 1.25
    assert "alertId" not in api.logs[0]
    assert events[0].kind == "healing_rules_loaded"
    assert events[0].healing_rules == {7: api.get_bound_healing_rules("SRV-1", 7)}
    assert events[1].kind == "healing_started"
    assert events[1].healing_result == HealingResult(7, 9, "RUNNING")
    assert events[-1].kind == "healing_executed"
    assert events[-1].healing_result == HealingResult(
        7, 9, "SUCCESS", "restarted", 0, 250, 1_250
    )


def test_service_checks_healing_by_each_service_interval(monkeypatch) -> None:
    service = service_for_alert_test()
    api = FakeHealingApi()
    config = MonitorConfig(7, "Nginx", "nginx", 8080, check_interval=30)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)
    execution = HealingExecution("SUCCESS", "restarted", 0, 1_000, 1_250)
    monkeypatch.setattr(
        "serverops_client.service.execute_rule", lambda _rule, **_kwargs: execution
    )

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status}, now=100)
    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status}, now=110)
    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status}, now=130)

    assert len(api.logs) == 2


def test_service_marks_running_command_success_as_soon_as_trigger_recovers(
    monkeypatch,
) -> None:
    stream = io.StringIO()
    configure_logging(level=logging.INFO, stream=stream, use_color=False)
    service = service_for_alert_test()
    worker_finished = threading.Event()

    class Collector:
        def collect(self):
            return MetricSnapshot(789, "host", 10, 4, 20, 100, 500, 1, 2)

    class Monitor:
        def check(self, _config, timestamp):
            return ServiceStatus(7, "Nginx", 1, 42, 1, timestamp)

    def slow_command(_rule, cancelled):
        while not cancelled():
            time.sleep(0.001)
        worker_finished.set()
        return HealingExecution("TIMEOUT", "", 0, 1_000, 60_000)

    service.collector = Collector()
    service.monitor = Monitor()
    monkeypatch.setattr("serverops_client.service.execute_rule", slow_command)
    monkeypatch.setattr("serverops_client.service.EARLY_RECOVERY_INITIAL_DELAY", 0)
    monkeypatch.setattr("serverops_client.service.EARLY_RECOVERY_INTERVAL", 0.01)
    rule = FakeHealingApi().get_bound_healing_rules("SRV-1", 7)[0]
    config = MonitorConfig(7, "Nginx", "nginx", 8080)

    result = service._execute_rule_with_early_recovery(rule, config, "SRV-1")

    assert result.status == "SUCCESS"
    assert worker_finished.wait(1)
    output = stream.getvalue()
    assert "开始每 0.0 秒检查恢复状态" in output
    assert "触发条件已恢复，提前判定执行成功" in output


def test_service_marks_timeout_success_when_trigger_recovers(monkeypatch) -> None:
    stream = io.StringIO()
    configure_logging(level=logging.INFO, stream=stream, use_color=False)
    service = service_for_alert_test()
    service.config.healing_timeout_recheck_delay = 5
    waits = []

    class StopEvent:
        def wait(self, delay):
            waits.append(delay)
            return False

    class Collector:
        def collect(self):
            return MetricSnapshot(789, "host", 10, 4, 20, 100, 500, 1, 2)

    class Monitor:
        def check(self, _config, timestamp):
            return ServiceStatus(7, "Nginx", 1, 42, 1, timestamp)

    service._stop = StopEvent()
    service.collector = Collector()
    service.monitor = Monitor()
    api = FakeHealingApi()
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)
    execution = HealingExecution("TIMEOUT", "still running", 0, 1_000, 61_000)
    monkeypatch.setattr(
        "serverops_client.service.execute_rule", lambda _rule, **_kwargs: execution
    )

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    assert waits == [5]
    assert api.logs[0]["executionStatus"] == "SUCCESS"
    output = stream.getvalue()
    assert "执行超时，等待 5.0 秒后复检" in output
    assert "触发条件已恢复，判定执行成功" in output
    assert "执行结束：成功" in output


def test_service_keeps_timeout_when_trigger_is_still_matched(monkeypatch) -> None:
    service = service_for_alert_test()
    service.config.healing_timeout_recheck_delay = 0

    class StopEvent:
        def wait(self, _delay):
            return False

    class Collector:
        def collect(self):
            return MetricSnapshot(789, "host", 10, 4, 20, 100, 500, 1, 2)

    class Monitor:
        def check(self, _config, timestamp):
            return ServiceStatus(7, "Nginx", 0, 0, 1, timestamp)

    service._stop = StopEvent()
    service.collector = Collector()
    service.monitor = Monitor()
    api = FakeHealingApi()
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)
    execution = HealingExecution("TIMEOUT", "still running", 0, 1_000, 61_000)
    monkeypatch.setattr(
        "serverops_client.service.execute_rule", lambda _rule, **_kwargs: execution
    )

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    assert api.logs[0]["executionStatus"] == "TIMEOUT"


def test_service_logs_execution_lifecycle_and_healing_log_report(monkeypatch) -> None:
    stream = io.StringIO()
    configure_logging(level=logging.INFO, stream=stream, use_color=False)
    service = service_for_alert_test()
    api = FakeHealingApi()
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)
    execution = HealingExecution("SUCCESS", "restarted", 0, 1_000, 1_250)
    monkeypatch.setattr(
        "serverops_client.service.execute_rule", lambda _rule, **_kwargs: execution
    )

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    output = stream.getvalue()
    assert "自愈规则 Restart Nginx 开始执行" in output
    assert "自愈规则 Restart Nginx 执行结束：成功" in output
    assert "命令执行输出:\nrestarted" in output
    assert "开始上报自愈日志" in output
    assert "自愈日志上报成功" in output
    assert len(api.logs) == 1


def test_service_reports_failed_healing_log_when_command_is_missing() -> None:
    service = service_for_alert_test()
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)

    class MissingCommandApi(FakeHealingApi):
        def get_bound_healing_rules(self, server_code, config_id):
            self.queries.append((server_code, config_id))
            return [
                HealingRule(
                    9,
                    "Restart Nginx",
                    {
                        "type": "SERVICE_STATUS",
                        "metric": "process",
                        "operator": "=",
                        "value": 0,
                    },
                    "",
                )
            ]

    api = MissingCommandApi()

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    assert len(api.logs) == 1
    assert api.logs[0]["executionStatus"] == "FAILED"
    assert api.logs[0]["commandOutput"] == "自愈规则缺少 executeCommand"


def test_service_logs_single_failed_healing_log_report_attempt(monkeypatch) -> None:
    stream = io.StringIO()
    configure_logging(level=logging.INFO, stream=stream, use_color=False)
    service = service_for_alert_test()
    api = FailingHealingLogApi()
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)
    execution = HealingExecution("FAILED", "restart failed", 1, 1_000, 1_250)
    monkeypatch.setattr(
        "serverops_client.service.execute_rule", lambda _rule, **_kwargs: execution
    )

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    output = stream.getvalue()
    assert len(api.logs) == 1
    assert "开始上报自愈日志" in output
    assert "自愈日志上报失败：server unavailable" in output


def test_service_links_healing_log_to_reported_alert(monkeypatch) -> None:
    service = service_for_alert_test()
    api = FakeHealingApi()
    api.report_alert_event = lambda _payload: 10086
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)
    execution = HealingExecution("SUCCESS", "restarted", 0, 1_000, 1_250)
    monkeypatch.setattr(
        "serverops_client.service.execute_rule", lambda _rule, **_kwargs: execution
    )

    service._report_alerts(api, "SRV-1", [config], [status], metric)
    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    assert api.logs[0]["alertId"] == 10086


def test_service_executes_real_command_for_flat_threshold_rule() -> None:
    service = service_for_alert_test()
    events = []
    service.callback = events.append
    command = f'"{sys.executable}" -c "print(\'healed\')"'

    class RealCommandApi(FakeHealingApi):
        def get_bound_healing_rules(self, server_code, config_id):
            self.queries.append((server_code, config_id))
            return [
                HealingRule(
                    9,
                    "Restart Nginx",
                    {
                        "metric": "cpu_usage",
                        "operator": ">",
                        "threshold": 5,
                        "duration": 60,
                    },
                    command,
                )
            ]

    api = RealCommandApi()
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 0, 0, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: status})

    assert api.logs[0]["executionStatus"] == "SUCCESS"
    assert api.logs[0]["commandOutput"] == "healed"
    assert events[-1].kind == "healing_executed"
    assert events[-1].healing_result is not None
    assert events[-1].healing_result.output == "healed"


def test_service_reports_rule_condition_not_matched() -> None:
    service = service_for_alert_test()
    api = FakeHealingApi()
    events = []
    service.callback = events.append
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    healthy = ServiceStatus(7, "Nginx", 1, 42, 1, 456)
    metric = MetricSnapshot(123, "host", 10, 4, 20, 100, 500, 1, 2)

    service._check_healing_rules(api, "SRV-1", [config], metric, {7: healthy})

    assert events[-1].kind == "healing_evaluated"
    assert events[-1].healing_result is not None
    assert events[-1].healing_result.status == "WAITING"
    assert api.logs == []
