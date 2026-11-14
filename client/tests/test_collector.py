from collections import namedtuple
from types import SimpleNamespace

from serverops_client.collector import BYTES_PER_GB, SystemCollector
from serverops_client.windows_disk import DiskIoRate

Partition = namedtuple("Partition", "device mountpoint fstype opts")
Io = namedtuple("Io", "read_bytes write_bytes read_count write_count")
Network = namedtuple("Network", "bytes_recv bytes_sent")


class FakePsutil:
    def cpu_percent(self, interval=None):  # type: ignore[no-untyped-def]
        return 10.0

    def cpu_count(self, logical=True):  # type: ignore[no-untyped-def]
        return 8

    def virtual_memory(self):  # type: ignore[no-untyped-def]
        return SimpleNamespace(total=16 * BYTES_PER_GB, used=8 * BYTES_PER_GB, percent=50.0)

    def net_io_counters(self):  # type: ignore[no-untyped-def]
        return Network(1000, 2000)

    def disk_io_counters(self, perdisk=False):  # type: ignore[no-untyped-def]
        counters = Io(1000, 2000, 10, 20)
        return {"PhysicalDrive0": counters} if perdisk else counters

    def disk_partitions(self, all=False):  # type: ignore[no-untyped-def]
        return [Partition("C:\\", "C:\\", "NTFS", "")]

    def disk_usage(self, _mountpoint):  # type: ignore[no-untyped-def]
        return SimpleNamespace(total=100 * BYTES_PER_GB, used=25 * BYTES_PER_GB, percent=25.0)


class FakeLogicalDiskIo:
    def sample(self, mount_points: list[str]) -> dict[str, DiskIoRate]:
        assert mount_points == ["C:\\"]
        return {"C:": DiskIoRate(12.5, 34.5, 3, 7)}


def test_windows_logical_disk_rates_override_unmatched_physical_key() -> None:
    collector = SystemCollector(psutil_module=FakePsutil(), logical_disk_io=FakeLogicalDiskIo())

    disk = collector.collect().disks[0]

    assert disk.disk_read_kbps == 12.5
    assert disk.disk_write_kbps == 34.5
    assert disk.disk_iops_read == 3
    assert disk.disk_iops_write == 7
