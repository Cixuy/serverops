from __future__ import annotations

import operator
import re
import subprocess
import tempfile
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .models import HealingRule, MetricSnapshot, ServiceStatus

MAX_LOG_BYTES = 1024 * 1024
MAX_COMMAND_OUTPUT = 64 * 1024
SHELL_ERROR_MARKERS = (
    "the system cannot find the path specified",
    "the system cannot find the file",
    "is not recognized as an internal or external command",
    "系统找不到指定的路径",
    "系统找不到指定的文件",
    "不是内部或外部命令",
)


class TriggerConfigError(ValueError):
    pass


_COMPARATORS: dict[str, Callable[[float, float], bool]] = {
    "=": operator.eq,
    "==": operator.eq,
    "!=": operator.ne,
    ">": operator.gt,
    ">=": operator.ge,
    "<": operator.lt,
    "<=": operator.le,
}


def evaluate_trigger(
    trigger_config: dict[str, Any],
    snapshot: MetricSnapshot,
    status: ServiceStatus,
    now: float | None = None,
) -> bool:
    """Evaluate a nested AND/OR trigger expression against the latest samples."""
    return _evaluate_node(trigger_config, snapshot, status, time.time() if now is None else now)


def trigger_detail(snapshot: MetricSnapshot, status: ServiceStatus) -> dict[str, Any]:
    return {
        "metrics": {
            "cpu_usage": snapshot.cpu_usage,
            "memory_usage": snapshot.memory_usage,
            "disk_usage": _disk_usage(snapshot),
            "network_in_kbps": snapshot.network_in_kbps,
            "network_out_kbps": snapshot.network_out_kbps,
        },
        "service_status": {
            "process": status.is_alive,
            "port": status.port_status,
        },
        "sampled_at": snapshot.timestamp,
        "checked_at": status.last_check_time,
    }


def _evaluate_node(
    node: dict[str, Any], snapshot: MetricSnapshot, status: ServiceStatus, now: float
) -> bool:
    conditions = node.get("conditions")
    if isinstance(conditions, list):
        if not conditions:
            raise TriggerConfigError("conditions 不能为空")
        logic = str(node.get("logic") or "AND").upper()
        if logic not in {"AND", "OR"}:
            raise TriggerConfigError(f"不支持的逻辑运算符: {logic}")
        values = []
        for condition in conditions:
            if not isinstance(condition, dict):
                raise TriggerConfigError("condition 必须是对象")
            values.append(_evaluate_node(condition, snapshot, status, now))
        return all(values) if logic == "AND" else any(values)

    condition_type = str(node.get("type") or ("METRIC" if node.get("metric") else "")).upper()
    if condition_type == "METRIC":
        return _evaluate_metric(node, snapshot)
    if condition_type == "SERVICE_STATUS":
        return _evaluate_service_status(node, status)
    if condition_type == "LOG_MATCH":
        return _evaluate_log_match(node, now)
    raise TriggerConfigError(f"不支持的条件类型: {condition_type or '<empty>'}")


def _evaluate_metric(condition: dict[str, Any], snapshot: MetricSnapshot) -> bool:
    metric = str(
        condition.get("metric")
        or condition.get("metric_name")
        or condition.get("metricName")
        or ""
    )
    values = {
        "cpu_usage": snapshot.cpu_usage,
        "memory_usage": snapshot.memory_usage,
        "disk_usage": _disk_usage(snapshot),
        "network_in_kbps": snapshot.network_in_kbps,
        "network_out_kbps": snapshot.network_out_kbps,
    }
    if metric not in values:
        raise TriggerConfigError(f"不支持的指标: {metric or '<empty>'}")
    threshold = _condition_value(condition)
    if isinstance(threshold, bool):
        raise TriggerConfigError(f"METRIC {metric} 的 value/threshold 必须是数字")
    try:
        expected = float(threshold)
    except (TypeError, ValueError) as exc:
        raise TriggerConfigError(
            f"METRIC {metric} 的 value/threshold 必须是数字"
        ) from exc
    return _compare(values[metric], expected, condition.get("operator"))


def _evaluate_service_status(
    condition: dict[str, Any], status: ServiceStatus
) -> bool:
    metric = str(condition.get("metric") or "")
    values = {"process": status.is_alive, "port": status.port_status}
    if metric not in values:
        raise TriggerConfigError(f"不支持的服务状态: {metric or '<empty>'}")
    expected = _condition_value(condition)
    if isinstance(expected, bool):
        raise TriggerConfigError(f"SERVICE_STATUS {metric} 的 value 必须是 0 或 1")
    try:
        expected_value = float(expected)
    except (TypeError, ValueError) as exc:
        raise TriggerConfigError(
            f"SERVICE_STATUS {metric} 的 value 必须是 0 或 1"
        ) from exc
    if expected_value not in {0.0, 1.0}:
        raise TriggerConfigError(f"SERVICE_STATUS {metric} 的 value 必须是 0 或 1")
    return _compare(float(values[metric]), expected_value, condition.get("operator"))


def _condition_value(condition: dict[str, Any]) -> Any:
    return condition.get("value") if "value" in condition else condition.get("threshold")


def _evaluate_log_match(condition: dict[str, Any], now: float) -> bool:
    path_value = condition.get("log_file_path")
    pattern = condition.get("regex_pattern")
    if not isinstance(path_value, str) or not path_value:
        raise TriggerConfigError("LOG_MATCH 缺少 log_file_path")
    if not isinstance(pattern, str) or not pattern:
        raise TriggerConfigError("LOG_MATCH 缺少 regex_pattern")
    try:
        window = float(condition.get("match_window_seconds", 60))
    except (TypeError, ValueError) as exc:
        raise TriggerConfigError("match_window_seconds 必须是正数") from exc
    if window <= 0:
        raise TriggerConfigError("match_window_seconds 必须是正数")

    path = Path(path_value).expanduser()
    try:
        stat = path.stat()
    except OSError:
        return False
    if now - stat.st_mtime > window:
        return False
    try:
        with path.open("rb") as file:
            file.seek(max(0, stat.st_size - MAX_LOG_BYTES))
            content = file.read(MAX_LOG_BYTES).decode("utf-8", errors="replace")
        matched = re.search(pattern, content) is not None
    except OSError:
        return False
    except re.error as exc:
        raise TriggerConfigError(f"无效的日志正则表达式: {exc}") from exc
    operator_name = str(condition.get("operator") or "=")
    if operator_name in {"=", "=="}:
        return matched
    if operator_name == "!=":
        return not matched
    raise TriggerConfigError(f"LOG_MATCH 不支持运算符: {operator_name}")


def _compare(actual: float, expected: float, operator_name: Any) -> bool:
    name = str(operator_name or "=")
    comparator = _COMPARATORS.get(name)
    if comparator is None:
        raise TriggerConfigError(f"不支持的比较运算符: {name}")
    return comparator(actual, expected)


def _disk_usage(snapshot: MetricSnapshot) -> float:
    return max((disk.disk_usage for disk in snapshot.disks), default=0.0)


@dataclass(slots=True, frozen=True)
class HealingExecution:
    status: str
    output: str
    retry_count: int
    started_at: int
    finished_at: int
    exit_code: int | None = None

    @property
    def duration_ms(self) -> int:
        return max(0, self.finished_at - self.started_at)


def execute_rule(
    rule: HealingRule,
    wait: Callable[[float], None] = time.sleep,
    run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    cancelled: Callable[[], bool] | None = None,
) -> HealingExecution:
    if not rule.execute_command.strip():
        raise ValueError("自愈规则缺少 executeCommand")

    started_at = int(time.time() * 1000)
    output = ""
    final_status = "FAILED"
    final_exit_code: int | None = None
    retry_count = 0
    is_cancelled = cancelled or (lambda: False)
    for attempt in range(rule.max_retries + 1):
        if is_cancelled():
            break
        retry_count = attempt
        # File-backed capture prevents detached Windows GUI processes from keeping
        # PIPE handles open after cmd.exe has already returned.
        with (
            tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace") as stdout,
            tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace") as stderr,
        ):
            try:
                final_exit_code = None
                result = run(
                    rule.execute_command,
                    shell=True,
                    stdout=stdout,
                    stderr=stderr,
                    text=True,
                    errors="replace",
                    timeout=rule.timeout_seconds,
                )
                final_exit_code = result.returncode
                output = _command_output(
                    _read_capture(stdout) or result.stdout,
                    _read_capture(stderr) or result.stderr,
                )
                if result.returncode == 0 and not command_output_has_shell_error(output):
                    final_status = "SUCCESS"
                    break
                final_status = "FAILED"
            except subprocess.TimeoutExpired as exc:
                final_exit_code = None
                output = _command_output(
                    _read_capture(stdout) or exc.stdout,
                    _read_capture(stderr) or exc.stderr,
                )
                final_status = (
                    "FAILED" if command_output_has_shell_error(output) else "TIMEOUT"
                )
            except OSError as exc:
                final_exit_code = None
                output = str(exc)
                final_status = "FAILED"
        if is_cancelled():
            break
        if attempt < rule.max_retries and rule.retry_interval:
            wait(rule.retry_interval)

    finished_at = int(time.time() * 1000)
    return HealingExecution(
        status=final_status,
        output=output,
        retry_count=retry_count,
        started_at=started_at,
        finished_at=finished_at,
        exit_code=final_exit_code,
    )


def _command_output(stdout: Any, stderr: Any) -> str:
    parts = []
    for value in (stdout, stderr):
        if isinstance(value, bytes):
            value = value.decode(errors="replace")
        if value:
            parts.append(str(value).strip())
    return "\n".join(parts)[-MAX_COMMAND_OUTPUT:]


def command_output_has_shell_error(output: str) -> bool:
    normalized = output.casefold()
    return any(marker in normalized for marker in SHELL_ERROR_MARKERS)


def _read_capture(stream: Any) -> str:
    stream.flush()
    stream.seek(0)
    return str(stream.read())
