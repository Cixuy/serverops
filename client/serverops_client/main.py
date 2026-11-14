from __future__ import annotations

import sys

from PySide6.QtCore import QLibraryInfo, QTranslator
from PySide6.QtWidgets import QApplication

from .logging_config import configure_logging, get_logger
from .ui.main_window import MainWindow


def install_chinese_translations(app: QApplication) -> QTranslator:
    translator = QTranslator(app)
    translations_path = QLibraryInfo.path(QLibraryInfo.LibraryPath.TranslationsPath)
    translator.load("qtbase_zh_CN", translations_path)
    app.installTranslator(translator)
    return translator


def main() -> int:
    configure_logging()
    logger = get_logger("main")
    logger.info("ServerOps Agent 正在启动")
    app = QApplication(sys.argv)
    app.setApplicationName("ServerOps 监控客户端")
    app.setOrganizationName("ServerOps")
    app.setStyle("Fusion")
    translator = install_chinese_translations(app)
    window = MainWindow()
    window.show()
    exit_code = app.exec()
    app.removeTranslator(translator)
    logger.info("ServerOps Agent 已退出，exitCode=%s", exit_code)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
