-- Phase 17: SDK and CLI registry.
--
-- Metadata only. The artefacts themselves are built and stored by the release
-- pipeline, and the source lives in separate repositories; this records enough
-- for a developer to choose a version and verify what they downloaded.
--
-- The integrity guarantee rests on the checksum columns. They are validated as
-- SHA-256 hex in the domain and constrained here, because a registry that
-- publishes an unverifiable checksum is worse than one that publishes none.

create table sdk_releases (
    id uuid primary key,
    language varchar(24) not null,
    -- Parsed semver, stored as its normalised string form.
    version varchar(64) not null,
    channel varchar(16) not null,
    status varchar(24) not null default 'PUBLISHED',
    minimum_api_major integer not null,
    maximum_api_major integer not null,
    -- Lower-case 64-character SHA-256 hex.
    checksum varchar(64) not null,
    documentation_url varchar(500),
    release_notes text,
    published_at timestamptz not null,
    deprecated_at timestamptz,
    deprecation_reason varchar(500),
    superseded_by varchar(64),
    created_at timestamptz not null default now(),
    constraint sdk_releases_language_check check (
        language in ('JAVA', 'PYTHON', 'TYPESCRIPT', 'GO', 'PHP', 'DOTNET')
    ),
    constraint sdk_releases_channel_check check (
        channel in ('ALPHA', 'BETA', 'STABLE', 'NEXT')
    ),
    constraint sdk_releases_status_check check (
        status in ('PUBLISHED', 'DEPRECATED', 'WITHDRAWN')
    ),
    -- One release per language and version. Without this, two builds of the same
    -- version could disagree on their checksum and a developer verifying against
    -- either would be misled.
    constraint sdk_releases_version_unique unique (language, version),
    constraint sdk_releases_api_range_check check (
        minimum_api_major >= 1 and maximum_api_major >= minimum_api_major
    ),
    -- A deprecated release must say why and when. A developer told to migrate
    -- deserves to know what to.
    constraint sdk_releases_deprecation_complete check (
        status <> 'DEPRECATED'
        or (deprecated_at is not null and deprecation_reason is not null
            and btrim(deprecation_reason) <> '')
    ),
    -- A deprecated release still has a publication time; the two must not be
    -- confused.
    constraint sdk_releases_deprecation_order check (
        deprecated_at is null or deprecated_at >= published_at
    ),
    -- Enforced here as well as in the domain: a checksum that is not SHA-256 hex
    -- cannot be verified against, so it must never be published.
    constraint sdk_releases_checksum_check check (checksum ~ '^[0-9a-f]{64}$')
);

create index if not exists sdk_releases_language_idx
    on sdk_releases(language, channel, status);

create index if not exists sdk_releases_recommended_idx
    on sdk_releases(language, version desc)
    where status = 'PUBLISHED' and channel = 'STABLE';

create table cli_releases (
    id uuid primary key,
    version varchar(64) not null,
    platform varchar(16) not null,
    architecture varchar(16) not null,
    channel varchar(16) not null,
    status varchar(24) not null default 'PUBLISHED',
    checksum varchar(64) not null,
    download_url varchar(1000) not null,
    release_notes text,
    published_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint cli_releases_platform_check check (
        platform in ('WINDOWS', 'LINUX', 'MACOS')
    ),
    constraint cli_releases_architecture_check check (
        architecture in ('X86_64', 'ARM64')
    ),
    constraint cli_releases_channel_check check (
        channel in ('ALPHA', 'BETA', 'STABLE', 'NEXT')
    ),
    constraint cli_releases_status_check check (
        status in ('PUBLISHED', 'DEPRECATED', 'WITHDRAWN')
    ),
    -- One build per version, platform, and architecture. A checksum shared across
    -- platforms would be meaningless: a developer verifying the Windows binary
    -- would be trusting the Linux one.
    constraint cli_releases_target_unique unique (version, platform, architecture),
    constraint cli_releases_checksum_check check (checksum ~ '^[0-9a-f]{64}$'),
    -- The URL is what a developer actually fetches. An https-only registry would
    -- push integrators toward plain http, which is a downgrade nobody chooses
    -- deliberately.
    constraint cli_releases_url_scheme_check check (download_url like 'https://%')
);

create index if not exists cli_releases_target_idx
    on cli_releases(platform, architecture, channel, version desc)
    where status = 'PUBLISHED';

-- A developer needs to know the platform's current API major to choose an
-- compatible SDK. Kept as a table rather than configuration so the history is
-- retained: an SDK published for v1 must stay explainable after v3 ships.
create table platform_api_versions (
    api_major integer primary key,
    status varchar(24) not null,
    released_at timestamptz not null,
    -- The date support ends. Mandatory for a deprecated version so developers get
    -- notice rather than a silent break.
    sunset_at timestamptz,
    notes text,
    constraint platform_api_versions_status_check check (
        status in ('CURRENT', 'DEPRECATED', 'RETIRED')
    ),
    constraint platform_api_versions_sunset_check check (
        status <> 'DEPRECATED' or sunset_at is not null
    ),
    constraint platform_api_versions_sunset_order_check check (
        sunset_at is null or sunset_at > released_at
    )
);

-- Append-only: a platform API major is a published fact, and rewriting it
-- retroactively would invalidate every compatibility claim made against it.
create or replace function prevent_platform_api_version_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'platform_api_versions is append-only; publish a new major instead';
end;
$$;

drop trigger if exists platform_api_versions_append_only on platform_api_versions;
create trigger platform_api_versions_append_only
    before update or delete on platform_api_versions
    for each row execute function prevent_platform_api_version_mutation();