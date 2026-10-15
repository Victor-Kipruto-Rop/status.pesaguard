"""Regression tests for the Reconciliation Job consume loop (services 44/47).

Rule #10/#30/#31: a failed reconciliation message must never be silently
dropped. Before the dead-letter fix, ``process_polled_batch`` left a message
whose ``_process_message`` returned ``False`` completely unquarantined -- it
only incremented a ``failed`` counter and left the offset unconsumed. On
redelivery, the partial ``ProcessedTransaction`` row written by
``_persist_atomically`` made the replay return ``True`` and commit the offset,
so a genuinely failed financial record was silently acknowledged as processed.

These tests pin the delivery contract: durable processing or durable
quarantine precedes offset commits; storage failures pause and retry the
partition; redelivery after a crash does not duplicate financial effects.
"""

from types import SimpleNamespace

import pytest

from event_store import EventStore
from models import Base, DeadLetter
import reconciliation_job


def _message(trans_id, offset, ok, partition=0):
    return SimpleNamespace(
        value={"tenant_id": "tenant-a", "TransID": trans_id, "TransAmount": "100.00", "ok": ok},
        topic="mpesa.transactions.raw",
        partition=partition,
        offset=offset,
        headers=[],
    )


def test_failed_message_is_quarantined_and_offset_advances(monkeypatch):
    # A failed message (False) must produce a dead-letter and still advance the
    # offset -- it is observable/recoverable, never silently dropped.
    dead_letters = []
    committed = []

    monkeypatch.setattr(
        reconciliation_job,
        "_process_message",
        lambda event, *a, **k: event["ok"],
    )
    monkeypatch.setattr(
        reconciliation_job,
        "_commit_message",
        lambda consumer, message: committed.append(message.offset),
    )
    monkeypatch.setattr(
        reconciliation_job.event_store,
        "write_dead_letter",
        lambda payload, reason=None, **k: dead_letters.append((payload, reason)) or True,
    )

    messages = {
        ("mpesa.transactions.raw", 0): [
            _message("TX-OK", 7, True),
            _message("TX-FAIL", 8, False),
        ]
    }

    result = reconciliation_job.process_polled_batch(messages, None, None, None)

    assert result == {"processed": 1, "failed": 1}
    # Offset advanced for BOTH the success and the quarantined failure, so the
    # failed record is not silently redelivered and later acknowledged.
    assert committed == [7, 8]
    # Exactly one dead-letter was recorded, carrying the failed payload.
    assert len(dead_letters) == 1
    payload, reason = dead_letters[0]
    assert reason == "reconciliation_message_processing_failed"
    assert payload["TransID"] == "TX-FAIL"


def test_successful_message_never_dead_lettered(monkeypatch):
    # A clean success must not create any dead-letter entry.
    dead_letters = []
    committed = []

    monkeypatch.setattr(
        reconciliation_job,
        "_process_message",
        lambda event, *a, **k: event["ok"],
    )
    monkeypatch.setattr(
        reconciliation_job,
        "_commit_message",
        lambda consumer, message: committed.append(message.offset),
    )
    monkeypatch.setattr(
        reconciliation_job.event_store,
        "write_dead_letter",
        lambda payload, reason=None, **k: dead_letters.append((payload, reason)),
    )

    messages = {("mpesa.transactions.raw", 0): [_message("TX-OK", 3, True)]}

    result = reconciliation_job.process_polled_batch(messages, None, None, None)

    assert result == {"processed": 1, "failed": 0}
    assert committed == [3]
    assert dead_letters == []


def test_dead_letter_database_failure_pauses_partition_without_advancing(monkeypatch):
    # A failed reconciliation with no durable quarantine must remain in Kafka.
    # Stop this partition before processing later messages that could otherwise
    # commit past the failed offset.
    reconciliation_job._PAUSED_PARTITIONS.clear()
    calls = []
    committed = []
    processed = []
    dead_letter_calls = []
    durable_dead_letters = set()

    class Consumer:
        def pause(self, partition):
            calls.append(("pause", partition))

        def seek(self, partition, offset):
            calls.append(("seek", partition, offset))

        def resume(self, partition):
            calls.append(("resume", partition))

        def commit(self, offsets=None):
            committed.append(offsets)

    consumer = Consumer()

    def process(event, *args, **kwargs):
        processed.append(event["TransID"])
        return event["TransID"] != "TX-FAIL"

    monkeypatch.setattr(reconciliation_job, "_process_message", process)

    def write_dead_letter(payload, **kwargs):
        dead_letter_calls.append((payload, kwargs))
        if len(dead_letter_calls) == 1:
            return False
        durable_dead_letters.add(payload["TransID"])
        return True

    monkeypatch.setattr(reconciliation_job.event_store, "write_dead_letter", write_dead_letter)
    monkeypatch.setattr(
        reconciliation_job,
        "_commit_message",
        lambda _consumer, message: committed.append(message.offset),
    )
    partition = ("mpesa.transactions.raw", 0)
    failed_message = _message("TX-FAIL", 1, False)
    other_partition = ("mpesa.transactions.raw", 1)
    messages = {
        partition: [failed_message, _message("TX-LATER", 2, True)],
        other_partition: [_message("TX-OTHER-PARTITION", 4, True, partition=1)],
    }

    result = reconciliation_job.process_polled_batch(messages, consumer, None, None)

    assert result == {"processed": 1, "failed": 1}
    assert committed == [4]
    assert processed == ["TX-FAIL", "TX-OTHER-PARTITION"]
    assert calls == [("pause", partition), ("seek", partition, 1)]
    assert reconciliation_job._PAUSED_PARTITIONS[partition] > reconciliation_job.time.monotonic()
    assert dead_letter_calls[0][1]["tenant_id"] == "tenant-a"
    assert dead_letter_calls[0][1]["error_detail"] == (
        '{"disposition":"quarantined","offset":1,"partition":0,'
        '"topic":"mpesa.transactions.raw"}'
    )

    # The retry timer is the operational recovery path: resume and retry the
    # failed offset. Once quarantine commits, the same partition can progress.
    retry_at = reconciliation_job._PAUSED_PARTITIONS[partition]
    reconciliation_job._resume_due_partitions(consumer, now=retry_at)
    assert calls[-1] == ("resume", partition)
    assert partition not in reconciliation_job._PAUSED_PARTITIONS
    resumed = reconciliation_job.process_polled_batch(
        {partition: messages[partition]}, consumer, None, None
    )
    assert resumed == {"processed": 1, "failed": 1}
    assert durable_dead_letters == {"TX-FAIL"}
    assert processed == [
        "TX-FAIL",
        "TX-OTHER-PARTITION",
        "TX-FAIL",
        "TX-LATER",
    ]
    assert committed == [4, 1, 2]


def test_successful_processing_commit_crash_redelivers_without_duplicate_effects(monkeypatch):
    effects = []
    committed = []
    already_processed = set()

    def process(event, *args, **kwargs):
        trans_id = event["TransID"]
        if trans_id in already_processed:
            return True
        effects.append(trans_id)
        already_processed.add(trans_id)
        return True

    def commit(_consumer, message):
        committed.append(message.offset)
        if len(committed) == 1:
            raise RuntimeError("commit outcome unknown")

    monkeypatch.setattr(reconciliation_job, "_process_message", process)
    monkeypatch.setattr(reconciliation_job, "_commit_message", commit)
    message = _message("TX-ONCE", 7, True)

    with pytest.raises(reconciliation_job._MessageRetryRequired):
        reconciliation_job._handle_message(None, message, None, None)

    # Kafka may redeliver when the commit outcome was not confirmed. Durable
    # idempotency makes that replay a no-op before the second commit.
    assert reconciliation_job._handle_message(None, message, None, None) is True
    assert effects == ["TX-ONCE"]
    assert committed == [7, 7]


def test_durable_dead_letter_commit_crash_redelivers_idempotently(monkeypatch):
    quarantined = set()
    committed = []

    monkeypatch.setattr(reconciliation_job, "_process_message", lambda *a, **k: False)

    def dead_letter(message, **kwargs):
        quarantined.add((message.topic, message.partition, message.offset))
        return True

    def commit(_consumer, message):
        committed.append(message.offset)
        if len(committed) == 1:
            raise RuntimeError("commit outcome unknown")

    monkeypatch.setattr(reconciliation_job, "_dead_letter_message", dead_letter)
    monkeypatch.setattr(reconciliation_job, "_commit_message", commit)
    message = _message("TX-POISON", 12, False)

    with pytest.raises(reconciliation_job._MessageRetryRequired):
        reconciliation_job._handle_message(None, message, None, None)

    assert reconciliation_job._handle_message(None, message, None, None) is False
    assert quarantined == {("mpesa.transactions.raw", 0, 12)}
    assert committed == [12, 12]


def test_database_dead_letter_commit_crash_redelivery_keeps_one_auditable_disposition(
    tmp_path, monkeypatch
):
    database_url = f"sqlite:///{tmp_path / 'dead-letter-commit-crash.db'}"
    store = EventStore(database_url=database_url)
    store._ensure_ready()
    Base.metadata.create_all(store.engine)
    monkeypatch.setattr(reconciliation_job, "event_store", store)
    monkeypatch.setattr(reconciliation_job, "_process_message", lambda *a, **k: False)
    commit_attempts = []

    def commit(_consumer, _message):
        commit_attempts.append(True)
        if len(commit_attempts) == 1:
            raise RuntimeError("commit outcome unknown")

    monkeypatch.setattr(reconciliation_job, "_commit_message", commit)
    message = _message("TX-DLQ-REPLAY", 19, False)

    with pytest.raises(reconciliation_job._MessageRetryRequired):
        reconciliation_job._handle_message(None, message, None, None)
    assert reconciliation_job._handle_message(None, message, None, None) is False

    with store.Session() as session:
        records = session.query(DeadLetter).all()
        assert len(records) == 1
        assert records[0].tenant_id == "tenant-a"
        assert records[0].reason == "reconciliation_message_processing_failed"
        assert records[0].error_detail == (
            '{"disposition":"quarantined","offset":19,"partition":0,'
            '"topic":"mpesa.transactions.raw"}'
        )
    assert len(commit_attempts) == 2
    store.engine.dispose()


def test_malformed_kafka_payload_is_durably_quarantined_before_commit(monkeypatch):
    operations = []
    message = _message("TX-BAD", 4, False)
    message.value = b'{"TransID":'

    def dead_letter(received, **kwargs):
        operations.append(("dead-letter", received.offset, kwargs.get("reason")))
        return True

    def commit(_consumer, received):
        operations.append(("commit", received.offset))

    monkeypatch.setattr(reconciliation_job, "_dead_letter_message", dead_letter)
    monkeypatch.setattr(reconciliation_job, "_commit_message", commit)

    assert reconciliation_job._handle_message(None, message, None, None) is False
    assert operations == [
        ("dead-letter", 4, "invalid_reconciliation_message"),
        ("commit", 4),
    ]


def test_missing_tenant_is_quarantined_without_default_tenant_fallback(monkeypatch):
    operations = []
    message = _message("TX-NO-TENANT", 9, False)
    message.value = {"TransID": "TX-NO-TENANT", "TransAmount": "100.00"}

    monkeypatch.setattr(
        reconciliation_job,
        "_process_message",
        lambda *args, **kwargs: pytest.fail("tenantless message must not be processed"),
    )
    monkeypatch.setattr(
        reconciliation_job.event_store,
        "write_dead_letter",
        lambda payload, **kwargs: operations.append(
            ("dead-letter", kwargs.get("tenant_id"), kwargs.get("reason"))
        ) or True,
    )
    monkeypatch.setattr(
        reconciliation_job,
        "_commit_message",
        lambda _consumer, received: operations.append(("commit", received.offset)),
    )

    assert reconciliation_job._handle_message(None, message, None, None) is False
    assert operations == [
        (
            "dead-letter",
            reconciliation_job.UNATTRIBUTED_TENANT_ID,
            "reconciliation_message_processing_failed",
        ),
        ("commit", 9),
    ]


def test_offset_commit_is_explicit_and_never_falls_back_to_current_positions():
    calls = []

    class Consumer:
        def commit(self, offsets=None):
            calls.append(offsets)
            raise AttributeError("broker response failed")

    message = _message("TX-COMMIT", 5, True)

    with pytest.raises(AttributeError, match="broker response failed"):
        reconciliation_job._commit_message(Consumer(), message)

    assert len(calls) == 1
    [(partition, offset)] = calls[0].items()
    assert (partition.topic, partition.partition, offset.offset) == (
        message.topic,
        message.partition,
        message.offset + 1,
    )
