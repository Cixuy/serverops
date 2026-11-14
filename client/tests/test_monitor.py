from types import SimpleNamespace

from serverops_client.models import MonitorConfig
from serverops_client.monitor import ServiceMonitor


class FakePsutil:
    def process_iter(self, _attrs):
        return [SimpleNamespace(info={"name": "nginx.exe", "pid": 42})]


class FakeSocket:
    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

    def create_connection(self, address, timeout):
        assert address == ("127.0.0.1", 8080)
        assert timeout == 1.0
        return self.Connection()


def test_service_monitor_checks_process_and_port() -> None:
    result = ServiceMonitor(FakePsutil(), FakeSocket()).check(
        MonitorConfig(7, "Nginx", "nginx", 8080), timestamp=123
    )
    assert result.to_payload() == {
        "configId": 7,
        "serviceName": "Nginx",
        "isAlive": 1,
        "pid": 42,
        "portStatus": 1,
        "lastCheckTime": 123,
        "alertContext": "",
    }
