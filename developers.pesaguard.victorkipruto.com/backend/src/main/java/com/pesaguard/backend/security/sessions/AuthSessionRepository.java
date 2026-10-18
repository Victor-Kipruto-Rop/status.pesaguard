package com.pesaguard.backend.security.sessions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Query("""
            select session.membership.user.id as userId, max(session.lastSeenAt) as lastActivityAt
            from AuthSession session
            where session.membership.organization.id = :organizationId
              and session.membership.user.id in :userIds
            group by session.membership.user.id
            """)
    List<MemberLastActivityProjection> findLatestActivityForOrganizationMembers(
            @Param("organizationId") UUID organizationId, @Param("userIds") List<UUID> userIds);

    @Query("select max(session.lastSeenAt) from AuthSession session "
            + "where session.membership.user.id = :userId")
    Instant findLatestActivityByUserId(@Param("userId") UUID userId);

    @Query("""
            select session from AuthSession session
            where session.membership.user.id = :userId
              and session.deviceLabel is not null
            order by session.lastSeenAt desc
            """)
    List<AuthSession> findRecentDeviceSessionsByUserId(
            @Param("userId") UUID userId, Pageable pageable);

    @Modifying
    @Query("""
            update AuthSession session set session.lastSeenAt = :now
            where session.id = :sessionId
              and session.revokedAt is null
              and session.expiresAt > :now
            """)
    int recordActivity(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    @Query("""
            select session from AuthSession session
            join fetch session.membership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where session.tokenHash = :tokenHash
              and session.revokedAt is null
              and membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE
              and organization.status = com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE
              and session.expiresAt > :now
            """)
    Optional<AuthSession> findActiveByTokenHash(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now);

    Optional<AuthSession> findByIdAndMembershipOrganizationId(UUID id, UUID organizationId);

    /**
     * A session looked up by owner as well as id.
     *
     * <p>Used wherever a caller submits a session id. Constraining by user in the
     * query is what makes "end session X" safe: without it, a user could submit
     * another user's session id and terminate their session.
     */
    Optional<AuthSession> findByIdAndMembershipUserId(UUID id, UUID userId);

    /**
     * Live sessions in an organization, so their access tokens can be denylisted.
     *
     * <p>The bulk {@code revokeActiveBy*} updates cannot return the ids they
     * touched, and a signed access token keeps working until it expires unless
     * denylisted. These reads exist to close that gap; they run only on
     * revocation paths, never per request.
     */
    @Query("""
            select session from AuthSession session
            where session.membership.organization.id = :organizationId
              and session.revokedAt is null
            """)
    List<AuthSession> findLiveSessionsByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("""
            select session from AuthSession session
            where session.membership.id = :membershipId and session.revokedAt is null
            """)
    List<AuthSession> findLiveSessionsByMembershipId(@Param("membershipId") UUID membershipId);

    @Query("""
            select session from AuthSession session
            where session.membership.user.id = :userId and session.revokedAt is null
            """)
    List<AuthSession> findLiveSessionsByUserId(@Param("userId") UUID userId);

    @Query("""
            select session from AuthSession session
            where session.membership.user.id = :userId and session.revokedAt is null
              and (:exceptSessionId is null or session.id <> :exceptSessionId)
            """)
    List<AuthSession> findLiveSessionsByUserIdExcept(
            @Param("userId") UUID userId,
            @Param("exceptSessionId") UUID exceptSessionId);

    @Query("""
            select count(session) from AuthSession session
            where session.membership.id = :membershipId
              and session.revokedAt is null
              and session.expiresAt > :now
            """)
    long countActiveByMembershipIdAndNow(@Param("membershipId") UUID membershipId, @Param("now") Instant now);

    @Query("""
            select session from AuthSession session
            where session.membership.id = :membershipId
              and session.revokedAt is null
              and session.expiresAt > :now
            order by session.lastSeenAt asc
            """)
    List<AuthSession> findActiveByMembershipIdOrderByLastSeenAtAsc(
            @Param("membershipId") UUID membershipId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.membership.organization.id = :organizationId "
            + "and session.revokedAt is null")
    int revokeActiveByOrganizationId(@Param("organizationId") UUID organizationId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.membership.id = :membershipId and session.revokedAt is null")
    int revokeActiveByMembershipId(@Param("membershipId") UUID membershipId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.membership.user.id = :userId and session.revokedAt is null")
    int revokeActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Every live session for a user except one.
     *
     * <p>Written so the exception is evaluated in SQL. Filtering in Java after
     * loading all of a user's sessions would work but would read rows the caller
     * has no intention of touching.
     */
    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.membership.user.id = :userId and session.revokedAt is null "
            + "and (:exceptSessionId is null or session.id <> :exceptSessionId)")
    int revokeActiveByUserIdExcept(@Param("userId") UUID userId,
            @Param("exceptSessionId") UUID exceptSessionId,
            @Param("now") Instant now);

    /** Live sessions for a user, most recently seen first, for the session list. */
    @Query("""
            select session from AuthSession session
            where session.membership.user.id = :userId
              and session.revokedAt is null
              and session.expiresAt > :now
            order by session.lastSeenAt desc
            """)
    List<AuthSession> findActiveByUserIdOrderByLastSeenAtDesc(
            @Param("userId") UUID userId, @Param("now") Instant now);
}
