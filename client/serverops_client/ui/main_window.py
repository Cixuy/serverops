from __future__ import annotations

from collections import deque
from datetime import datetime
from pathlib import Path
from typing import ClassVar

from PySide6.QtCore import QObject, QPointF, Qt, Signal
from PySide6.QtGui import (
    QCloseEvent,
    QColor,
    QKeySequence,
    QPainter,
    QPainterPath,
    QPen,
    QShortcut,
)
from PySide6.QtWidgets import (
    QAbstractItemView,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QSplitter,
    QStyle,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..config import ConfigStore
from ..models import HealingRule, MetricSnapshot, MonitorConfig, ServiceStatus
from ..service import AgentService, HealingResult, ServiceEvent
from .settings_dialog import SettingsDialog


class EventBridge(QObject):
    received = Signal(object)


class Sparkline(QWidget):
    def __init__(self, color: str, parent=None) -> None:  # type: ignore[no-untyped-def]
        super().__init__(parent)
        self.values: deque[float] = deque(maxlen=60)
        self.color = QColor(color)
        self.setFixedHeight(48)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

    def append(self, value: float) -> None:
        self.values.append(value)
        self.update()

    def paintEvent(self, event) -> None:  # type: ignore[no-untyped-def]
        super().paintEvent(event)
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(), QColor("#f7f9fb"))
        if len(self.values) < 2:
            return
        values = list(self.values)
        low, high = min(values), max(values)
        spread = max(high - low, 1.0)
        width = max(self.width() - 12, 1)
        height = max(self.height() - 12, 1)
        path = QPainterPath()
        for index, value in enumerate(values):
            x = 6 + width * index / (len(values) - 1)
            y = 6 + height * (1 - (value - low) / spread)
            point = QPointF(x, y)
            path.moveTo(point) if index == 0 else path.lineTo(point)
        painter.setPen(QPen(self.color, 2))
        painter.drawPath(path)


class MetricCard(QFrame):
    def __init__(self, title: str, color: str, progress: bool = False, parent=None) -> None:  # type: ignore[no-untyped-def]
        super().__init__(parent)
        self.setObjectName("metricCard")
        self.setMinimumHeight(158)
        self.title = QLabel(title)
        self.title.setObjectName("metricTitle")
        self.value = QLabel("--")
        self.value.setObjectName("metricValue")
        self.detail = QLabel("等待首次采样")
        self.detail.setObjectName("metricDetail")
        self.detail.setWordWrap(True)
        self.chart = Sparkline(color)
        self.progress = QProgressBar() if progress else None
        if self.progress:
            self.progress.setRange(0, 100)
            self.progress.setTextVisible(False)
            self.progress.setFixedHeight(5)
            self.progress.setStyleSheet(
                f"QProgressBar {{ border: 0; background: #e6eaee; }}"
                f"QProgressBar::chunk {{ background: {color}; }}"
            )

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 14, 16, 14)
        layout.setSpacing(5)
        layout.addWidget(self.title)
        layout.addWidget(self.value)
        if self.progress:
            layout.addWidget(self.progress)
        layout.addWidget(self.detail)
        layout.addStretch()
        layout.addWidget(self.chart)

    def update_value(self, value: str, detail: str, chart_value: float) -> None:
        self.value.setText(value)
        self.detail.setText(detail)
        self.chart.append(chart_value)
        if self.progress:
            self.progress.setValue(round(chart_value))


METRIC_LABELS = {
    "cpu_usage": ("CPU 使用率", "%"),
    "memory_usage": ("内存使用率", "%"),
    "disk_usage": ("磁盘使用率", "%"),
    "network_in_kbps": ("网络下载", " KB/s"),
    "network_out_kbps": ("网络上传", " KB/s"),
}
OPERATOR_LABELS = {
    "=": "=",
    "==": "=",
    "!=": "≠",
    ">": ">",
    ">=": "≥",
    "<": "<",
    "<=": "≤",
}


def condition_display(condition: dict) -> tuple[str, str, str]:  # type: ignore[type-arg]
    """Return a compact source, subject and expression for a trigger leaf."""
    condition_type = str(condition.get("type") or "").upper()
    operator_text = OPERATOR_LABELS.get(
        str(condition.get("operator") or "="), str(condition.get("operator") or "=")
    )
    if condition_type == "METRIC":
        metric = str(condition.get("metric") or "")
        subject, unit = METRIC_LABELS.get(metric, (metric or "未知指标", ""))
        return "指标", subject, f"{operator_text} {condition.get('value', '--')}{unit}"
    if condition_type == "SERVICE_STATUS":
        metric = str(condition.get("metric") or "")
        subject = {"process": "进程状态", "port": "端口状态"}.get(
            metric, metric or "服务状态"
        )
        values = (
            {0: "停止", 1: "运行"}
            if metric == "process"
            else {0: "不可达", 1: "可达"}
        )
        value = values.get(condition.get("value"), str(condition.get("value", "--")))
        return "状态", subject, f"{operator_text} {value}"
    if condition_type == "LOG_MATCH":
        path = str(condition.get("log_file_path") or "未指定日志")
        pattern = str(condition.get("regex_pattern") or "--")
        window = condition.get("match_window_seconds", 60)
        return "日志", Path(path).name or path, f"匹配 /{pattern}/ · {window} 秒"
    return "条件", "未知类型", "配置无法识别"


def service_status_display(
    config: MonitorConfig, status: ServiceStatus | None
) -> tuple[str, str]:
    """Return process and port status text for the service table."""
    if status is None:
        process = "未配置" if not config.process_name else "等待检查"
        port = "未配置" if config.port is None else "等待检查"
        return process, port
    process = "未配置"
    if config.process_name:
        process = f"存活 · PID {status.pid}" if status.is_alive else "未运行"
    port = "未配置"
    if config.port is not None:
        port = "可达" if status.port_status else "不可达"
    return process, port


class TriggerExpression(QWidget):
    def __init__(self, config: dict, parent=None) -> None:  # type: ignore[type-arg,no-untyped-def]
        super().__init__(parent)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(4)
        layout.addWidget(self._node(config, root=True))

    def _node(self, node: dict, root: bool = False) -> QWidget:  # type: ignore[type-arg]
        conditions = node.get("conditions")
        if isinstance(conditions, list):
            group = QFrame()
            group.setObjectName("triggerGroupRoot" if root else "triggerGroup")
            layout = QVBoxLayout(group)
            layout.setContentsMargins(10, 7, 6, 7)
            layout.setSpacing(5)
            logic = str(node.get("logic") or "AND").upper()
            title = QLabel("全部满足 · AND" if logic == "AND" else "任一满足 · OR")
            title.setObjectName("logicAnd" if logic == "AND" else "logicOr")
            layout.addWidget(title, 0, Qt.AlignmentFlag.AlignLeft)
            for condition in conditions:
                if isinstance(condition, dict):
                    layout.addWidget(self._node(condition))
            if not conditions:
                empty = QLabel("未配置触发条件")
                empty.setObjectName("mutedText")
                layout.addWidget(empty)
            return group

        source, subject, expression = condition_display(node)
        row = QFrame()
        row.setObjectName("conditionRow")
        layout = QHBoxLayout(row)
        layout.setContentsMargins(8, 6, 8, 6)
        layout.setSpacing(7)
        source_label = QLabel(source)
        source_label.setObjectName(f"condition{source}")
        source_label.setFixedWidth(36)
        subject_label = QLabel(subject)
        subject_label.setObjectName("conditionSubject")
        subject_label.setWordWrap(True)
        subject_label.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
        value_label = QLabel(expression)
        value_label.setObjectName("conditionValue")
        value_label.setWordWrap(True)
        value_label.setMaximumWidth(210)
        value_label.setToolTip(expression)
        value_label.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Preferred)
        value_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(source_label)
        layout.addWidget(subject_label, 1)
        layout.addWidget(value_label)
        return row


def healing_result_display(result: HealingResult) -> tuple[str, str, str]:
    labels = {
        "WAITING": "条件未满足",
        "CONFIG_ERROR": "规则无效",
        "RUNNING": "执行中",
        "SUCCESS": "执行成功",
        "FAILED": "执行失败",
        "TIMEOUT": "执行超时",
    }
    if result.status == "RUNNING":
        return labels[result.status], "命令正在运行", ""

    details = []
    if result.finished_at is not None:
        finished = datetime.fromtimestamp(result.finished_at / 1000).astimezone()
        details.append(finished.strftime("%m-%d %H:%M:%S"))
    if result.status == "WAITING":
        details.append("最近检查")
    elif result.status != "CONFIG_ERROR":
        details.append(f"耗时 {result.duration_ms} 毫秒")
    if result.retry_count:
        details.append(f"重试 {result.retry_count} 次")
    if result.status == "WAITING":
        output = "当前采集值尚未达到触发条件"
    elif result.status == "CONFIG_ERROR":
        output = result.output.strip() or "触发条件配置无效"
    else:
        output = result.output.strip() or "命令无输出"
    if len(output) > 360:
        output = f"...{output[-357:]}"
    return labels.get(result.status, "未知状态"), " · ".join(details), output


class HealingRuleCard(QFrame):
    def __init__(
        self,
        rule: HealingRule,
        service_name: str,
        result: HealingResult | None = None,
        parent=None,  # type: ignore[no-untyped-def]
    ) -> None:
        super().__init__(parent)
        self.setObjectName("healingRuleCard")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 11, 12, 12)
        layout.setSpacing(7)

        header = QHBoxLayout()
        header.setSpacing(7)
        dot = QFrame()
        dot.setObjectName("activeRuleDot")
        dot.setFixedSize(8, 8)
        name = QLabel(rule.rule_name)
        name.setObjectName("ruleName")
        name.setWordWrap(True)
        rule_id = QLabel(f"ID {rule.rule_id}")
        rule_id.setObjectName("ruleId")
        header.addWidget(dot)
        header.addWidget(name, 1)
        header.addWidget(rule_id)
        layout.addLayout(header)

        service = QLabel(service_name)
        service.setObjectName("ruleService")
        layout.addWidget(service)
        layout.addWidget(TriggerExpression(rule.trigger_config))

        command = QLabel(rule.execute_command or "未配置执行命令")
        command.setObjectName("ruleCommand")
        command.setWordWrap(True)
        command.setToolTip(rule.execute_command)
        layout.addWidget(command)
        policy = QLabel(
            f"超时 {rule.timeout_seconds} 秒  ·  最多重试 {rule.max_retries} 次"
            f"  ·  间隔 {rule.retry_interval} 秒"
        )
        policy.setObjectName("rulePolicy")
        layout.addWidget(policy)

        result_box = QFrame()
        result_box.setObjectName("executionResult")
        result_layout = QVBoxLayout(result_box)
        result_layout.setContentsMargins(8, 7, 8, 7)
        result_layout.setSpacing(4)
        result_header = QHBoxLayout()
        result_title = QLabel("最近执行")
        result_title.setObjectName("resultTitle")
        result_header.addWidget(result_title)
        result_header.addStretch()
        if result is None:
            result_status = QLabel("尚未执行")
            result_status.setObjectName("resultIDLE")
            result_header.addWidget(result_status)
        else:
            status_text, detail_text, output_text = healing_result_display(result)
            result_status = QLabel(status_text)
            result_status.setObjectName(f"result{result.status}")
            result_header.addWidget(result_status)
            result_layout.addLayout(result_header)
            detail = QLabel(detail_text)
            detail.setObjectName("resultDetail")
            result_layout.addWidget(detail)
            if output_text:
                output = QLabel(output_text)
                output.setObjectName("resultOutput")
                output.setWordWrap(True)
                output.setToolTip(result.output[-4000:])
                result_layout.addWidget(output)
            layout.addWidget(result_box)
            return
        result_layout.addLayout(result_header)
        layout.addWidget(result_box)


class MainWindow(QMainWindow):
    STATUS_COLORS: ClassVar[dict[str, str]] = {
        "connected": "#16825d",
        "reported": "#16825d",
        "starting": "#b36b00",
        "offline": "#c23b3b",
        "error": "#c23b3b",
        "paused": "#6b7280",
        "stopped": "#6b7280",
    }

    def __init__(self, config_store: ConfigStore | None = None) -> None:
        super().__init__()
        self.config_store = config_store or ConfigStore()
        self.config = self.config_store.load()
        self.service: AgentService | None = None
        self.monitor_configs: dict[int, MonitorConfig] = {}
        self.service_statuses: dict[int, ServiceStatus] = {}
        self.healing_rules: dict[int, list[HealingRule]] = {}
        self.healing_results: dict[tuple[int, int], HealingResult] = {}
        self.healing_rule_sources: set[int] = set()
        self.bridge = EventBridge()
        self.bridge.received.connect(self._handle_event)
        self.setWindowTitle("ServerOps 监控客户端")
        self.resize(1180, 800)
        self.setMinimumSize(820, 760)
        self._build_ui()
        self._apply_style()

        try:
            self.config.validate()
        except ValueError:
            self._set_status("stopped", "等待配置")
            self.connection_detail.setText("请先配置服务器地址和 Agent Token")
        else:
            self._start_service()

    def _build_ui(self) -> None:
        root = QWidget()
        self.setCentralWidget(root)
        page = QVBoxLayout(root)
        page.setContentsMargins(24, 20, 24, 20)
        page.setSpacing(16)

        title = QLabel("ServerOps 监控客户端")
        title.setObjectName("appTitle")
        subtitle = QLabel("本机资源监控客户端")
        subtitle.setObjectName("subtitle")
        title_column = QVBoxLayout()
        title_column.setSpacing(2)
        title_column.addWidget(title)
        title_column.addWidget(subtitle)

        self.status_dot = QFrame()
        self.status_dot.setFixedSize(10, 10)
        self.status_text = QLabel("未启动")
        self.status_text.setObjectName("statusText")
        status_row = QHBoxLayout()
        status_row.setSpacing(8)
        status_row.addWidget(self.status_dot)
        status_row.addWidget(self.status_text)

        self.toggle_button = QPushButton("启动")
        self.toggle_button.clicked.connect(self._toggle_service)
        report_button = QPushButton("立即上报")
        report_button.setObjectName("secondaryButton")
        report_button.clicked.connect(self._report_now)
        self.refresh_button = QPushButton("刷新")
        self.refresh_button.setObjectName("secondaryButton")
        self.refresh_button.setIcon(
            self.style().standardIcon(QStyle.StandardPixmap.SP_BrowserReload)
        )
        self.refresh_button.setToolTip("重新拉取服务配置与自愈规则 (F5)")
        self.refresh_button.clicked.connect(self._refresh_configs)
        self.refresh_shortcut = QShortcut(QKeySequence.Refresh, self)
        self.refresh_shortcut.activated.connect(self._refresh_configs)
        settings_button = QPushButton("设置")
        settings_button.setObjectName("secondaryButton")
        settings_button.clicked.connect(self._open_settings)

        header = QHBoxLayout()
        header.addLayout(title_column)
        header.addStretch()
        header.addLayout(status_row)
        header.addSpacing(12)
        header.addWidget(self.toggle_button)
        header.addWidget(report_button)
        header.addWidget(self.refresh_button)
        header.addWidget(settings_button)
        page.addLayout(header)

        self.connection_detail = QLabel("尚未连接")
        self.connection_detail.setObjectName("connectionDetail")
        page.addWidget(self.connection_detail)

        self.cpu_card = MetricCard("CPU", "#2878b5", progress=True)
        self.memory_card = MetricCard("内存", "#16825d", progress=True)
        self.network_card = MetricCard("网络吞吐", "#b36b00")
        self.disk_io_card = MetricCard("磁盘读写", "#b3436c")
        cards = QGridLayout()
        cards.setHorizontalSpacing(12)
        cards.setVerticalSpacing(12)
        cards.addWidget(self.cpu_card, 0, 0)
        cards.addWidget(self.memory_card, 0, 1)
        cards.addWidget(self.network_card, 0, 2)
        cards.addWidget(self.disk_io_card, 0, 3)
        cards.setColumnStretch(0, 1)
        cards.setColumnStretch(1, 1)
        cards.setColumnStretch(2, 1)
        cards.setColumnStretch(3, 1)
        page.addLayout(cards)

        config_header = QHBoxLayout()
        config_title = QLabel("服务配置与自愈")
        config_title.setObjectName("sectionTitle")
        self.config_status = QLabel("等待服务端下发")
        self.config_status.setObjectName("configStatus")
        config_header.addWidget(config_title)
        config_header.addStretch()
        config_header.addWidget(self.config_status)
        page.addLayout(config_header)

        self.config_table = QTableWidget(0, 7)
        self.config_table.setObjectName("configTable")
        self.config_table.setHorizontalHeaderLabels(
            ["服务", "进程", "进程状态", "监听端口", "端口状态", "检查周期", "配置 ID"]
        )
        self.config_table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.config_table.setSelectionMode(QAbstractItemView.SelectionMode.NoSelection)
        self.config_table.verticalHeader().setVisible(False)
        self.config_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.config_table.setMinimumHeight(118)
        service_panel = QWidget()
        service_layout = QVBoxLayout(service_panel)
        service_layout.setContentsMargins(0, 0, 0, 0)
        service_layout.setSpacing(7)
        service_label = QLabel("监控服务")
        service_label.setObjectName("panelLabel")
        service_layout.addWidget(service_label)
        service_layout.addWidget(self.config_table)

        healing_panel = QWidget()
        healing_layout = QVBoxLayout(healing_panel)
        healing_layout.setContentsMargins(0, 0, 0, 0)
        healing_layout.setSpacing(7)
        healing_header = QHBoxLayout()
        healing_label = QLabel("激活的自愈规则")
        healing_label.setObjectName("panelLabel")
        self.healing_status = QLabel("等待规则检测")
        self.healing_status.setObjectName("healingStatus")
        healing_header.addWidget(healing_label)
        healing_header.addStretch()
        healing_header.addWidget(self.healing_status)
        healing_layout.addLayout(healing_header)

        self.healing_scroll = QScrollArea()
        self.healing_scroll.setObjectName("healingScroll")
        self.healing_scroll.setWidgetResizable(True)
        self.healing_scroll.setHorizontalScrollBarPolicy(
            Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        )
        self.healing_rules_content = QWidget()
        self.healing_rules_content.setObjectName("healingRulesContent")
        self.healing_rules_layout = QVBoxLayout(self.healing_rules_content)
        self.healing_rules_layout.setContentsMargins(10, 10, 10, 10)
        self.healing_rules_layout.setSpacing(8)
        self.healing_rules_layout.addStretch()
        self.healing_scroll.setWidget(self.healing_rules_content)
        self.healing_scroll.setMinimumHeight(170)
        healing_layout.addWidget(self.healing_scroll)

        service_splitter = QSplitter(Qt.Orientation.Horizontal)
        service_splitter.setObjectName("serviceSplitter")
        service_splitter.addWidget(service_panel)
        service_splitter.addWidget(healing_panel)
        service_splitter.setStretchFactor(0, 3)
        service_splitter.setStretchFactor(1, 2)
        service_splitter.setSizes([650, 430])
        service_splitter.setMinimumHeight(190)
        page.addWidget(service_splitter)

        disks_title = QLabel("磁盘占用")
        disks_title.setObjectName("sectionTitle")
        page.addWidget(disks_title)
        self.disk_table = QTableWidget(0, 5)
        self.disk_table.setHorizontalHeaderLabels(
            ["挂载点", "文件系统", "已用 / 总量", "使用率", "读 / 写"]
        )
        self.disk_table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.disk_table.setSelectionMode(QAbstractItemView.SelectionMode.NoSelection)
        self.disk_table.verticalHeader().setVisible(False)
        self.disk_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.disk_table.setMinimumHeight(180)
        page.addWidget(self.disk_table, 1)

        self.server_code_label = QLabel("服务器编码：--")
        self.last_report_label = QLabel("最后上报: --")
        self.queue_label = QLabel("待上报: 0")
        footer = QHBoxLayout()
        footer.addWidget(self.server_code_label)
        footer.addStretch()
        footer.addWidget(self.queue_label)
        footer.addSpacing(20)
        footer.addWidget(self.last_report_label)
        page.addLayout(footer)
        self._render_healing_rules()

    def _apply_style(self) -> None:
        self.setStyleSheet(
            """
            QMainWindow, QWidget { background: #f3f5f7; color: #18212b; font-size: 13px;
                                  font-family: "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI"; }
            QLabel#appTitle { font-size: 23px; font-weight: 700; }
            QLabel#subtitle, QLabel#connectionDetail, QLabel#metricDetail { color: #66717d; }
            QLabel#connectionDetail { padding: 8px 12px; background: #e9edf1; border-radius: 4px; }
            QLabel#configStatus { color: #6750a4; background: #f3edf7; padding: 5px 10px; border-radius: 12px; }
            QLabel#healingStatus { color: #42624f; background: #eaf4ee; padding: 3px 8px;
                                   border-radius: 9px; font-size: 11px; }
            QLabel#statusText { font-weight: 600; }
            QLabel#metricTitle { color: #596572; font-weight: 600; }
            QLabel#metricValue { font-size: 24px; font-weight: 700; }
            QLabel#sectionTitle { font-size: 16px; font-weight: 700; margin-top: 2px; }
            QLabel#panelLabel { color: #44515d; font-weight: 700; }
            QFrame#metricCard { background: white; border: 1px solid #dfe4e8; border-radius: 6px; }
            QPushButton { background: #263746; color: white; border: 0; border-radius: 4px;
                          min-height: 34px; padding: 0 14px; font-weight: 600; }
            QPushButton:hover { background: #344a5c; }
            QPushButton#secondaryButton { background: white; color: #263746; border: 1px solid #cfd6dc; }
            QPushButton#secondaryButton:hover { background: #edf1f4; }
            QTableWidget { background: white; border: 1px solid #dfe4e8; border-radius: 4px;
                           gridline-color: #edf0f2; }
            QTableWidget#configTable { background: #fffbfe; border: 1px solid #e6e0e9; border-radius: 12px; }
            QTableWidget#configTable QHeaderView::section { background: #f3edf7; color: #49454f; }
            QSplitter#serviceSplitter::handle { background: #dfe4e8; width: 1px; margin: 0 8px; }
            QScrollArea#healingScroll { background: #f8faf9; border: 1px solid #dce6df;
                                       border-radius: 5px; }
            QWidget#healingRulesContent { background: #f8faf9; }
            QFrame#healingRuleCard { background: white; border: 1px solid #d9e3dc;
                                     border-radius: 5px; }
            QFrame#activeRuleDot { background: #23845a; border: 0; border-radius: 4px; }
            QLabel#ruleName { font-weight: 700; color: #1d2a22; }
            QLabel#ruleId, QLabel#ruleService, QLabel#rulePolicy, QLabel#mutedText {
                color: #728078; font-size: 11px;
            }
            QLabel#ruleCommand { color: #34453b; background: #f2f5f3; border: 0;
                                 padding: 5px 7px; font-family: Consolas, monospace; }
            QFrame#executionResult { background: #f7f9f8; border: 0;
                                     border-top: 1px solid #e2e8e4; }
            QLabel#resultTitle { color: #526158; font-size: 11px; font-weight: 700; }
            QLabel#resultIDLE, QLabel#resultRUNNING, QLabel#resultSUCCESS,
            QLabel#resultFAILED, QLabel#resultTIMEOUT, QLabel#resultWAITING,
            QLabel#resultCONFIG_ERROR {
                border: 0; padding: 2px 6px; font-size: 11px; font-weight: 700;
            }
            QLabel#resultIDLE { color: #6b7280; background: #eceff1; }
            QLabel#resultRUNNING { color: #805b10; background: #fff1c7; }
            QLabel#resultSUCCESS { color: #176644; background: #dff3e8; }
            QLabel#resultWAITING { color: #526158; background: #e8eeea; }
            QLabel#resultFAILED, QLabel#resultTIMEOUT, QLabel#resultCONFIG_ERROR {
                color: #9b3030; background: #fbe4e4;
            }
            QLabel#resultDetail { color: #728078; font-size: 11px; }
            QLabel#resultOutput { color: #34453b; background: transparent; border: 0;
                                  font-family: Consolas, monospace; font-size: 11px; }
            QFrame#triggerGroupRoot { background: #fbfcfb; border-left: 3px solid #4b8465; }
            QFrame#triggerGroup { background: transparent; border-left: 2px solid #b9c9bf; }
            QLabel#logicAnd { color: #35664c; font-weight: 700; font-size: 11px; }
            QLabel#logicOr { color: #986018; font-weight: 700; font-size: 11px; }
            QFrame#conditionRow { background: #f4f7f5; border: 0; border-bottom: 1px solid #e1e8e3; }
            QLabel#conditionSubject { color: #29382f; font-weight: 600; }
            QLabel#conditionValue { color: #526158; }
            QLabel#condition指标 { color: #256d85; font-size: 11px; font-weight: 700; }
            QLabel#condition状态 { color: #367150; font-size: 11px; font-weight: 700; }
            QLabel#condition日志 { color: #986018; font-size: 11px; font-weight: 700; }
            QLabel#condition条件 { color: #a13d3d; font-size: 11px; font-weight: 700; }
            QLabel#healingEmpty { color: #7a8780; padding: 30px; }
            QHeaderView::section { background: #edf1f4; color: #44515d; border: 0;
                                   border-bottom: 1px solid #d8dee3; padding: 8px; font-weight: 600; }
            QTableWidget::item { padding: 7px; }
            QLineEdit, QDoubleSpinBox, QSpinBox { background: white; border: 1px solid #cbd3d9;
                                                 border-radius: 4px; padding: 7px; min-height: 22px; }
            """
        )

    def _start_service(self) -> None:
        if self.service:
            self.service.stop()
        try:
            self.config.validate()
        except ValueError as exc:
            show_warning(self, "配置无效", str(exc))
            self.service = None
            self.toggle_button.setText("启动")
            self._set_status("stopped", "客户端配置无效")
            return
        try:
            self.config.resolve_server_address()
        except ValueError as exc:
            show_warning(self, "地址无效", str(exc))
            self.service = None
            self.toggle_button.setText("启动")
            self._set_status("stopped", "服务器地址无效")
            return
        self.service = AgentService(
            self.config, self.config_store, lambda event: self.bridge.received.emit(event)
        )
        try:
            self.service.start()
        except ValueError as exc:
            show_warning(self, "配置无效", str(exc))
            self.service = None
            return
        self.toggle_button.setText("暂停")
        self._set_status("starting", "正在启动")

    def _toggle_service(self) -> None:
        if not self.service:
            try:
                self.config.validate()
            except ValueError:
                self._open_settings()
                return
            self._start_service()
        elif self.service.paused:
            self.service.set_paused(False)
            self.toggle_button.setText("暂停")
        else:
            self.service.set_paused(True)
            self.toggle_button.setText("继续")

    def _report_now(self) -> None:
        if self.service and not self.service.paused:
            self.service.report_now()
        else:
            self.connection_detail.setText("请先启动监控")

    def _refresh_configs(self) -> None:
        if self.service and not self.service.paused:
            self.refresh_button.setEnabled(False)
            self.config_status.setText("正在刷新...")
            self.healing_status.setText("正在重新检查")
            self.service.refresh_configs()
        else:
            self.connection_detail.setText("请先启动监控后再刷新配置")

    def _open_settings(self) -> None:
        dialog = SettingsDialog(self.config, self)
        if dialog.exec() != SettingsDialog.DialogCode.Accepted:
            return
        new_config = dialog.config(self.config)
        try:
            new_config.validate()
        except ValueError as exc:
            show_warning(self, "配置无效", str(exc))
            return
        try:
            new_config.resolve_server_address()
        except ValueError as exc:
            show_warning(self, "地址无效", str(exc))
            return
        try:
            self.config_store.save(new_config)
        except OSError as exc:
            show_warning(self, "无法保存设置", str(exc))
            return
        self.config = new_config
        self._start_service()

    def _handle_event(self, event: ServiceEvent) -> None:
        if event.kind == "snapshot" and event.snapshot:
            self._update_metrics(event.snapshot)
        elif event.kind in {"config_loaded", "config_refreshed"}:
            if event.kind == "config_refreshed":
                self.healing_rules.clear()
                self.healing_rule_sources.clear()
            self._update_monitor_configs(event.monitor_configs or [])
            self.config_status.setToolTip("")
            self.refresh_button.setEnabled(True)
        elif event.kind == "config_error":
            self.config_status.setText("刷新失败")
            self.config_status.setToolTip(event.message)
            self.refresh_button.setEnabled(True)
        elif event.kind == "service_checked" and event.service_statuses is not None:
            self.service_statuses.update(
                {status.config_id: status for status in event.service_statuses}
            )
            self._render_monitor_configs()
        elif event.kind == "healing_rules_loaded" and event.healing_rules is not None:
            self._update_healing_rules(event.healing_rules)
        elif event.kind in {
            "healing_evaluated",
            "healing_started",
            "healing_executed",
        } and event.healing_result:
            result = event.healing_result
            key = (result.config_id, result.rule_id)
            previous = self.healing_results.get(key)
            if result.status != "WAITING" or previous is None or previous.status == "WAITING":
                self.healing_results[key] = result
            self._render_healing_rules()
        elif event.kind == "healing_error":
            self.healing_status.setText("检测有异常")
            self.healing_status.setToolTip(event.message)
        elif event.kind == "reported":
            self.last_report_label.setText(
                f"最后上报: {datetime.now().astimezone().strftime('%H:%M:%S')}"
            )
        if event.kind in self.STATUS_COLORS:
            self._set_status(event.kind, event.message)
        if event.server_code:
            self.server_code_label.setText(f"服务器编码：{event.server_code}")
        self.queue_label.setText(f"待上报: {event.queue_size}")

    def _update_monitor_configs(self, configs: list[MonitorConfig]) -> None:
        previous_configs = self.monitor_configs
        self.monitor_configs = {config.config_id: config for config in configs}
        config_ids = set(self.monitor_configs)
        self.service_statuses = {
            config_id: status
            for config_id, status in self.service_statuses.items()
            if previous_configs.get(config_id) == self.monitor_configs.get(config_id)
        }
        self.healing_rules = {
            config_id: rules
            for config_id, rules in self.healing_rules.items()
            if config_id in config_ids
        }
        self.healing_results = {
            key: result
            for key, result in self.healing_results.items()
            if key[0] in config_ids
        }
        self.healing_rule_sources.intersection_update(config_ids)
        self._render_monitor_configs()
        self.config_status.setText(f"已生效 · {len(configs)} 项")
        if not configs:
            self.config_status.setText("暂无已启用服务")
            self._render_healing_rules()
            return
        self._render_healing_rules()

    def _render_monitor_configs(self) -> None:
        configs = list(self.monitor_configs.values())
        self.config_table.setRowCount(len(configs))
        for row, config in enumerate(configs):
            process_status, port_status = service_status_display(
                config, self.service_statuses.get(config.config_id)
            )
            values = [
                config.service_name,
                config.process_name or "未指定",
                process_status,
                str(config.port) if config.port is not None else "未监听端口",
                port_status,
                f"{config.check_interval} 秒" if config.check_interval is not None else "默认",
                str(config.config_id),
            ]
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setTextAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
                if column in {2, 4}:
                    if value.startswith("存活") or value == "可达":
                        item.setForeground(QColor("#16825d"))
                    elif value in {"未运行", "不可达"}:
                        item.setForeground(QColor("#c23b3b"))
                    status = self.service_statuses.get(config.config_id)
                    if status is not None:
                        checked_at = datetime.fromtimestamp(
                            status.last_check_time / 1000
                        ).astimezone()
                        tooltip = f"检查时间: {checked_at.strftime('%Y-%m-%d %H:%M:%S')}"
                        if status.alert_context:
                            tooltip += f"\n{status.alert_context}"
                        item.setToolTip(tooltip)
                self.config_table.setItem(row, column, item)

    def _update_healing_rules(self, rules_by_config: dict[int, list[HealingRule]]) -> None:
        for config_id, rules in rules_by_config.items():
            self.healing_rules[config_id] = [rule for rule in rules if rule.status == 1]
            rule_ids = {rule.rule_id for rule in self.healing_rules[config_id]}
            self.healing_results = {
                key: result
                for key, result in self.healing_results.items()
                if key[0] != config_id or key[1] in rule_ids
            }
            self.healing_rule_sources.add(config_id)
        self.healing_status.setToolTip("")
        self._render_healing_rules()

    def _render_healing_rules(self) -> None:
        while self.healing_rules_layout.count():
            item = self.healing_rules_layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()

        active = [
            (config_id, rule)
            for config_id, rules in self.healing_rules.items()
            if config_id in self.monitor_configs
            for rule in rules
        ]
        if active:
            service_count = len({config_id for config_id, _rule in active})
            self.healing_status.setText(f"{len(active)} 条 · {service_count} 项服务")
            for config_id, rule in active:
                config = self.monitor_configs[config_id]
                self.healing_rules_layout.addWidget(
                    HealingRuleCard(
                        rule,
                        config.service_name,
                        self.healing_results.get((config_id, rule.rule_id)),
                    )
                )
        else:
            empty = QLabel(
                "正在同步规则"
                if self.monitor_configs
                and len(self.healing_rule_sources) < len(self.monitor_configs)
                else "暂无激活规则"
            )
            empty.setObjectName("healingEmpty")
            empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.healing_rules_layout.addWidget(empty, 1)

        if self.monitor_configs and len(self.healing_rule_sources) < len(self.monitor_configs):
            self.healing_status.setText(
                f"同步 {len(self.healing_rule_sources)}/{len(self.monitor_configs)}"
            )
        elif not active:
            self.healing_status.setText("0 条")
        self.healing_rules_layout.addStretch()

    def _set_status(self, kind: str, message: str) -> None:
        labels = {
            "connected": "在线",
            "reported": "在线",
            "starting": "连接中",
            "offline": "离线",
            "error": "异常",
            "paused": "已暂停",
            "stopped": "未启动",
        }
        color = self.STATUS_COLORS.get(kind, "#6b7280")
        self.status_dot.setStyleSheet(f"background: {color}; border-radius: 5px;")
        self.status_text.setText(labels.get(kind, "未知状态"))
        if message:
            self.connection_detail.setText(message)

    def _update_metrics(self, snapshot: MetricSnapshot) -> None:
        self.cpu_card.update_value(
            f"{snapshot.cpu_usage:.1f}%", f"{snapshot.cpu_count} 个逻辑核心", snapshot.cpu_usage
        )
        self.memory_card.update_value(
            f"{snapshot.memory_usage:.1f}%",
            f"{snapshot.memory_used_mb / 1024:.1f} / {snapshot.memory_total_mb / 1024:.1f} GB",
            snapshot.memory_usage,
        )
        self.network_card.update_value(
            f"↓ {self._rate(snapshot.network_in_kbps)}",
            f"上传 ↑ {self._rate(snapshot.network_out_kbps)}",
            snapshot.network_in_kbps + snapshot.network_out_kbps,
        )
        self.disk_io_card.update_value(
            f"读 {self._rate(snapshot.disk_read_kbps_total)}",
            f"写 {self._rate(snapshot.disk_write_kbps_total)}",
            snapshot.disk_read_kbps_total + snapshot.disk_write_kbps_total,
        )
        self.disk_table.setRowCount(len(snapshot.disks))
        for row, disk in enumerate(snapshot.disks):
            values = [
                disk.mount_point,
                disk.fs_type,
                f"{disk.disk_used_gb:.1f} / {disk.disk_total_gb:.1f} GB",
                f"{disk.disk_usage:.1f}%",
                f"{self._rate(disk.disk_read_kbps)} / {self._rate(disk.disk_write_kbps)}",
            ]
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setTextAlignment(
                    Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter
                )
                self.disk_table.setItem(row, column, item)

    @staticmethod
    def _rate(kilobytes: float) -> str:
        if kilobytes >= 1024:
            return f"{kilobytes / 1024:.1f} MB/s"
        return f"{kilobytes:.1f} KB/s"

    def closeEvent(self, event: QCloseEvent) -> None:
        if self.service:
            self.service.stop()
        event.accept()


def create_warning_dialog(parent: QWidget, title: str, message: str) -> QMessageBox:
    dialog = QMessageBox(
        QMessageBox.Icon.Warning,
        title,
        message,
        QMessageBox.StandardButton.Ok,
        parent,
    )
    dialog.button(QMessageBox.StandardButton.Ok).setText("确定")
    return dialog


def show_warning(parent: QWidget, title: str, message: str) -> None:
    create_warning_dialog(parent, title, message).exec()
