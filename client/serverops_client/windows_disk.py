from __future__ import annotations

import ctypes
from ctypes import wintypes
from dataclasses import dataclass
from typing import ClassVar

PDH_FMT_DOUBLE = 0x00000200
ERROR_SUCCESS = 0


class _PdhValueUnion(ctypes.Union):
    _fields_ = [
        ("long_value", wintypes.LONG),
        ("double_value", ctypes.c_double),
        ("large_value", ctypes.c_longlong),
        ("ansi_string_value", wintypes.LPSTR),
        ("wide_string_value", wintypes.LPWSTR),
    ]


class _PdhFormattedCounterValue(ctypes.Structure):
    _anonymous_ = ("value",)
    _fields_ = [("status", wintypes.DWORD), ("value", _PdhValueUnion)]


@dataclass(frozen=True, slots=True)
class DiskIoRate:
    read_kbps: float = 0.0
    write_kbps: float = 0.0
    read_iops: int = 0
    write_iops: int = 0


class WindowsLogicalDiskIo:
    """Read per-volume IO rates from Windows LogicalDisk performance counters."""

    COUNTER_NAMES: ClassVar[dict[str, str]] = {
        "read_kbps": "Disk Read Bytes/sec",
        "write_kbps": "Disk Write Bytes/sec",
        "read_iops": "Disk Reads/sec",
        "write_iops": "Disk Writes/sec",
    }

    def __init__(self) -> None:
        self._pdh = ctypes.WinDLL("pdh", use_last_error=True)
        self._configure_functions()
        self._query = wintypes.HANDLE()
        self._counters: dict[str, dict[str, wintypes.HANDLE]] = {}
        self._mount_points: tuple[str, ...] = ()

    def _configure_functions(self) -> None:
        self._pdh.PdhOpenQueryW.argtypes = [wintypes.LPCWSTR, ctypes.c_size_t, ctypes.c_void_p]
        self._pdh.PdhOpenQueryW.restype = wintypes.LONG
        self._pdh.PdhAddEnglishCounterW.argtypes = [
            wintypes.HANDLE,
            wintypes.LPCWSTR,
            ctypes.c_size_t,
            ctypes.c_void_p,
        ]
        self._pdh.PdhAddEnglishCounterW.restype = wintypes.LONG
        self._pdh.PdhCollectQueryData.argtypes = [wintypes.HANDLE]
        self._pdh.PdhCollectQueryData.restype = wintypes.LONG
        self._pdh.PdhGetFormattedCounterValue.argtypes = [
            wintypes.HANDLE,
            wintypes.DWORD,
            ctypes.c_void_p,
            ctypes.POINTER(_PdhFormattedCounterValue),
        ]
        self._pdh.PdhGetFormattedCounterValue.restype = wintypes.LONG
        self._pdh.PdhCloseQuery.argtypes = [wintypes.HANDLE]
        self._pdh.PdhCloseQuery.restype = wintypes.LONG

    def sample(self, mount_points: list[str]) -> dict[str, DiskIoRate]:
        normalized = tuple(sorted({self._instance_name(path) for path in mount_points}))
        if normalized != self._mount_points:
            self._rebuild(normalized)
        if not self._query:
            return {}
        if self._pdh.PdhCollectQueryData(self._query) != ERROR_SUCCESS:
            return {}

        values: dict[str, DiskIoRate] = {}
        for instance, counters in self._counters.items():
            read_bytes = self._read(counters["read_kbps"])
            write_bytes = self._read(counters["write_kbps"])
            values[instance] = DiskIoRate(
                read_kbps=read_bytes / 1024,
                write_kbps=write_bytes / 1024,
                read_iops=round(self._read(counters["read_iops"])),
                write_iops=round(self._read(counters["write_iops"])),
            )
        return values

    def _rebuild(self, mount_points: tuple[str, ...]) -> None:
        self.close()
        self._mount_points = mount_points
        query = wintypes.HANDLE()
        if self._pdh.PdhOpenQueryW(None, 0, ctypes.byref(query)) != ERROR_SUCCESS:
            return
        self._query = query

        for instance in mount_points:
            handles: dict[str, wintypes.HANDLE] = {}
            for field, counter_name in self.COUNTER_NAMES.items():
                handle = wintypes.HANDLE()
                path = f"\\LogicalDisk({instance})\\{counter_name}"
                status = self._pdh.PdhAddEnglishCounterW(
                    self._query, path, 0, ctypes.byref(handle)
                )
                if status == ERROR_SUCCESS:
                    handles[field] = handle
            if len(handles) == len(self.COUNTER_NAMES):
                self._counters[instance] = handles
        self._pdh.PdhCollectQueryData(self._query)

    def _read(self, counter: wintypes.HANDLE) -> float:
        value = _PdhFormattedCounterValue()
        status = self._pdh.PdhGetFormattedCounterValue(
            counter, PDH_FMT_DOUBLE, None, ctypes.byref(value)
        )
        if status != ERROR_SUCCESS or value.status != ERROR_SUCCESS:
            return 0.0
        return max(0.0, value.double_value)

    def close(self) -> None:
        if getattr(self, "_query", None):
            self._pdh.PdhCloseQuery(self._query)
        self._query = wintypes.HANDLE()
        self._counters = {}

    @staticmethod
    def _instance_name(mount_point: str) -> str:
        return mount_point.rstrip("/\\")

    def __del__(self) -> None:
        self.close()
