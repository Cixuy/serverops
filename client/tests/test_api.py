import json

import httpx
import pytest

from serverops_client.api import AgentApi, ApiError
from serverops_client.config import AgentConfig
from serverops_client.models import HostInfo


def config(server_url: str = "http://server.test") -> AgentConfig:
    return AgentConfig(server_url=server_url, agent_token="secret")


def host_info() -> HostInfo:
    return HostInfo("host", "10.0.0.2", "LINUX", "Linux", "CPU", "8 GB", "/ 100 GB", "0.1.0")


def test_register_uses_openapi_path_header_and_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/servers/register"
        assert request.headers["X-Agent-Token"] == "secret"
        assert b'"ipAddress":"10.0.0.2"' in request.content
        return httpx.Response(
            200, json={"code": 0, "msg": "success", "data": {"serverCode": "SRV-1"}}
        )

    with AgentApi(config(), transport=httpx.MockTransport(handler)) as api:
        assert api.register(host_info()) == "SRV-1"


def test_api_base_path_is_not_duplicated() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/servers/heartbeat"
        return httpx.Response(200, json={"code": 0, "msg": "success"})

    with AgentApi(
        config("http://server.test/api/v1"), transport=httpx.MockTransport(handler)
    ) as api:
        api.heartbeat("SRV-1", 123)


def test_api_rejects_failed_business_result() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(200, json={"code": 401, "message": "Token 无效"})
    )
    with (
        AgentApi(config(), transport=transport) as api,
        pytest.raises(ApiError, match="Token 无效"),
    ):
        api.report_metrics({})


def test_api_rejects_legacy_200_business_code() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(200, json={"code": 200, "msg": "旧成功码"})
    )
    with (
        AgentApi(config(), transport=transport) as api,
        pytest.raises(ApiError, match="旧成功码"),
    ):
        api.heartbeat("SRV-1", 123)


def test_get_monitor_configs_uses_openapi_path_and_query() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/agent/monitor-configs"
        assert request.url.params["serverCode"] == "SRV-1"
        return httpx.Response(
            200,
            json={
                "code": 0,
                "msg": "success",
                "data": [
                    {
                        "configId": 7,
                        "serviceName": "Nginx",
                        "processName": "nginx",
                        "port": 8080,
                        "checkInterval": 15,
                    }
                ],
            },
        )

    with AgentApi(config(), transport=httpx.MockTransport(handler)) as api:
        configs = api.get_monitor_configs("SRV-1")

    assert configs[0].service_name == "Nginx"
    assert configs[0].port == 8080
    assert configs[0].check_interval == 15


def test_report_service_status_uses_openapi_path() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/agent/service-status/report"
        assert json.loads(request.content)["services"][0]["portStatus"] == 1
        return httpx.Response(200, json={"code": 0, "msg": "success"})

    with AgentApi(config(), transport=httpx.MockTransport(handler)) as api:
        api.report_service_status(
            {
                "serverCode": "SRV-1",
                "services": [{"configId": 1, "portStatus": 1}],
            }
        )


def test_report_alert_event_accepts_new_api_success_code() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/alert-events"
        assert json.loads(request.content)["alert_type"] == "SERVICE_DOWN"
        return httpx.Response(
            200,
            json={
                "code": 200,
                "message": "上报成功",
                "data": {"id": 10086, "alert_no": "ALM-1"},
            },
        )

    with AgentApi(config(), transport=httpx.MockTransport(handler)) as api:
        alert_id = api.report_alert_event({"alert_type": "SERVICE_DOWN"})

    assert alert_id == 10086


def test_get_bound_healing_rules_accepts_paginated_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/service-healing-bindings"
        assert request.url.params["serverCode"] == "SRV-1"
        assert request.url.params["configId"] == "7"
        return httpx.Response(
            200,
            json={
                "code": 200,
                "message": "success",
                "data": {
                    "list": [
                        {
                            "id": 3,
                            "ruleName": "Restart nginx",
                            "triggerConfig": {
                                "type": "SERVICE_STATUS",
                                "metric": "process",
                                "operator": "=",
                                "value": "0",
                            },
                            "executeCommand": "restart nginx",
                            "timeoutSeconds": 15,
                            "maxRetries": 2,
                            "retryInterval": 3,
                            "status": 1,
                        }
                    ]
                },
            },
        )

    with AgentApi(config(), transport=httpx.MockTransport(handler)) as api:
        rules = api.get_bound_healing_rules("SRV-1", 7)

    assert rules[0].rule_id == 3
    assert rules[0].trigger_config["value"] == "0"
    assert rules[0].max_retries == 2


def test_report_healing_log_uses_action_log_endpoint() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/healing-action-logs"
        assert json.loads(request.content)["executionStatus"] == "SUCCESS"
        return httpx.Response(200, json={"code": 200, "message": "success"})

    with AgentApi(config(), transport=httpx.MockTransport(handler)) as api:
        api.report_healing_log({"executionStatus": "SUCCESS"})


def test_get_error_contains_url_and_query_parameters() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(503, json={"message": "unavailable"})
    )

    with AgentApi(config(), transport=transport) as api, pytest.raises(ApiError) as caught:
        api.get_bound_healing_rules("SRV-1", 7)

    message = str(caught.value)
    assert "GET http://server.test/api/v1/service-healing-bindings?" in message
    assert "serverCode=SRV-1" in message
    assert "configId=7" in message
    assert '参数: {"serverCode":"SRV-1","configId":7}' in message
    assert "503 Service Unavailable" in message


def test_post_error_contains_sanitized_payload() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(500, json={"message": "failed"})
    )
    payload = {
        "serverCode": "SRV-1",
        "password": "do-not-log",
        "nested": {"agentToken": "also-secret", "status": "FAILED"},
    }

    with AgentApi(config(), transport=transport) as api, pytest.raises(ApiError) as caught:
        api.report_metrics(payload)

    message = str(caught.value)
    assert "POST http://server.test/api/v1/metrics" in message
    assert '"serverCode":"SRV-1"' in message
    assert '"password":"<redacted>"' in message
    assert '"agentToken":"<redacted>"' in message
    assert "do-not-log" not in message
    assert "also-secret" not in message
    assert '响应: {"message":"failed"}' in message


def test_get_error_redacts_sensitive_query_value_from_url() -> None:
    with AgentApi(config()) as api:
        error = api._request_error(
            "GET",
            "/api/v1/example",
            {"serverCode": "SRV-1", "agentToken": "must-not-leak"},
            "failed",
        )

    message = str(error)
    assert "agentToken=%3Credacted%3E" in message
    assert '"agentToken":"<redacted>"' in message
    assert "must-not-leak" not in message
