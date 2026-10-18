package com.pesaguard.backend.events.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.events.domain.EventLifecycle;
import com.pesaguard.backend.events.domain.EventTypeDefinition;

public interface EventTypeDefinitionRepository extends JpaRepository<EventTypeDefinition, String> {

    List<EventTypeDefinition> findByLifecycleInOrderByNameAsc(List<EventLifecycle> lifecycles);
}
