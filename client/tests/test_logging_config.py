import io
import logging

from serverops_client.logging_config import configure_logging, get_logger
from serverops_client.service import AgentService, HealingResult, ServiceEvent


def test_console_logging_formats_levels_with_colors() -> None:
    stream = io.StringIO()
    configure_logging(stream=stream, use_color=True)
    logger = get_logger("test")

    logger.info("connected")
    logger.warning("retrying")
    logger.error("failed")

    output = stream.getvalue()
    assert "\033[36m[INFO]\033[0m" in output
    assert "\033[33m[WARN]\033[0m" in output
    assert "\033[31m[ERROR]\033[0m" in output


def test_console_logging_can_disable_colors() -> None:
    stream = io.StringIO()
    configure_logging(stream=stream, use_color=False)

    get_logger("test").warning("plain")

    assert "[WARN]" in stream.getvalue()
    assert "\033[" not in stream.getvalue()


def test_console_log_level_can_be_configured_by_environment(monkeypatch) -> None:
    stream = io.StringIO()
    monkeypatch.setenv("SERVEROPS_LOG_LEVEL", "DEBUG")
    configure_logging(stream=stream, use_color=False)

    get_logger("test").debug("request details")

    assert "[DEBUG]" in stream.getvalue()
    assert "request details" in stream.getvalue()


def test_service_error_log_contains_healing_context() -> None:
    stream = io.StringIO()
    configure_logging(level=logging.DEBUG, stream=stream, use_color=False)
    event = ServiceEvent(
        kind="healing_error",
        message="自愈命令执行失败",
        server_code="SRV-1",
        healing_result=HealingResult(7, 9, "FAILED", duration_ms=250),
    )

    AgentService._log_event(event)

    output = stream.getvalue()
    assert "[ERROR]" in output
    assert "serverCode=SRV-1" in output
    assert "configId=7" in output
    assert "ruleId=9" in output
    assert "status=FAILED" in output
    assert "durationMs=250" in output


def test_service_execution_end_log_contains_command_output() -> None:
    stream = io.StringIO()
    configure_logging(level=logging.INFO, stream=stream, use_color=False)
    event = ServiceEvent(
        kind="healing_executed",
        message="自愈规则 Restart 执行结束：成功",
        server_code="SRV-1",
        healing_result=HealingResult(
            7,
            9,
            "SUCCESS",
            "service restarted\nhealth check passed",
            duration_ms=250,
        ),
    )

    AgentService._log_event(event)

    output = stream.getvalue()
    assert "执行结束：成功" in output
    assert "命令执行输出:" in output
    assert "service restarted\nhealth check passed" in output
