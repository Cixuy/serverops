from datetime import datetime

from serverops_client.models import (
    DiskMetric,
    HealingRule,
    MetricSnapshot,
    MonitorConfig,
    ServiceStatus,
    alert_event_payload,
    service_status_report_payload,
)


def test_healing_rule_accepts_websocket_style_snapshot() -> None:
    rule = HealingRule.from_payload(
        {
            "rule_id": 9,
            "status": 1,
            "rule_snapshot": {
                "rule_name": "restart",
                "trigger_config": {"logic": "AND", "conditions": []},
                "execute_command": "service restart",
                "timeout_seconds": 10,
                "max_retries": 1,
                "retry_interval": 2,
            },
        }
    )

    assert rule.rule_id == 9
    assert rule.execute_command == "service restart"
    assert rule.timeout_seconds == 10


def test_metric_payload_matches_openapi_contract() -> None:
    snapshot = MetricSnapshot(
        timestamp=1_724_133_600_000,
        hostname="Web-Server-01",
        cpu_usage=45.234,
        cpu_count=8,
        memory_usage=68.5,
        memory_used_mb=5480,
        memory_total_mb=8192,
        network_in_kbps=125.6,
        network_out_kbps=89.3,
        disks=[
            DiskMetric(
                mount_point="/",
                fs_type="ext4",
                disk_read_kbps=2048.5,
                disk_write_kbps=1024.3,
                disk_iops_read=350,
                disk_iops_write=120,
                disk_usage=72.8,
                disk_used_gb=364,
                disk_total_gb=500,
            )
        ],
    )

    payload = snapshot.to_report_payload("SRV-8F3A2B9C")

    assert payload["serverCode"] == "SRV-8F3A2B9C"
    assert payload["timestamp"] == 1_724_133_600_000
    assert payload["metrics"] == {
        "cpuUsage": 45.23,
        "cpuCount": 8,
        "memoryUsage": 68.5,
        "memoryUsedMb": 5480,
        "memoryTotalMb": 8192,
        "networkInKbps": 125.6,
        "networkOutKbps": 89.3,
    }
    assert payload["disks"][0]["mountPoint"] == "/"
    assert payload["disks"][0]["diskIopsRead"] == 350


def test_service_status_payload_matches_openapi_contract() -> None:
    payload = service_status_report_payload(
        "SRV-1",
        [ServiceStatus(7, "Nginx", 1, 123, 1, 1_724_133_600_000)],
    )
    assert payload == {
        "serverCode": "SRV-1",
        "services": [
            {
                "configId": 7,
                "serviceName": "Nginx",
                "isAlive": 1,
                "pid": 123,
                "portStatus": 1,
                "lastCheckTime": 1_724_133_600_000,
                "alertContext": "",
            }
        ],
    }


def test_alert_event_payload_matches_new_openapi_contract() -> None:
    payload = alert_event_payload(
        "SRV-1",
        MonitorConfig(7, "Nginx", "nginx", 8080),
        ServiceStatus(7, "Nginx", 0, 0, 0, 1_724_133_600_000, "进程未运行"),
    )
    assert payload["server_code"] == "SRV-1"
    assert payload["config_id"] == 7
    assert payload["alert_type"] == "SERVICE_DOWN"
    assert payload["alert_level"] == "CRITICAL"
    assert payload["alert_content"]["configured_port"] == 8080
    assert payload["alert_content"]["error_msg"] == "进程未运行"
    assert "T" in payload["trigger_time"]
    assert datetime.fromisoformat(payload["trigger_time"]).tzinfo is not None
    assert "rule_id" not in payload


def test_alert_event_payload_includes_related_rule_id() -> None:
    payload = alert_event_payload(
        "SRV-1",
        MonitorConfig(7, "Nginx", "nginx", 8080),
        ServiceStatus(7, "Nginx", 0, 0, 0, 1_724_133_600_000),
        rule_id=9,
    )

    assert payload["rule_id"] == 9


def test_port_failure_is_reported_as_degraded_warning() -> None:
    payload = alert_event_payload(
        "SRV-1",
        MonitorConfig(7, "Nginx", "nginx", 8080),
        ServiceStatus(7, "Nginx", 1, 42, 0, 1_724_133_600_000, "端口不可达"),
    )
    assert payload["alert_type"] == "SERVICE_DEGRADED"
    assert payload["alert_level"] == "WARNING"
