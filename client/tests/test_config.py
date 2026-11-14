import json
import socket

import pytest

from serverops_client.config import AgentConfig, ConfigStore


def test_config_round_trip_and_server_code(tmp_path) -> None:  # type: ignore[no-untyped-def]
    store = ConfigStore(tmp_path)
    original = AgentConfig(server_url="https://ops.example.com", agent_token="secret")

    store.save(original)
    store.save_server_code("SRV-123")

    assert store.load() == original
    assert store.load_server_code() == "SRV-123"
    assert json.loads(store.state_path.read_text(encoding="utf-8"))["serverCode"] == "SRV-123"


def test_alert_report_time_round_trip_preserves_server_code(tmp_path) -> None:  # type: ignore[no-untyped-def]
    store = ConfigStore(tmp_path)
    store.save_server_code("SRV-123")
    store.save_alert_report_time(7, 1234.5)

    assert store.load_server_code() == "SRV-123"
    assert store.load_alert_report_times() == {7: 1234.5}


def test_config_rejects_invalid_values() -> None:
    with pytest.raises(ValueError, match="Agent Token"):
        AgentConfig().validate()
    with pytest.raises(ValueError, match="采样周期"):
        AgentConfig(agent_token="x", sample_interval=0.5).validate()
    with pytest.raises(ValueError, match="告警冷却"):
        AgentConfig(agent_token="x", alert_cooldown_minutes=0).validate()
    with pytest.raises(ValueError, match="自愈超时复检延时"):
        AgentConfig(agent_token="x", healing_timeout_recheck_delay=-1).validate()


def test_config_loads_healing_timeout_recheck_delay_from_environment(
    tmp_path, monkeypatch
) -> None:  # type: ignore[no-untyped-def]
    monkeypatch.setenv("SERVEROPS_HEALING_TIMEOUT_RECHECK_DELAY", "8.5")

    config = ConfigStore(tmp_path).load()

    assert config.healing_timeout_recheck_delay == 8.5


def test_server_address_resolves_hostname_and_port(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    calls = []
    monkeypatch.setattr(
        "serverops_client.config.socket.getaddrinfo",
        lambda host, port, **kwargs: calls.append((host, port, kwargs)) or [(object(),)],
    )

    AgentConfig(server_url="https://ops.example.com:9443/api/v1", agent_token="x").resolve_server_address()

    assert calls == [("ops.example.com", 9443, {"type": socket.SOCK_STREAM})]


def test_server_address_rejects_getaddrinfo_failure(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    def fail(*_args, **_kwargs):  # type: ignore[no-untyped-def]
        raise socket.gaierror(11001, "host not found")

    monkeypatch.setattr("serverops_client.config.socket.getaddrinfo", fail)

    with pytest.raises(ValueError, match="无法解析服务器地址"):
        AgentConfig(server_url="http://invalid.example", agent_token="x").resolve_server_address()
