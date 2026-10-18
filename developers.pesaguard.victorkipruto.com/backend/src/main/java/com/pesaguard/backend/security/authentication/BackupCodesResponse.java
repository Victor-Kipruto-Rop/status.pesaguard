package com.pesaguard.backend.security.authentication;

import java.util.List;

/**
 * Recovery codes, returned exactly once.
 *
 * <p>Only SHA-256 digests are stored, so these cannot be shown again. A user who
 * loses them must re-enrol rather than ask the platform for a copy — which is the
 * intended behaviour: a support agent who could reissue them could also read
 * them.
 */
public record BackupCodesResponse(List<String> backupCodes, AuthenticationResponse session) {

    public BackupCodesResponse(List<String> backupCodes) {
        this(backupCodes, null);
    }
}