package com.pesaguard.backend.environment.domain;

/** Operations that can be granted per environment through an access policy. */
public enum EnvironmentPermission {
    READ,
    WRITE,
    DEPLOY,
    ROTATE_CREDENTIALS,
    MANAGE_POLICIES
}