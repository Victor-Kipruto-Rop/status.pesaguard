package com.pesaguard.backend.common.exception;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * Raised when an account belongs to more than one organization, so a caller must
 * choose one before a session can be issued.
 *
 * <p>Carries the organizations the caller may choose from. That list is the
 * whole point of the exception: a 409 that says only "pick one" leaves the
 * client with nothing to pick from, so the user is either stuck or the portal
 * has to guess. Both are worse than being told which options exist.
 *
 * <p>Only memberships this account actually holds are included, and the list is
 * built from the authentication attempt that produced it. It therefore reveals
 * nothing about organizations the caller is not a member of — the same set they
 * could already enumerate by signing in to each one.
 */
public class OrganizationSelectionException extends BusinessException {

    /**
     * One selectable organization.
     *
     * <p>Name and slug are included because a list of bare UUIDs is not
     * recognisable to the person choosing, and a chooser that cannot be read
     * cannot be used.
     *
     * @param id organization id to pass as {@code organizationId} on the next sign-in
     * @param name display name
     * @param slug stable URL/CLI identifier
     * @param role the caller's role in that organization
     */
    public record Selectable(UUID id, String name, String slug, String role) {
    }

    private final List<Selectable> selectable;

    public OrganizationSelectionException(List<Selectable> selectable) {
        super(HttpStatus.CONFLICT, "ORGANIZATION_SELECTION_REQUIRED",
                "This account belongs to more than one organization. Select the organization to sign in to.");
        this.selectable = selectable == null ? List.of() : List.copyOf(selectable);
    }

    /** The organizations this account may sign in to. Never null, never empty here. */
    public List<Selectable> selectable() {
        return selectable;
    }
}