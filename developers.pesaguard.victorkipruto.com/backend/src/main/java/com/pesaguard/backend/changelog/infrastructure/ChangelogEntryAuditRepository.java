package com.pesaguard.backend.changelog.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.changelog.domain.ChangelogEntryAudit;

public interface ChangelogEntryAuditRepository extends JpaRepository<ChangelogEntryAudit, UUID> {
}
