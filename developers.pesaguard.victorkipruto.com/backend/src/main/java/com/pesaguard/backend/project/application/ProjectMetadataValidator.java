package com.pesaguard.backend.project.application;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.organization.application.OrganizationMetadataValidator;

/**
 * Project metadata reuses the organization metadata rules: bounded depth,
 * bounded size, and rejection of credential-like keys. Configuration data must
 * never be smuggled through metadata.
 */
@Component
public class ProjectMetadataValidator {

    private final OrganizationMetadataValidator delegate;

    public ProjectMetadataValidator(OrganizationMetadataValidator delegate) {
        this.delegate = delegate;
    }

    public void validate(Map<String, Object> metadata) {
        delegate.validate(metadata);
    }
}