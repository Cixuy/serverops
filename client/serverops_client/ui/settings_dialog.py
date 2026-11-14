from __future__ import annotations

from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFormLayout,
    QLineEdit,
    QSpinBox,
    QVBoxLayout,
)

from ..config import AgentConfig


class SettingsDialog(QDialog):
    def __init__(self, config: AgentConfig, parent=None) -> None:  # type: ignore[no-untyped-def]
        super().__init__(parent)
        self.setWindowTitle("连接设置")
        self.setMinimumWidth(440)

        self.server_url = QLineEdit(config.server_url)
        self.server_url.setPlaceholderText("http://127.0.0.1:8080")
        self.agent_token = QLineEdit(config.agent_token)
        self.agent_token.setEchoMode(QLineEdit.EchoMode.Password)
        self.agent_token.setPlaceholderText("X-Agent-Token")

        self.sample_interval = QDoubleSpinBox()
        self.sample_interval.setRange(1, 300)
        self.sample_interval.setSuffix(" 秒")
        self.sample_interval.setValue(config.sample_interval)

        self.heartbeat_interval = QDoubleSpinBox()
        self.heartbeat_interval.setRange(5, 600)
        self.heartbeat_interval.setSuffix(" 秒")
        self.heartbeat_interval.setValue(config.heartbeat_interval)

        self.alert_cooldown = QSpinBox()
        self.alert_cooldown.setRange(1, 1440)
        self.alert_cooldown.setSuffix(" 分钟")
        self.alert_cooldown.setValue(config.alert_cooldown_minutes)

        self.healing_timeout_recheck_delay = QDoubleSpinBox()
        self.healing_timeout_recheck_delay.setRange(0, 300)
        self.healing_timeout_recheck_delay.setDecimals(1)
        self.healing_timeout_recheck_delay.setSuffix(" 秒")
        self.healing_timeout_recheck_delay.setValue(
            config.healing_timeout_recheck_delay
        )

        form = QFormLayout()
        form.setSpacing(12)
        form.addRow("服务器地址", self.server_url)
        form.addRow("Agent Token", self.agent_token)
        form.addRow("指标采样周期", self.sample_interval)
        form.addRow("心跳周期", self.heartbeat_interval)
        form.addRow("告警冷却", self.alert_cooldown)
        form.addRow("自愈超时复检延时", self.healing_timeout_recheck_delay)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.button(QDialogButtonBox.StandardButton.Save).setText("保存")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("取消")
        self.buttons = buttons
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        layout = QVBoxLayout(self)
        layout.addLayout(form)
        layout.addSpacing(8)
        layout.addWidget(buttons)

    def config(self, original: AgentConfig) -> AgentConfig:
        return AgentConfig(
            server_url=self.server_url.text().strip().rstrip("/"),
            agent_token=self.agent_token.text().strip(),
            sample_interval=self.sample_interval.value(),
            heartbeat_interval=self.heartbeat_interval.value(),
            request_timeout=original.request_timeout,
            queue_limit=original.queue_limit,
            alert_cooldown_minutes=self.alert_cooldown.value(),
            healing_timeout_recheck_delay=self.healing_timeout_recheck_delay.value(),
        )
