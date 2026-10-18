package com.pesaguard.backend.support.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.support.domain.SupportCategory;

public interface SupportCategoryRepository extends JpaRepository<SupportCategory, String> {

    List<SupportCategory> findAllByOrderBySortOrderAsc();
}
