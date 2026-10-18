package com.pesaguard.backend.changelog.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.changelog.domain.ChangelogEntry;

import jakarta.persistence.LockModeType;

public interface ChangelogEntryRepository extends JpaRepository<ChangelogEntry, UUID> {

    List<ChangelogEntry> findByStatusOrderByPublishedAtDescIdDesc(String status);

    List<ChangelogEntry> findByStatusOrderByUpdatedAtDescIdDesc(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from ChangelogEntry entry where entry.id = :entryId")
    Optional<ChangelogEntry> findByIdForUpdate(@Param("entryId") UUID entryId);
}
