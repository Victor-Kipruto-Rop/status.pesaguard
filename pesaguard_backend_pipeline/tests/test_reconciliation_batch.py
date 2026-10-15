from types import SimpleNamespace

import reconciliation_job


def test_polled_batch_preserves_partition_order_and_bounds_batch(monkeypatch):
    seen = []

    def fake_process(event, consumer, producer, registry):
        seen.append(event["id"])
        return True

    monkeypatch.setattr(reconciliation_job, "_process_message", fake_process)
    monkeypatch.setattr(reconciliation_job, "_commit_message", lambda consumer, message: None)
    messages = {
        "partition-a": [SimpleNamespace(value={"id": index, "tenant_id": "tenant-a"}) for index in range(3)],
        "partition-b": [SimpleNamespace(value={"id": index + 10, "tenant_id": "tenant-a"}) for index in range(2)],
    }
    result = reconciliation_job.process_polled_batch(messages, None, None, None)

    assert result == {"processed": 5, "failed": 0}
    assert seen == [0, 1, 2, 10, 11]


def test_polled_batch_counts_failures_and_commits_success_or_durable_quarantine(monkeypatch):
    committed = []

    def fake_process(event, consumer, producer, registry):
        return event["ok"]

    monkeypatch.setattr(reconciliation_job, "_process_message", fake_process)
    monkeypatch.setattr(
        reconciliation_job,
        "_commit_message",
        lambda consumer, message: committed.append(message.offset),
    )
    dead_letters = []
    monkeypatch.setattr(
        reconciliation_job.event_store,
        "write_dead_letter",
        lambda payload, reason=None, **k: dead_letters.append((payload, reason)) or True,
    )
    messages = {"partition-a": [
        SimpleNamespace(value={"ok": True, "tenant_id": "tenant-a"}, offset=7),
        SimpleNamespace(value={"ok": False, "tenant_id": "tenant-a"}, offset=8),
    ]}

    assert reconciliation_job.process_polled_batch(messages, None, None, None) == {"processed": 1, "failed": 1}
    # Rule #10/#30/#31: the failed message is quarantined in the dead-letter store
    # and its offset advances, so it can never be silently acknowledged as
    # processed on redelivery. The success offset commits as before.
    assert committed == [7, 8]
    assert len(dead_letters) == 1


def test_handle_message_dead_letters_and_advances_offset_on_failure(monkeypatch):
    # The shared failure policy used by BOTH the poll path and the iterate
    # fallback: a failed message is dead-lettered and its offset is advanced, so a
    # poison payload cannot be redelivered forever (Rule #10/#30/#31/#57).
    committed = []
    dead_letters = []
    monkeypatch.setattr(reconciliation_job, "_process_message", lambda *a, **k: False)
    monkeypatch.setattr(
        reconciliation_job,
        "_commit_message",
        lambda consumer, message: committed.append(message.offset),
    )
    monkeypatch.setattr(
        reconciliation_job,
        "_dead_letter_message",
        lambda message: dead_letters.append(getattr(message, "offset", None)) or True,
    )

    message = SimpleNamespace(value={"bad": "payload", "tenant_id": "tenant-a"}, offset=42)
    handled = reconciliation_job._handle_message(None, message, None, None)

    assert handled is False
    assert committed == [42]            # offset advanced despite failure
    assert dead_letters == [42]         # payload quarantined for replay


def test_handle_message_commits_offset_only_on_success(monkeypatch):
    committed = []
    dead_letters = []
    monkeypatch.setattr(reconciliation_job, "_process_message", lambda *a, **k: True)
    monkeypatch.setattr(
        reconciliation_job,
        "_commit_message",
        lambda consumer, message: committed.append(message.offset),
    )
    monkeypatch.setattr(
        reconciliation_job,
        "_dead_letter_message",
        lambda message: dead_letters.append(getattr(message, "offset", None)) or True,
    )

    message = SimpleNamespace(value={"good": "payload", "tenant_id": "tenant-a"}, offset=9)
    handled = reconciliation_job._handle_message(None, message, None, None)

    assert handled is True
    assert committed == [9]
    assert dead_letters == []
