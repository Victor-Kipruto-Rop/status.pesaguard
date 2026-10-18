package com.pesaguard.backend.scopes.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.scopes.domain.ApiScopeDefinition;

public interface ApiScopeDefinitionRepository extends JpaRepository<ApiScopeDefinition, String> {

    Optional<ApiScopeDefinition> findByName(String name);

    List<ApiScopeDefinition> findByCategoryOrderByNameAsc(String category);

    List<ApiScopeDefinition> findByDeprecatedTrueOrderByNameAsc();

    List<ApiScopeDefinition> findByRestrictedTrueOrderByNameAsc();

    List<ApiScopeDefinition> findAllByOrderByCategoryAscNameAsc();
}