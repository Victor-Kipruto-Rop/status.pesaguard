package com.pesaguard.backend.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceConflictException extends BusinessException {

    public ResourceConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
