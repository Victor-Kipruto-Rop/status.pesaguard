package com.pesaguard.backend.security.authentication;

import org.springframework.http.HttpStatus;

import com.pesaguard.backend.common.exception.BusinessException;

public class LoginEmailMfaInvalidException extends BusinessException {

    public LoginEmailMfaInvalidException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
