package com.pesaguard.backend.ecosystem.infrastructure;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.ecosystem.domain.ApiCompatibility;
import com.pesaguard.backend.ecosystem.domain.ReleaseChannel;
import com.pesaguard.backend.ecosystem.domain.ReleaseStatus;
import com.pesaguard.backend.ecosystem.domain.SdkLanguage;
import com.pesaguard.backend.ecosystem.domain.SdkRelease;
import com.pesaguard.backend.ecosystem.domain.SemanticVersion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read mapping for immutable release-pipeline metadata. */
@Entity
@Table(name = "sdk_releases")
public class SdkReleaseEntity {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SdkLanguage language;
    @Column(nullable = false) private String version;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ReleaseChannel channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ReleaseStatus status;
    @Column(name = "minimum_api_major", nullable = false) private int minimumApiMajor;
    @Column(name = "maximum_api_major", nullable = false) private int maximumApiMajor;
    @Column(nullable = false) private String checksum;
    @Column(name = "documentation_url") private String documentationUrl;
    @Column(name = "release_notes") private String releaseNotes;
    @Column(name = "published_at", nullable = false) private Instant publishedAt;
    @Column(name = "deprecated_at") private Instant deprecatedAt;
    @Column(name = "deprecation_reason") private String deprecationReason;
    @Column(name = "superseded_by") private String supersededBy;

    protected SdkReleaseEntity() { }

    public SdkRelease toDomain() {
        return new SdkRelease(id, language, SemanticVersion.parse(version)
                .orElseThrow(() -> new IllegalStateException("Stored SDK release has an invalid version.")),
                channel, status, ApiCompatibility.of(minimumApiMajor, maximumApiMajor), checksum,
                documentationUrl, releaseNotes, publishedAt, deprecatedAt, deprecationReason, supersededBy);
    }
}
