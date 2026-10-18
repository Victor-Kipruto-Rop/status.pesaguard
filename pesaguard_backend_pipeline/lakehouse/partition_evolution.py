"""
Partition evolution for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, List, Optional

from .lakehouse_types import (
    LakehouseError, PartitionEvolution, PartitionSpec,
)


class PartitionEvolutionError(LakehouseError):
    pass


@dataclass(frozen=True)
class PartitionEvolutionPlan:
    table_name: str
    old_spec: PartitionSpec
    new_spec: PartitionSpec
    evolution_id: str
    effective_from: str
    status: str
    created_at: str = field(
        default_factory=lambda: datetime.now(timezone.utc).isoformat()
    )
    completed_at: Optional[str] = None


class PartitionEvolutionManager:
    def __init__(self, config):
        self.config = config
        self._evolutions: Dict[str, PartitionEvolutionPlan] = {}
        self._table_partitions: Dict[str, PartitionSpec] = {}

    def register_table_partition(
        self, table_name: str, spec: PartitionSpec
    ) -> None:
        self._table_partitions[table_name] = spec

    def get_table_partition(self, table_name: str) -> Optional[PartitionSpec]:
        return self._table_partitions.get(table_name)

    def plan_evolution(
        self,
        table_name: str,
        new_spec: PartitionSpec,
        effective_from: Optional[str] = None,
    ) -> PartitionEvolutionPlan:
        old_spec = self._table_partitions.get(table_name)
        if old_spec is None:
            raise PartitionEvolutionError(
                f"No partition spec registered for table {table_name}"
            )
        if old_spec == new_spec:
            raise PartitionEvolutionError(
                f"New partition spec is identical to current spec for table {table_name}"
            )
        evolution_id = (
            f"evol_{table_name}_"
            f"{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"
        )
        effective = effective_from or datetime.now(timezone.utc).isoformat()
        plan = PartitionEvolutionPlan(
            table_name=table_name,
            old_spec=old_spec,
            new_spec=new_spec,
            evolution_id=evolution_id,
            effective_from=effective,
            status="planned",
        )
        self._evolutions[evolution_id] = plan
        return plan

    def execute_evolution(self, evolution_id: str) -> PartitionEvolutionPlan:
        plan = self._evolutions.get(evolution_id)
        if plan is None:
            raise PartitionEvolutionError(f"Evolution {evolution_id} not found")
        if plan.status != "planned":
            raise PartitionEvolutionError(
                f"Evolution {evolution_id} is already {plan.status}"
            )
        self._table_partitions[plan.table_name] = plan.new_spec
        completed = PartitionEvolutionPlan(
            table_name=plan.table_name,
            old_spec=plan.old_spec,
            new_spec=plan.new_spec,
            evolution_id=plan.evolution_id,
            effective_from=plan.effective_from,
            status="completed",
            created_at=plan.created_at,
            completed_at=datetime.now(timezone.utc).isoformat(),
        )
        self._evolutions[evolution_id] = completed
        return completed

    def rollback_evolution(self, evolution_id: str) -> PartitionEvolutionPlan:
        plan = self._evolutions.get(evolution_id)
        if plan is None:
            raise PartitionEvolutionError(f"Evolution {evolution_id} not found")
        if plan.status not in ("planned", "in_progress"):
            raise PartitionEvolutionError(
                f"Evolution {evolution_id} cannot be rolled back (status: {plan.status})"
            )
        rolled_back = PartitionEvolutionPlan(
            table_name=plan.table_name,
            old_spec=plan.old_spec,
            new_spec=plan.new_spec,
            evolution_id=plan.evolution_id,
            effective_from=plan.effective_from,
            status="rolled_back",
            created_at=plan.created_at,
            completed_at=datetime.now(timezone.utc).isoformat(),
        )
        self._evolutions[evolution_id] = rolled_back
        return rolled_back

    def get_evolution(self, evolution_id: str) -> Optional[PartitionEvolutionPlan]:
        return self._evolutions.get(evolution_id)

    def list_evolutions(
        self, table_name: Optional[str] = None
    ) -> List[PartitionEvolutionPlan]:
        plans = list(self._evolutions.values())
        if table_name:
            plans = [p for p in plans if p.table_name == table_name]
        return sorted(plans, key=lambda p: p.created_at)
