package com.pesaguard.backend.security.authentication;

@FunctionalInterface
public interface PasswordBreachChecker {

    boolean isCompromised(String password);
}
