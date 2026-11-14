from serverops_client.storage import MetricQueue


def test_queue_is_bounded_and_acknowledges_items(tmp_path) -> None:  # type: ignore[no-untyped-def]
    queue = MetricQueue(tmp_path / "metrics.db", limit=2)
    queue.enqueue({"sequence": 1})
    queue.enqueue({"sequence": 2})
    queue.enqueue({"sequence": 3})

    items = queue.due()

    assert queue.count() == 2
    assert [payload["sequence"] for _, payload in items] == [2, 3]
    queue.acknowledge(items[0][0])
    assert queue.count() == 1


def test_queue_postpones_failed_item(tmp_path) -> None:  # type: ignore[no-untyped-def]
    queue = MetricQueue(tmp_path / "metrics.db")
    queue.enqueue({"sequence": 1})
    item_id, _ = queue.due(now=0)[0]

    queue.postpone(item_id)

    assert queue.due() == []
