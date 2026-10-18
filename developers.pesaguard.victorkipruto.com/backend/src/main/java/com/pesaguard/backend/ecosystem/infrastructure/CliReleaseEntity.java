package com.pesaguard.backend.ecosystem.infrastructure;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.ecosystem.domain.CliArchitecture;
import com.pesaguard.backend.ecosystem.domain.CliPlatform;
import com.pesaguard.backend.ecosystem.domain.CliRelease;
import com.pesaguard.backend.ecosystem.domain.ReleaseChannel;
import com.pesaguard.backend.ecosystem.domain.ReleaseStatus;
import com.pesaguard.backend.ecosystem.domain.SemanticVersion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read mapping for immutable CLI release metadata. */
@Entity
@Table(name = "cli_releases")
public class CliReleaseEntity {
    @Id private UUID id;
    @Column(nullable = false) private String version;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private CliPlatform platform;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private CliArchitecture architecture;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ReleaseChannel channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ReleaseStatus status;
    @Column(nullable = false) private String checksum;
    @Column(name = "download_url", nullable = false) private String downloadUrl;
    @Column(name = "release_notes") private String releaseNotes;
    @Column(name = "published_at", nullable = false) private Instant publishedAt;

    protected CliReleaseEntity() { }

    public CliRelease toDomain() {
        return new CliRelease(id, SemanticVersion.parse(version)
                .orElseThrow(() -> new IllegalStateException("Stored CLI release has an invalid version.")),
                platform, architecture, channel, status, checksum, downloadUrl, releaseNotes, publishedAt);
    }
}
