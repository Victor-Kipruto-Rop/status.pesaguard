package com.pesaguard.backend.organization.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationRole;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where user.email = :email
              and membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE
              and organization.status in (
                  com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE,
                  com.pesaguard.backend.organization.domain.OrganizationStatus.SUSPENDED)
              and user.status in (
                  com.pesaguard.backend.member.domain.UserStatus.ACTIVE,
                  com.pesaguard.backend.member.domain.UserStatus.PENDING_DELETION)
            order by organization.name asc
            """)
    List<OrganizationMembership> findAllLoginableByEmail(@Param("email") String email);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where lower(user.username) = lower(:username)
              and membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE
              and organization.status in (
                  com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE,
                  com.pesaguard.backend.organization.domain.OrganizationStatus.SUSPENDED)
              and user.status in (
                  com.pesaguard.backend.member.domain.UserStatus.ACTIVE,
                  com.pesaguard.backend.member.domain.UserStatus.PENDING_DELETION)
            order by organization.name asc
            """)
    List<OrganizationMembership> findAllLoginableByUsername(@Param("username") String username);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where user.email = :email
              and membership.organization.id = :organizationId
              and membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE
              and organization.status in (
                  com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE,
                  com.pesaguard.backend.organization.domain.OrganizationStatus.SUSPENDED)
              and user.status in (
                  com.pesaguard.backend.member.domain.UserStatus.ACTIVE,
                  com.pesaguard.backend.member.domain.UserStatus.PENDING_DELETION)
            """)
    Optional<OrganizationMembership> findLoginableByEmailAndOrganizationId(
            @Param("email") String email, @Param("organizationId") UUID organizationId);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.organization.id = :organizationId
            order by membership.createdAt asc
            """)
    List<OrganizationMembership> findAllByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.id = :membershipId
              and membership.organization.id = :organizationId
            """)
    Optional<OrganizationMembership> findByIdAndOrganizationId(
            @Param("membershipId") UUID membershipId,
            @Param("organizationId") UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.id = :membershipId
              and membership.organization.id = :organizationId
            """)
    Optional<OrganizationMembership> findByIdAndOrganizationIdForUpdate(
            @Param("membershipId") UUID membershipId,
            @Param("organizationId") UUID organizationId);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.organization.id = :organizationId
              and membership.user.id = :userId
            """)
    Optional<OrganizationMembership> findByOrganizationIdAndUserId(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId);

    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.user.id = :userId
            order by organization.name asc
            """)
    List<OrganizationMembership> findAllByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.user.id = :userId
            order by organization.name asc
            """)
    List<OrganizationMembership> findAllByUserIdForUpdate(@Param("userId") UUID userId);

    boolean existsByUserIdAndRole(UUID userId, OrganizationRole role);

    default List<OrganizationMembership> findAllActiveByEmail(String email) {
        return findAllLoginableByEmail(email).stream()
                .filter(membership -> membership.getOrganization().isActive())
                .toList();
    }

    default List<OrganizationMembership> findAllActiveByUsername(String username) {
        return findAllLoginableByUsername(username).stream()
                .filter(membership -> membership.getOrganization().isActive())
                .toList();
    }

    /**
     * Every workspace a user can currently act in.
     *
     * <p>Drives the workspace switcher, so it filters on membership status and an
     * active organization rather than returning everything ever associated with
     * the address: a revoked member must not appear to retain access.
     */
    @Query("""
            select membership from OrganizationMembership membership
            join fetch membership.organization organization
            join fetch membership.user user
            where membership.user.id = :userId
              and membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE
              and organization.status not in (
                  com.pesaguard.backend.organization.domain.OrganizationStatus.DELETED,
                  com.pesaguard.backend.organization.domain.OrganizationStatus.DISABLED)
              and user.status in (
                  com.pesaguard.backend.member.domain.UserStatus.ACTIVE,
                  com.pesaguard.backend.member.domain.UserStatus.PENDING_DELETION)
            order by organization.name asc
            """)
    List<OrganizationMembership> findAllActiveByUserId(@Param("userId") UUID userId);

    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    @Query("""
            select count(membership) > 0 from OrganizationMembership membership
            where membership.organization.id = :organizationId
              and lower(membership.user.email) = lower(:email)
            """)
    boolean existsByOrganizationIdAndUserEmailIgnoreCase(
            @Param("organizationId") UUID organizationId, @Param("email") String email);
}
