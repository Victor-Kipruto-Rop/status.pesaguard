"""Mastercard card network source connector."""

from connectors.base import AdapterConnector
from ingestion import MastercardAdapter


class MastercardConnector(AdapterConnector):
    def __init__(self, ingestion_service, credentials=None):
        super().__init__(MastercardAdapter(), ingestion_service, credentials)


__all__ = ["MastercardConnector"]