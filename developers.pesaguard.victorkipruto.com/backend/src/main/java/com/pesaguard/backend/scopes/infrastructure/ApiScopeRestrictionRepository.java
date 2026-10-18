package com.pesaguard.backend.scopes.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.scopes.domain.ApiScopeRestriction;

public interface ApiScopeRestrictionRepository extends JpaRepository<ApiScopeRestriction, String> {

    Optional<ApiScopeRestriction> findByScopeName(String scopeName);

    List<ApiScopeRestriction> findByRequiresSecurityReviewTrueOrderByScopeNameAsc();
}