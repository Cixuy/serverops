from __future__ import annotations

import socket
import time
from pathlib import Path
from typing import Any

import psutil

from .models import MonitorConfig, ServiceStatus


class ServiceMonitor:
    """执行服务配置中的进程存活和本机 TCP 端口探测。"""

    def __init__(self, psutil_module: Any = psutil, socket_module: Any = socket) -> None:
        self.psutil = psutil_module
        self.socket = socket_module

    def check(self, config: MonitorConfig, timestamp: int | None = None) -> ServiceStatus:
        process_alive, pid = self._check_process(config.process_name)
        port_status = self._check_port(config.port)
        alerts: list[str] = []
        if config.process_name and not process_alive:
            alerts.append(f"进程未运行: {config.process_name}")
        if config.port is not None and not port_status:
            alerts.append(f"端口不可达: {config.port}")
        return ServiceStatus(
            config_id=config.config_id,
            service_name=config.service_name,
            is_alive=int(process_alive),
            pid=pid,
            port_status=int(port_status),
            last_check_time=int(time.time() * 1000) if timestamp is None else timestamp,
            alert_context="；".join(alerts),
        )

    def _check_process(self, process_name: str) -> tuple[bool, int]:
        if not process_name:
            return True, 0
        target = process_name.casefold()
        try:
            processes = self.psutil.process_iter(["name", "pid"])
            for process in processes:
                try:
                    name = str(process.info.get("name") or "")
                    stem = Path(name).stem
                    if name.casefold() == target or stem.casefold() == target:
                        return True, int(process.info.get("pid") or 0)
                except (psutil.Error, OSError, ValueError):
                    continue
        except (psutil.Error, OSError):
            pass
        return False, 0

    def _check_port(self, port: int | None) -> bool:
        if port is None:
            return True
        try:
            with self.socket.create_connection(("127.0.0.1", port), timeout=1.0):
                return True
        except OSError:
            return False
