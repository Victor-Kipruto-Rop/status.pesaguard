"""Absa Bank source connector."""

from connectors.base import AdapterConnector
from ingestion import AbsaAdapter


class AbsaConnector(AdapterConnector):
    def __init__(self, ingestion_service, credentials=None):
        super().__init__(AbsaAdapter(), ingestion_service, credentials)


__all__ = ["AbsaConnector"]