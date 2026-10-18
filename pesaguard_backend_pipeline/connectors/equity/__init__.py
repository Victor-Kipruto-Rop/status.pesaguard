"""Equity Bank source connector."""

from connectors.base import AdapterConnector
from ingestion import EquityAdapter


class EquityConnector(AdapterConnector):
    def __init__(self, ingestion_service, credentials=None):
        super().__init__(EquityAdapter(), ingestion_service, credentials)


__all__ = ["EquityConnector"]