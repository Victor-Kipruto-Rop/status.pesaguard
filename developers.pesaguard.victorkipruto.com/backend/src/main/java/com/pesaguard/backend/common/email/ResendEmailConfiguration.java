package com.pesaguard.backend.common.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(prefix = "pesaguard.email", name = "provider", havingValue = "resend")
public class ResendEmailConfiguration {

    @Bean
    JavaMailSender resendJavaMailSender(
            @Value("${RESEND_API_KEY:}") String apiKey,
            @Value("${pesaguard.identity.from-email}") String fromAddress,
            ObjectMapper objectMapper) {
        return new ResendJavaMailSender(apiKey, fromAddress, objectMapper);
    }
}
