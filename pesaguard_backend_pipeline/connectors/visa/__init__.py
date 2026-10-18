"""Visa card network source connector."""

from connectors.base import AdapterConnector
from ingestion import VisaAdapter


class VisaConnector(AdapterConnector):
    def __init__(self, ingestion_service, credentials=None):
        super().__init__(VisaAdapter(), ingestion_service, credentials)


__all__ = ["VisaConnector"]