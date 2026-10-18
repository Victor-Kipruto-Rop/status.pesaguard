package com.pesaguard.backend.ecosystem.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.pesaguard.backend.ecosystem.domain.SdkLanguage;
import java.util.UUID;

public interface SdkReleaseRepository extends JpaRepository<SdkReleaseEntity, UUID> {
    List<SdkReleaseEntity> findByLanguage(SdkLanguage language);
    List<SdkReleaseEntity> findAllByOrderByPublishedAtDesc();
}
