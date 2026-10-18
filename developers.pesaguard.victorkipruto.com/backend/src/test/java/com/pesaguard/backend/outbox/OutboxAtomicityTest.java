package com.pesaguard.backend.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pesaguard.backend.outbox.application.EventPublisher;
import com.pesaguard.backend.outbox.application.OutboxRelay;
import com.pesaguard.backend.outbox.application.OutboxService;
import com.pesaguard.backend.outbox.domain.OutboxEvent;
import com.pesaguard.backend.outbox.domain.OutboxStatus;
import com.pesaguard.backend.outbox.infrastructure.OutboxEventRepository;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the outbox against a real PostgreSQL.
 *
 * <p>The property under test is atomicity: a domain change and the event
 * announcing it must commit together or not at all. That guarantee lives in the
 * database, so mocks cannot demonstrate it. This is the reason the suite exists.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
class OutboxAtomicityTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-bookworm");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pesaguard.security.credential-hmac-key",
                () -> base64("integration-credential-key-32-bytes!!"));
        registry.add("pesaguard.security.audit-hmac-key",
                () -> base64("integration-audit-key-32-bytes!!!!!!"));
    }

    /** Stands in for the broker, capturing what a real one would have received. */
    static final class CapturingPublisher implements EventPublisher {

        final AtomicInteger attempts = new AtomicInteger();
        final List<UUID> publishedEventIds = new ArrayList<>();
        boolean shouldFail = false;

        @Override
        public void publish(OutboxEvent event) {
            attempts.incrementAndGet();
            if (shouldFail) {
                throw new EventPublishException("broker unavailable");
            }
            publishedEventIds.add(event.getEventId());
        }
    }

    @TestConfiguration
    static class PublisherConfig {

        @Bean
        @Primary
        CapturingPublisher capturingPublisher() {
            return new CapturingPublisher();
        }
    }

    @Autowired
    OutboxService outbox;

    @Autowired
    OutboxEventRepository repository;

    @Autowired
    CapturingPublisher publisher;

    @Autowired
    OutboxRelay relay;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    com.pesaguard.backend.member.infrastructure.UserAccountRepository accounts;

    /** Sets {@code email_verified_at} directly; see {@link #registerAndGetToken}. */
    private void confirmEmailDirectly(String email) {
        accounts.findByEmail(email).ifPresent(account -> {
            account.verifyEmail(java.time.Instant.now());
            accounts.saveAndFlush(account);
        });
    }

    /**
     * Registration now sends a verification email, and a real sender would try to
     * reach an SMTP host that does not exist in a test run. These tests only need
     * the resulting session, not the message, so the send is absorbed.
     */
    @MockitoBean
    org.springframework.mail.javamail.JavaMailSender mailSender;

    private UUID organizationId;
    private UUID projectId;

    @BeforeEach
    void setUp() throws Exception {
        publisher.shouldFail = false;
        // Real tenant rows, because outbox_events holds foreign keys to
        // organizations and projects. Random identifiers would be refused by the
        // database, which is the constraint working correctly rather than a
        // problem to route around.
        String token = registerAndGetToken("test-" + UUID.randomUUID() + "@example.com");
        organizationId = UUID.fromString(organizationIdOf(token));
        String created = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Outbox Project\",\"slug\":\"outbox-"
                                + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        projectId = UUID.fromString(field(created, "id"));
    }

    /**
     * Registers, confirms, then signs in.
     *
     * <p>Registration returns only the address and whether verification is
     * outstanding -- no session -- so the token these tests need comes from a
     * follow-up sign-in. The address is confirmed directly in the database because
     * this suite is about outbox atomicity, not the verification flow, and the
     * token it would need exists only in an email body.
     */
    private String registerAndGetToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\","
                                + "\"password\":\"correct horse battery staple\","
                                + "\"displayName\":\"Outbox Tester\","
                                + "\"organizationName\":\"Outbox Org\","
                                + "\"termsAccepted\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        confirmEmailDirectly(email);

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\","
                                + "\"password\":\"correct horse battery staple\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        return field(body, "accessToken");
    }

    private String organizationIdOf(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return field(body, "organizationId");
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private OutboxEvent recordEvent() {
        return outbox.recordTenantEvent("developer.project.created",
                "{\"projectId\":\"" + projectId + "\"}",
                organizationId, projectId, "corr-1", "trace-1");
    }
    @Test
    void anEventIsReadableFromTheDatabaseBeforeAnythingIsPublished() {
        OutboxEvent recorded = recordEvent();

        OutboxEvent reloaded = repository.findById(recorded.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getEventType()).isEqualTo("developer.project.created");
        assertThat(reloaded.getOrganizationId()).isEqualTo(organizationId);
        // Partitioned by organization so one customer's events stay ordered.
        assertThat(reloaded.getPartitionKey()).isEqualTo(organizationId.toString());
        assertThat(reloaded.getPayload()).contains(projectId.toString());
    }

    @Test
    void theEventSurvivesEvenIfThePublisherNeverRuns() {
        OutboxEvent recorded = recordEvent();

        // Nothing published yet: the row is committed and durable. A crash here
        // loses no event, which is the entire point of the pattern.
        assertThat(repository.findById(recorded.getId())).isPresent();
        assertThat(publisher.attempts.get()).isZero();
    }

    @Test
    void aRolledBackTransactionLeavesNoEventBehind() {
        long before = repository.count();

        try {
            outbox.recordThenFail(organizationId, projectId);
        } catch (IllegalStateException expected) {
            // the deliberate rollback
        }

        // A change that did not commit must not have produced an event, or
        // consumers would act on something that never happened.
        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    void drainingMarksTheEventPublishedAndStampsTheTime() {
        OutboxEvent recorded = recordEvent();

        // The batch total depends on rows left by earlier tests, so the assertion
        // is about this event's own state, not about how many the relay saw.
        relay.publishOne(recorded.getId());

        OutboxEvent reloaded = repository.findById(recorded.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();
        assertThat(publisher.publishedEventIds).contains(recorded.getEventId());
    }

    @Test
    void aSecondDrainDoesNotRepublishAnAlreadyDeliveredEvent() {
        OutboxEvent recorded = recordEvent();
        relay.publishOne(recorded.getId());

        // Republishing would duplicate the message for every subscriber.
        assertThat(relay.publishOne(recorded.getId())).isFalse();
        int occurrences = 0;
        for (UUID seen : publisher.publishedEventIds) {
            if (seen.equals(recorded.getEventId())) {
                occurrences++;
            }
        }
        assertThat(occurrences).isOne();
    }

    @Test
    void aBrokerFailureLeavesTheEventPendingRatherThanLosingIt() {
        OutboxEvent recorded = recordEvent();
        publisher.shouldFail = true;

        assertThat(relay.drain()).isZero();

        OutboxEvent reloaded = repository.findById(recorded.getId()).orElseThrow();
        // Still retryable, with the failure recorded and a backoff scheduled.
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isOne();
        // Only the exception type is stored: a broker message can carry payload
        // or connection detail that must not land in the outbox table.
        assertThat(reloaded.getLastError()).isEqualTo("EventPublishException");
        assertThat(reloaded.getNextAttemptAt()).isAfter(reloaded.getCreatedAt());
    }

    @Test
    void anEventIsDeadLetteredAfterTheAttemptBudgetIsExhausted() {
        OutboxEvent recorded = recordEvent();
        publisher.shouldFail = true;
        // A budget of two makes exhaustion reachable without eight slow retries.
        OutboxRelay smallBudget = new OutboxRelay(repository, providerFor(publisher), 2, 10);

        for (int attempt = 0; attempt < 4; attempt++) {
            smallBudget.publishOne(recorded.getId());
        }

        OutboxEvent reloaded = repository.findById(recorded.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
        // The payload is retained: a dead letter nobody can read cannot be replayed.
        assertThat(reloaded.getPayload()).contains(projectId.toString());
    }

    private static org.springframework.beans.factory.ObjectProvider<EventPublisher> providerFor(EventPublisher publisher) {
        org.springframework.beans.factory.support.StaticListableBeanFactory factory =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("publisher", publisher);
        return factory.getBeanProvider(EventPublisher.class);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
