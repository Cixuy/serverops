import json

from serverops_client.config import AgentConfig
from serverops_client.websocket import (
    ConfigWebSocketClient,
    client_websocket_url,
    pull_monitor_config_message,
)


class FakeConnection:
    def __init__(self):
        self.messages = []

    def send(self, message):
        self.messages.append(json.loads(message))


def config(server_url: str = "http://server.test") -> AgentConfig:
    return AgentConfig(server_url=server_url, agent_token="device secret")


def test_client_websocket_url_uses_device_endpoint_and_auth_query() -> None:
    assert client_websocket_url(config("https://server.test/api/v1"), "SRV-A7IDAYIG") == (
        "wss://server.test/api/v1/ws/client?apiKey=device+secret&serverCode=SRV-A7IDAYIG"
    )


def test_pull_monitor_config_message_matches_protocol() -> None:
    assert json.loads(pull_monitor_config_message("SRV-A7IDAYIG", "uuid-114514")) == {
        "action": "PULL_MONITOR_CONFIG",
        "request_id": "uuid-114514",
        "payload": {"server_code": "SRV-A7IDAYIG"},
    }


def test_websocket_response_updates_configs_and_change_triggers_pull() -> None:
    received = []
    connection = FakeConnection()
    client = ConfigWebSocketClient(config(), "SRV-A7IDAYIG", received.append)

    client._handle_message(
        connection,
        json.dumps(
            {
                "action": "PULL_MONITOR_CONFIG_RESP",
                "data": [
                    {
                        "config_id": 7,
                        "service_name": "Nginx",
                        "process_name": "nginx",
                        "port": 8080,
                        "check_interval": 15,
                    }
                ],
            }
        ),
    )
    assert received[0][0].config_id == 7
    assert received[0][0].check_interval == 15

    client._handle_message(
        connection,
        json.dumps(
            {
                "action": "MONITOR_CONFIG_CHANGED",
                "server_code": "SRV-A7IDAYIG",
                "action_type": "PUT",
                "status": 1,
            }
        ),
    )
    assert connection.messages[-1]["action"] == "PULL_MONITOR_CONFIG"
    assert connection.messages[-1]["payload"]["server_code"] == "SRV-A7IDAYIG"


def test_healing_rule_change_requests_immediate_check() -> None:
    statuses = []
    client = ConfigWebSocketClient(
        config(),
        "SRV-A7IDAYIG",
        lambda _configs: None,
        lambda kind, message: statuses.append((kind, message)),
    )

    client._handle_message(
        FakeConnection(),
        json.dumps({"action": "HEALING_RULE_BINDING_CHANGE", "data": []}),
    )

    assert statuses == [("healing_rules_changed", "自愈规则已变更，正在重新检查")]


def test_pong_is_accepted_without_status_noise() -> None:
    statuses = []
    client = ConfigWebSocketClient(
        config(),
        "SRV-A7IDAYIG",
        lambda _configs: None,
        lambda kind, message: statuses.append((kind, message)),
    )

    client._handle_message(FakeConnection(), json.dumps({"action": "pong"}))

    assert statuses == []
