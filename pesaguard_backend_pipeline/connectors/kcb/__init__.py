"""KCB Bank source connector."""

from connectors.base import AdapterConnector
from ingestion import KcbAdapter


class KcbConnector(AdapterConnector):
    def __init__(self, ingestion_service, credentials=None):
        super().__init__(KcbAdapter(), ingestion_service, credentials)


__all__ = ["KcbConnector"]