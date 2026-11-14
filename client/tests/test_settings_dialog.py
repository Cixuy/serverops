import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication, QDialogButtonBox

from serverops_client.config import AgentConfig
from serverops_client.ui.settings_dialog import SettingsDialog


def test_settings_dialog_uses_chinese_button_labels() -> None:
    app = QApplication.instance() or QApplication([])
    dialog = SettingsDialog(AgentConfig(agent_token="x"))

    assert dialog.buttons.button(QDialogButtonBox.StandardButton.Save).text() == "保存"
    assert dialog.buttons.button(QDialogButtonBox.StandardButton.Cancel).text() == "取消"
    dialog.close()
    assert app is not None
