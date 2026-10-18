package com.pesaguard.backend.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", resource + " was not found.");
    }
}
