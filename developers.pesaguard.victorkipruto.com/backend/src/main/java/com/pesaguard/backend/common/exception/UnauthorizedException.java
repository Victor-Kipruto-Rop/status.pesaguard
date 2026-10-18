package com.pesaguard.backend.common.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
