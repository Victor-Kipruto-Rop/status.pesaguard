"""Replay the bounded Kafka offset interval [start_offset, end_offset)."""

from __future__ import annotations

import argparse
import json
import os
from typing import Any

from kafka import KafkaConsumer, KafkaProducer, TopicPartition


def replay(
    source_topic: str,
    replay_topic: str,
    partition: int,
    start_offset: int,
    end_offset: int,
    tenant_id: str | None = None,
    provider_account_id: str | None = None,
) -> dict[str, Any]:
    if not isinstance(source_topic, str) or not source_topic.strip():
        raise ValueError("source_topic is required")
    if not isinstance(replay_topic, str) or not replay_topic.strip():
        raise ValueError("replay_topic is required")
    if source_topic == replay_topic:
        raise ValueError("replay_topic must be different from source_topic")
    if isinstance(partition, bool) or not isinstance(partition, int) or partition < 0:
        raise ValueError("partition must be a non-negative integer")
    if isinstance(start_offset, bool) or not isinstance(start_offset, int) or start_offset < 0:
        raise ValueError("start_offset must be a non-negative integer")
    if isinstance(end_offset, bool) or not isinstance(end_offset, int) or end_offset <= start_offset:
        raise ValueError("end_offset must be greater than start_offset")
    if tenant_id is not None and (not isinstance(tenant_id, str) or not tenant_id.strip()):
        raise ValueError("tenant_id must be a non-empty string when provided")
    if provider_account_id is not None and (
        not isinstance(provider_account_id, str) or not provider_account_id.strip()
    ):
        raise ValueError("provider_account_id must be a non-empty string when provided")

    bootstrap_servers = [
        server.strip()
        for server in os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092").split(",")
        if server.strip()
    ]
    if not bootstrap_servers:
        raise ValueError("KAFKA_BOOTSTRAP_SERVERS must contain at least one broker")
    topic_partition = TopicPartition(source_topic, partition)
    consumer = None
    producer = None
    replayed = 0
    scanned = 0
    try:
        consumer = KafkaConsumer(
            bootstrap_servers=bootstrap_servers,
            enable_auto_commit=False,
            auto_offset_reset="none",
            consumer_timeout_ms=1000,
            value_deserializer=lambda value: json.loads(value.decode("utf-8")),
        )
        producer = KafkaProducer(
            bootstrap_servers=bootstrap_servers,
            acks="all",
            value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
        )
        consumer.assign([topic_partition])
        consumer.seek(topic_partition, start_offset)
        while True:
            records = consumer.poll(timeout_ms=1000, max_records=500)
            if not records:
                break
            stop = False
            for message in records.get(topic_partition, []):
                if message.offset >= end_offset:
                    stop = True
                    break
                scanned += 1
                payload = message.value
                if tenant_id and str(payload.get("tenant_id") or payload.get("TenantID")) != tenant_id:
                    continue
                if provider_account_id and str(payload.get("provider_account_id") or payload.get("BusinessShortCode")) != provider_account_id:
                    continue
                producer.send(replay_topic, key=message.key, value=payload).get(timeout=10)
                replayed += 1
            if stop:
                break
        producer.flush()
        return {
            "source_topic": source_topic,
            "replay_topic": replay_topic,
            "partition": partition,
            "start_offset": start_offset,
            "end_offset": end_offset,
            "scanned": scanned,
            "replayed": replayed,
            "tenant_id": tenant_id,
            "provider_account_id": provider_account_id,
        }
    finally:
        try:
            if consumer is not None:
                consumer.close()
        finally:
            if producer is not None:
                producer.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Replay a bounded Kafka offset range")
    parser.add_argument("source_topic")
    parser.add_argument("replay_topic")
    parser.add_argument("--partition", type=int, required=True)
    parser.add_argument("--start-offset", type=int, required=True)
    parser.add_argument("--end-offset", type=int, required=True)
    parser.add_argument("--tenant-id")
    parser.add_argument("--provider-account-id")
    args = parser.parse_args()
    if args.end_offset <= args.start_offset:
        parser.error("--end-offset must be greater than --start-offset")
    print(json.dumps(replay(
        args.source_topic,
        args.replay_topic,
        args.partition,
        args.start_offset,
        args.end_offset,
        args.tenant_id,
        args.provider_account_id,
    ), indent=2))


if __name__ == "__main__":
    main()
