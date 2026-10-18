package com.pesaguard.backend.oauth.api;

import com.pesaguard.backend.oauth.api.ApplicationView;

/** The one and only response that carries the client secret. */
public record CreatedApplicationView(ApplicationView application, String clientSecret) {
}