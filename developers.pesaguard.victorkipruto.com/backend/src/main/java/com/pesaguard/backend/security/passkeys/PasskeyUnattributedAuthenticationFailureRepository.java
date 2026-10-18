package com.pesaguard.backend.security.passkeys;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PasskeyUnattributedAuthenticationFailureRepository
        extends JpaRepository<PasskeyUnattributedAuthenticationFailure, UUID> {
}
