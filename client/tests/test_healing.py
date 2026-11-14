import subprocess
import time

import pytest

from serverops_client.healing import TriggerConfigError, evaluate_trigger, execute_rule
from serverops_client.models import (
    DiskMetric,
    HealingRule,
    MetricSnapshot,
    ServiceStatus,
)


def snapshot() -> MetricSnapshot:
    return MetricSnapshot(
        timestamp=123,
        hostname="host",
        cpu_usage=96,
        cpu_count=4,
        memory_usage=85,
        memory_used_mb=100,
        memory_total_mb=200,
        network_in_kbps=12,
        network_out_kbps=34,
        disks=[DiskMetric("/", "ext4", 0, 0, 0, 0, 81, 8, 10)],
    )


def service_status() -> ServiceStatus:
    return ServiceStatus(7, "Nginx", 0, 0, 1, 123)


def test_evaluate_nested_metric_and_service_status_conditions() -> None:
    trigger = {
        "logic": "AND",
        "conditions": [
            {"type": "METRIC", "metric": "memory_usage", "operator": ">", "value": "80"},
            {"type": "SERVICE_STATUS", "metric": "process", "operator": "=", "value": 0},
            {
                "logic": "OR",
                "conditions": [
                    {
                        "type": "METRIC",
                        "metric": "cpu_usage",
                        "operator": ">",
                        "value": "95",
                    },
                    {
                        "type": "METRIC",
                        "metric": "disk_usage",
                        "operator": ">",
                        "value": "90",
                    },
                ],
            },
        ],
    }

    assert evaluate_trigger(trigger, snapshot(), service_status()) is True


@pytest.mark.parametrize(
    ("condition", "message"),
    [
        (
            {"type": "METRIC", "metric": "cpu_usage", "operator": ">", "value": "high"},
            "value/threshold 必须是数字",
        ),
        (
            {"type": "SERVICE_STATUS", "metric": "port", "operator": "=", "value": "up"},
            "value 必须是 0 或 1",
        ),
    ],
)
def test_evaluate_rejects_wrong_value_types(condition, message) -> None:
    with pytest.raises(TriggerConfigError, match=message):
        evaluate_trigger(condition, snapshot(), service_status())


def test_log_match_obeys_regex_and_file_window(tmp_path) -> None:
    log = tmp_path / "app.log"
    log.write_text("ready\nOOM killed process\n", encoding="utf-8")
    condition = {
        "type": "LOG_MATCH",
        "operator": "=",
        "log_file_path": str(log),
        "regex_pattern": r"OOM\s+killed",
        "match_window_seconds": 60,
    }

    assert evaluate_trigger(condition, snapshot(), service_status(), now=time.time()) is True
    assert (
        evaluate_trigger(condition, snapshot(), service_status(), now=time.time() + 61)
        is False
    )


def test_service_status_accepts_string_value_from_rule_editor() -> None:
    condition = {
        "type": "SERVICE_STATUS",
        "metric": "process",
        "operator": "=",
        "value": "0",
    }

    assert evaluate_trigger(condition, snapshot(), service_status()) is True


def test_flat_metric_rule_accepts_numeric_threshold() -> None:
    condition = {
        "metric": "cpu_usage",
        "operator": ">",
        "threshold": 90,
        "duration": 60,
    }

    assert evaluate_trigger(condition, snapshot(), service_status()) is True


def test_execute_rule_retries_nonzero_command_until_success() -> None:
    calls = []

    def run(*_args, **kwargs):
        calls.append(kwargs)
        return subprocess.CompletedProcess(
            args="restart",
            returncode=1 if len(calls) == 1 else 0,
            stdout="restarted" if len(calls) > 1 else "",
            stderr="command reported an error" if len(calls) == 1 else "",
        )

    waits = []
    result = execute_rule(
        HealingRule(3, "Restart", {}, "restart", 5, 2, 4),
        wait=waits.append,
        run=run,
    )

    assert result.status == "SUCCESS"
    assert result.output == "restarted"
    assert result.retry_count == 1
    assert result.exit_code == 0
    assert len(calls) == 2
    assert waits == [4]
    assert calls[0]["timeout"] == 5


def test_execute_rule_reports_failed_nonzero_exit_code() -> None:
    def run(*_args, **_kwargs):
        return subprocess.CompletedProcess(
            args="missing", returncode=1, stdout="", stderr="file not found"
        )

    result = execute_rule(HealingRule(3, "Start", {}, "missing"), run=run)

    assert result.status == "FAILED"
    assert result.output == "file not found"
    assert result.exit_code == 1


def test_execute_rule_rejects_shell_error_even_with_zero_exit_code() -> None:
    def run(*_args, **_kwargs):
        return subprocess.CompletedProcess(
            args="start missing",
            returncode=0,
            stdout="",
            stderr="The system cannot find the file missing.exe.",
        )

    result = execute_rule(HealingRule(3, "Start", {}, "start missing"), run=run)

    assert result.status == "FAILED"
    assert result.exit_code == 0


def test_execute_rule_marks_timeout_shell_error_as_failed() -> None:
    def run(*_args, **kwargs):
        kwargs["stderr"].write("The system cannot find the path specified.")
        raise subprocess.TimeoutExpired("bad path", 5)

    result = execute_rule(HealingRule(3, "Start", {}, "bad path", 5), run=run)

    assert result.status == "FAILED"


def test_execute_rule_uses_file_backed_output_capture() -> None:
    def run(*_args, **kwargs):
        assert "capture_output" not in kwargs
        kwargs["stdout"].write("launched")
        kwargs["stderr"].write("startup warning")
        return subprocess.CompletedProcess(args="start app", returncode=0)

    result = execute_rule(HealingRule(3, "Start", {}, "start app"), run=run)

    assert result.status == "SUCCESS"
    assert result.output == "launched\nstartup warning"


def test_execute_rule_keeps_file_output_on_timeout() -> None:
    def run(*_args, **kwargs):
        kwargs["stdout"].write("still starting")
        raise subprocess.TimeoutExpired("slow command", 5)

    result = execute_rule(HealingRule(3, "Start", {}, "slow command", timeout_seconds=5), run=run)

    assert result.status == "TIMEOUT"
    assert result.output == "still starting"


def test_execute_rule_does_not_retry_after_early_recovery_cancellation() -> None:
    calls = []

    def run(*_args, **_kwargs):
        calls.append(object())
        raise subprocess.TimeoutExpired("slow command", 5)

    result = execute_rule(
        HealingRule(3, "Start", {}, "slow command", 5, 2, 1),
        run=run,
        cancelled=lambda: bool(calls),
    )

    assert result.status == "TIMEOUT"
    assert len(calls) == 1

