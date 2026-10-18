package com.pesaguard.backend.status.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pesaguard.backend.config.DeveloperRuntimeProperties;
import com.pesaguard.backend.status.application.PlatformIncidentService;

class PlatformStatusControllerTest {

    @Test
    void reportsUnknownForComponentsWithoutHealthSignals() {
        DeveloperRuntimeProperties runtime = new DeveloperRuntimeProperties();
        PlatformIncidentService incidents = org.mockito.Mockito.mock(PlatformIncidentService.class);
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(incidents.publicActiveIncidents()).thenReturn(List.of());
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);

        var status = new PlatformStatusController(runtime, incidents, jdbcTemplate).status().data();

        assertThat(status.overallStatus()).isEqualTo("UNKNOWN");
        assertThat(status.components()).containsExactly(
                new PlatformStatusController.ComponentView("API", "OPERATIONAL"),
                new PlatformStatusController.ComponentView("DASHBOARD", "UNKNOWN"),
                new PlatformStatusController.ComponentView("AUTHENTICATION", "UNKNOWN"),
                new PlatformStatusController.ComponentView("WEBHOOKS", "UNKNOWN"));
    }

    @Test
    void reportsApiDegradedWhenDatabaseReadinessProbeFails() {
        DeveloperRuntimeProperties runtime = new DeveloperRuntimeProperties();
        PlatformIncidentService incidents = org.mockito.Mockito.mock(PlatformIncidentService.class);
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(incidents.publicActiveIncidents()).thenReturn(List.of());
        when(jdbcTemplate.queryForObject("select 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        var status = new PlatformStatusController(runtime, incidents, jdbcTemplate).status().data();

        assertThat(status.overallStatus()).isEqualTo("DEGRADED");
        assertThat(status.components().get(0))
                .isEqualTo(new PlatformStatusController.ComponentView("API", "DEGRADED"));
    }
}
