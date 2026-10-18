package com.pesaguard.backend.member.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountDataPurgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountDataPurgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean purge(UUID userId) {
        Boolean purged = jdbcTemplate.queryForObject(
                "select public.purge_account_data(?)", Boolean.class, userId);
        return Boolean.TRUE.equals(purged);
    }
}
