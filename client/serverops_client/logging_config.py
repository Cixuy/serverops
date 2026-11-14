from __future__ import annotations

import logging
import os
import sys
from typing import TextIO

LOGGER_NAME = "serverops"
RESET = "\033[0m"
LEVEL_COLORS = {
    logging.DEBUG: "\033[90m",
    logging.INFO: "\033[36m",
    logging.WARNING: "\033[33m",
    logging.ERROR: "\033[31m",
    logging.CRITICAL: "\033[1;31m",
}


def _supports_color(output: TextIO) -> bool:
    if (
        os.environ.get("NO_COLOR") is not None
        or os.environ.get("TERM") == "dumb"
        or not bool(getattr(output, "isatty", lambda: False)())
    ):
        return False
    if os.name != "nt":
        return True
    try:
        import ctypes
        import msvcrt

        handle = msvcrt.get_osfhandle(output.fileno())
        mode = ctypes.c_uint()
        kernel32 = ctypes.windll.kernel32
        if not kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            return False
        return bool(kernel32.SetConsoleMode(handle, mode.value | 0x0004))
    except (AttributeError, OSError, ValueError):
        return False


class ColorFormatter(logging.Formatter):
    def __init__(self, use_color: bool) -> None:
        super().__init__(
            fmt="%(asctime)s [%(levelname)s] [%(threadName)s] %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
        self.use_color = use_color

    def format(self, record: logging.LogRecord) -> str:
        text = super().format(record)
        if not self.use_color:
            return text
        color = LEVEL_COLORS.get(record.levelno)
        marker = f"[{record.levelname}]"
        return text.replace(marker, f"{color}{marker}{RESET}", 1) if color else text


def configure_logging(
    level: int | None = None,
    stream: TextIO | None = None,
    use_color: bool | None = None,
) -> logging.Logger:
    output = stream or sys.stdout
    if level is None:
        configured_level = os.environ.get("SERVEROPS_LOG_LEVEL", "INFO").upper()
        level = logging.getLevelNamesMapping().get(configured_level, logging.INFO)
    if use_color is None:
        use_color = _supports_color(output)

    logging.addLevelName(logging.WARNING, "WARN")
    handler = logging.StreamHandler(output)
    handler.setFormatter(ColorFormatter(use_color))

    logger = logging.getLogger(LOGGER_NAME)
    logger.handlers.clear()
    logger.addHandler(handler)
    logger.setLevel(level)
    logger.propagate = False
    return logger


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(f"{LOGGER_NAME}.{name}")
