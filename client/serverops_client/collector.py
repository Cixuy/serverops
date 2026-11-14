from __future__ import annotations

import os
import platform
import socket
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import psutil

from . import __version__
from .models import DiskMetric, HostInfo, MetricSnapshot
from .windows_disk import WindowsLogicalDiskIo

BYTES_PER_MB = 1024**2
BYTES_PER_GB = 1024**3
PSEUDO_FILESYSTEMS = {
    "autofs",
    "cgroup",
    "cgroup2",
    "devpts",
    "devtmpfs",
    "overlay",
    "proc",
    "squashfs",
    "sysfs",
    "tmpfs",
}


def _local_ip() -> str:
    try:
        addresses = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        candidates = [item[4][0] for item in addresses if not item[4][0].startswith("127.")]
        return candidates[0] if candidates else "127.0.0.1"
    except OSError:
        return "127.0.0.1"


class SystemCollector:
    def __init__(
        self,
        psutil_module: Any = psutil,
        clock: Callable[[], float] = time.monotonic,
        wall_clock: Callable[[], float] = time.time,
        logical_disk_io: Any | None = None,
    ) -> None:
        self.psutil = psutil_module
        self.clock = clock
        self.wall_clock = wall_clock
        self._last_time: float | None = None
        self._last_network: Any | None = None
        self._last_disk: dict[str, Any] = {}
        self._last_disk_total: Any | None = None
        self._logical_disk_io = logical_disk_io
        if self._logical_disk_io is None and os.name == "nt":
            self._logical_disk_io = WindowsLogicalDiskIo()
        self.psutil.cpu_percent(interval=None)

    def host_info(self) -> HostInfo:
        memory = self.psutil.virtual_memory()
        partitions = self._partitions()
        disk_parts: list[str] = []
        for partition in partitions:
            try:
                usage = self.psutil.disk_usage(partition.mountpoint)
            except (OSError, PermissionError):
                continue
            disk_parts.append(f"{partition.mountpoint} {usage.total / BYTES_PER_GB:.1f} GB")

        system = platform.system().upper()
        os_type = "WINDOWS" if system == "WINDOWS" else "LINUX"
        cpu_name = platform.processor().strip() or platform.machine()
        return HostInfo(
            hostname=socket.gethostname(),
            ip_address=_local_ip(),
            os_type=os_type,
            os_version=platform.platform(),
            cpu_info=f"{cpu_name} ({self.psutil.cpu_count(logical=True) or 1} cores)",
            memory_info=f"{memory.total / BYTES_PER_GB:.1f} GB",
            disk_info="; ".join(disk_parts),
            agent_version=__version__,
        )

    def collect(self) -> MetricSnapshot:
        now = self.clock()
        elapsed = max(now - self._last_time, 0.001) if self._last_time is not None else None
        network = self.psutil.net_io_counters()
        disk_total = self.psutil.disk_io_counters()
        disk_by_device = self.psutil.disk_io_counters(perdisk=True) or {}

        network_in = self._rate(network.bytes_recv, self._last_network, "bytes_recv", elapsed)
        network_out = self._rate(network.bytes_sent, self._last_network, "bytes_sent", elapsed)
        disk_read_total = self._rate(disk_total.read_bytes, self._last_disk_total, "read_bytes", elapsed)
        disk_write_total = self._rate(
            disk_total.write_bytes, self._last_disk_total, "write_bytes", elapsed
        )

        partitions = self._partitions()
        logical_rates = (
            self._logical_disk_io.sample([partition.mountpoint for partition in partitions])
            if self._logical_disk_io
            else {}
        )
        disks: list[DiskMetric] = []
        for partition in partitions:
            try:
                usage = self.psutil.disk_usage(partition.mountpoint)
            except (OSError, PermissionError):
                continue
            key = self._disk_key(partition.device, disk_by_device)
            current = disk_by_device.get(key) if key else None
            previous = self._last_disk.get(key) if key else None
            logical_rate = logical_rates.get(partition.mountpoint.rstrip("/\\"))
            read_kbps = (
                logical_rate.read_kbps
                if logical_rate
                else self._rate(current, previous, "read_bytes", elapsed)
            )
            write_kbps = (
                logical_rate.write_kbps
                if logical_rate
                else self._rate(current, previous, "write_bytes", elapsed)
            )
            read_iops = (
                logical_rate.read_iops
                if logical_rate
                else int(self._rate(current, previous, "read_count", elapsed, 1))
            )
            write_iops = (
                logical_rate.write_iops
                if logical_rate
                else int(self._rate(current, previous, "write_count", elapsed, 1))
            )
            disks.append(
                DiskMetric(
                    mount_point=partition.mountpoint,
                    fs_type=partition.fstype,
                    disk_read_kbps=read_kbps,
                    disk_write_kbps=write_kbps,
                    disk_iops_read=read_iops,
                    disk_iops_write=write_iops,
                    disk_usage=usage.percent,
                    disk_used_gb=usage.used / BYTES_PER_GB,
                    disk_total_gb=usage.total / BYTES_PER_GB,
                )
            )

        memory = self.psutil.virtual_memory()
        self._last_time = now
        self._last_network = network
        self._last_disk_total = disk_total
        self._last_disk = disk_by_device
        return MetricSnapshot(
            timestamp=int(self.wall_clock() * 1000),
            hostname=socket.gethostname(),
            cpu_usage=self.psutil.cpu_percent(interval=None),
            cpu_count=self.psutil.cpu_count(logical=True) or 1,
            memory_usage=memory.percent,
            memory_used_mb=memory.used / BYTES_PER_MB,
            memory_total_mb=memory.total / BYTES_PER_MB,
            network_in_kbps=network_in,
            network_out_kbps=network_out,
            disks=disks,
            disk_read_kbps_total=disk_read_total,
            disk_write_kbps_total=disk_write_total,
        )

    def _partitions(self) -> list[Any]:
        partitions = self.psutil.disk_partitions(all=False)
        return [
            partition
            for partition in partitions
            if partition.fstype and partition.fstype.lower() not in PSEUDO_FILESYSTEMS
        ]

    @staticmethod
    def _rate(
        current: Any,
        previous: Any | None,
        attribute: str,
        elapsed: float | None,
        divisor: int = 1024,
    ) -> float:
        if current is None or previous is None or elapsed is None:
            return 0.0
        current_value = getattr(current, attribute) if not isinstance(current, (int, float)) else current
        previous_value = getattr(previous, attribute)
        return max(0.0, (current_value - previous_value) / elapsed / divisor)

    @staticmethod
    def _disk_key(device: str, counters: dict[str, Any]) -> str | None:
        normalized = device.rstrip("/\\")
        candidates = [normalized, Path(normalized).name, normalized.replace("\\", "")]
        lowered = {key.lower(): key for key in counters}
        for candidate in candidates:
            if candidate.lower() in lowered:
                return lowered[candidate.lower()]
        return None
