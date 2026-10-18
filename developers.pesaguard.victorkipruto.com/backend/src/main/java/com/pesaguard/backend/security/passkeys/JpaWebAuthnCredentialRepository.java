package com.pesaguard.backend.security.passkeys;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;

@Component
public class JpaWebAuthnCredentialRepository implements CredentialRepository {

    private final PasskeyCredentialRepository repository;

    public JpaWebAuthnCredentialRepository(PasskeyCredentialRepository repository) {
        this.repository = repository;
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        UUID userId = parseUserId(username).orElse(null);
        if (userId == null) return Set.of();
        return repository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(credential -> PublicKeyCredentialDescriptor.builder()
                        .id(new ByteArray(credential.getCredentialId()))
                        .build())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return parseUserId(username)
                .filter(repository::existsByUserId)
                .map(JpaWebAuthnCredentialRepository::handleFor);
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        byte[] bytes = userHandle.getBytes();
        if (bytes.length != 16) return Optional.empty();
        UUID userId = uuidFrom(bytes);
        return repository.existsByUserId(userId) ? Optional.of(userId.toString()) : Optional.empty();
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return repository.findByCredentialId(credentialId.getBytes())
                .filter(credential -> MessageDigest.isEqual(credential.getUserHandle(), userHandle.getBytes()))
                .map(JpaWebAuthnCredentialRepository::registeredCredential);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return repository.findAllByCredentialId(credentialId.getBytes()).stream()
                .map(JpaWebAuthnCredentialRepository::registeredCredential)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static ByteArray handleFor(UUID userId) {
        return new ByteArray(ByteBuffer.allocate(16)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .array());
    }

    static UUID uuidFrom(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static Optional<UUID> parseUserId(String username) {
        try {
            return Optional.of(UUID.fromString(username));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static RegisteredCredential registeredCredential(PasskeyCredential credential) {
        return RegisteredCredential.builder()
                .credentialId(new ByteArray(credential.getCredentialId()))
                .userHandle(new ByteArray(credential.getUserHandle()))
                .publicKeyCose(new ByteArray(credential.getPublicKeyCose()))
                .signatureCount(credential.getSignatureCount())
                .backupEligible(credential.isBackupEligible())
                .backupState(credential.isBackedUp())
                .build();
    }
}
