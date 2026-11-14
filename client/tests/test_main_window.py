from serverops_client.models import MonitorConfig, ServiceStatus
from serverops_client.service import HealingResult
from serverops_client.ui.main_window import (
    condition_display,
    create_warning_dialog,
    healing_result_display,
    service_status_display,
)


def test_condition_display_formats_metrics_with_units() -> None:
    assert condition_display(
        {
            "type": "METRIC",
            "metric": "memory_usage",
            "operator": ">=",
            "value": "80",
        }
    ) == ("指标", "内存使用率", "≥ 80%")


def test_condition_display_translates_service_state() -> None:
    assert condition_display(
        {
            "type": "SERVICE_STATUS",
            "metric": "process",
            "operator": "=",
            "value": 0,
        }
    ) == ("状态", "进程状态", "= 停止")


def test_condition_display_summarizes_log_match() -> None:
    source, subject, expression = condition_display(
        {
            "type": "LOG_MATCH",
            "log_file_path": "/var/log/app/error.log",
            "regex_pattern": "OOM|OutOfMemory",
            "match_window_seconds": 120,
        }
    )

    assert source == "日志"
    assert subject == "error.log"
    assert expression == "匹配 /OOM|OutOfMemory/ · 120 秒"


def test_healing_result_display_formats_execution_feedback() -> None:
    status, detail, output = healing_result_display(
        HealingResult(7, 9, "TIMEOUT", "service did not stop", 2, 5_200, 1_750_000_000_000)
    )

    assert status == "执行超时"
    assert "耗时 5200 毫秒" in detail
    assert "重试 2 次" in detail
    assert output == "service did not stop"


def test_healing_result_display_limits_long_output() -> None:
    _status, _detail, output = healing_result_display(
        HealingResult(7, 9, "FAILED", "x" * 500)
    )

    assert len(output) == 360
    assert output.startswith("...")


def test_healing_result_display_explains_unmatched_condition() -> None:
    status, detail, output = healing_result_display(
        HealingResult(7, 9, "WAITING", finished_at=1_750_000_000_000)
    )

    assert status == "条件未满足"
    assert "最近检查" in detail
    assert output == "当前采集值尚未达到触发条件"


def test_service_status_display_distinguishes_live_and_reachable() -> None:
    config = MonitorConfig(7, "Nginx", "nginx", 8080)
    status = ServiceStatus(7, "Nginx", 1, 42, 0, 123)

    assert service_status_display(config, status) == ("存活 · PID 42", "不可达")


def test_service_status_display_handles_pending_and_unconfigured_checks() -> None:
    assert service_status_display(MonitorConfig(7, "Worker", "worker"), None) == (
        "等待检查",
        "未配置",
    )


def test_warning_dialog_uses_chinese_confirm_button() -> None:
    from PySide6.QtWidgets import QApplication, QMessageBox

    app = QApplication.instance() or QApplication([])
    dialog = create_warning_dialog(None, "提示", "内容")

    assert dialog.button(QMessageBox.StandardButton.Ok).text() == "确定"
    dialog.close()
    assert app is not None
