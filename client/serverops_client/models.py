from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any


def _rounded(value: float) -> float:
    return round(max(0.0, value), 2)


@dataclass(slots=True, frozen=True)
class MonitorConfig:
    """服务端下发给 Agent 的当前监控配置。"""

    config_id: int
    service_name: str
    process_name: str = ""
    port: int | None = None
    check_interval: int | None = None

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> MonitorConfig:
        return cls(
            config_id=int(payload.get("configId") or payload.get("config_id") or 0),
            service_name=str(
                payload.get("serviceName") or payload.get("service_name") or "未命名服务"
            ),
            process_name=str(payload.get("processName") or payload.get("process_name") or ""),
            port=int(payload["port"]) if payload.get("port") is not None else None,
            check_interval=(
                int(payload.get("checkInterval") or payload.get("check_interval"))
                if payload.get("checkInterval") is not None
                or payload.get("check_interval") is not None
                else None
            ),
        )


@dataclass(slots=True, frozen=True)
class ServiceStatus:
    """进程和端口探测结果，字段名与服务端 OpenAPI 保持一致。"""

    config_id: int
    service_name: str
    is_alive: int
    pid: int
    port_status: int
    last_check_time: int
    alert_context: str = ""

    def to_payload(self) -> dict[str, Any]:
        return {
            "configId": self.config_id,
            "serviceName": self.service_name,
            "isAlive": self.is_alive,
            "pid": self.pid,
            "portStatus": self.port_status,
            "lastCheckTime": self.last_check_time,
            "alertContext": self.alert_context,
        }


@dataclass(slots=True, frozen=True)
class HealingRule:
    """A self-healing rule returned for a monitored service."""

    rule_id: int
    rule_name: str
    trigger_config: dict[str, Any]
    execute_command: str
    timeout_seconds: int = 60
    max_retries: int = 0
    retry_interval: int = 0
    status: int = 1

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> HealingRule:
        snapshot = payload.get("rule_snapshot") or payload.get("ruleSnapshot")
        rule = snapshot if isinstance(snapshot, dict) else payload
        trigger_config = rule.get("triggerConfig") or rule.get("trigger_config") or {}
        return cls(
            rule_id=int(payload.get("id") or payload.get("ruleId") or payload.get("rule_id") or 0),
            rule_name=str(rule.get("ruleName") or rule.get("rule_name") or "未命名规则"),
            trigger_config=trigger_config if isinstance(trigger_config, dict) else {},
            execute_command=str(
                rule.get("executeCommand") or rule.get("execute_command") or ""
            ),
            timeout_seconds=max(
                1, int(rule.get("timeoutSeconds") or rule.get("timeout_seconds") or 60)
            ),
            max_retries=max(
                0, int(rule.get("maxRetries") or rule.get("max_retries") or 0)
            ),
            retry_interval=max(
                0, int(rule.get("retryInterval") or rule.get("retry_interval") or 0)
            ),
            status=int(payload.get("status", rule.get("status", 1))),
        )


def service_status_report_payload(
    server_code: str, statuses: list[ServiceStatus]
) -> dict[str, Any]:
    return {"serverCode": server_code, "services": [status.to_payload() for status in statuses]}


def alert_event_payload(
    server_code: str,
    config: MonitorConfig,
    status: ServiceStatus,
    rule_id: int | None = None,
) -> dict[str, Any]:
    process_down = bool(config.process_name) and status.is_alive == 0
    alert_type = "SERVICE_DOWN" if process_down else "SERVICE_DEGRADED"
    alert_level = "CRITICAL" if process_down else "WARNING"
    payload = {
        "server_code": server_code,
        "config_id": config.config_id,
        "service_name": config.service_name,
        "alert_type": alert_type,
        "alert_level": alert_level,
        "alert_content": {
            "error_msg": status.alert_context,
            "process_name": config.process_name,
            "configured_port": config.port,
            "is_alive": status.is_alive,
            "pid": status.pid,
            "port_status": status.port_status,
        },
        "trigger_time": (
            datetime(1970, 1, 1, tzinfo=UTC)
            + timedelta(milliseconds=status.last_check_time)
        )
        .astimezone()
        .isoformat(timespec="seconds"),
    }
    if rule_id is not None:
        payload["rule_id"] = rule_id
    return payload


@dataclass(slots=True)
class HostInfo:
    hostname: str
    ip_address: str
    os_type: str
    os_version: str
    cpu_info: str
    memory_info: str
    disk_info: str
    agent_version: str

    def to_register_payload(self) -> dict[str, Any]:
        return {
            "hostname": self.hostname,
            "ipAddress": self.ip_address,
            "osType": self.os_type,
            "osVersion": self.os_version,
            "cpuInfo": self.cpu_info[:255],
            "memoryInfo": self.memory_info[:128],
            "diskInfo": self.disk_info[:512],
            "agentVersion": self.agent_version,
        }


@dataclass(slots=True)
class DiskMetric:
    mount_point: str
    fs_type: str
    disk_read_kbps: float
    disk_write_kbps: float
    disk_iops_read: int
    disk_iops_write: int
    disk_usage: float
    disk_used_gb: float
    disk_total_gb: float

    def to_payload(self) -> dict[str, Any]:
        return {
            "mountPoint": self.mount_point,
            "fsType": self.fs_type,
            "diskReadKbps": _rounded(self.disk_read_kbps),
            "diskWriteKbps": _rounded(self.disk_write_kbps),
            "diskIopsRead": max(0, self.disk_iops_read),
            "diskIopsWrite": max(0, self.disk_iops_write),
            "diskUsage": _rounded(self.disk_usage),
            "diskUsedGb": _rounded(self.disk_used_gb),
            "diskTotalGb": _rounded(self.disk_total_gb),
        }


@dataclass(slots=True)
class MetricSnapshot:
    timestamp: int
    hostname: str
    cpu_usage: float
    cpu_count: int
    memory_usage: float
    memory_used_mb: float
    memory_total_mb: float
    network_in_kbps: float
    network_out_kbps: float
    disks: list[DiskMetric] = field(default_factory=list)
    disk_read_kbps_total: float = 0.0
    disk_write_kbps_total: float = 0.0

    def to_report_payload(self, server_code: str) -> dict[str, Any]:
        return {
            "serverCode": server_code,
            "hostname": self.hostname,
            "timestamp": self.timestamp,
            "metrics": {
                "cpuUsage": _rounded(self.cpu_usage),
                "cpuCount": self.cpu_count,
                "memoryUsage": _rounded(self.memory_usage),
                "memoryUsedMb": _rounded(self.memory_used_mb),
                "memoryTotalMb": _rounded(self.memory_total_mb),
                # The OpenAPI names these Kbps, but defines the unit as KB/s.
                "networkInKbps": _rounded(self.network_in_kbps),
                "networkOutKbps": _rounded(self.network_out_kbps),
            },
            "disks": [disk.to_payload() for disk in self.disks],
        }
