package com.pesaguard.backend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import jakarta.mail.Part;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.pesaguard.backend.organization.application.InvitationEmailDeliveryScheduler;
import com.pesaguard.backend.member.infrastructure.AccountDataPurgeRepository;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeveloperPlatformIntegrationTest {
    private static final String TEST_PASSWORD = "Ripple!Cedar-29";

    private static final String CREDENTIAL_KEY = base64("integration-credential-key-32-bytes!!");
    private static final String AUDIT_KEY = base64("integration-audit-key-32-bytes!!!!!!");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-bookworm");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pesaguard.security.credential-hmac-key", () -> CREDENTIAL_KEY);
        registry.add("pesaguard.security.audit-hmac-key", () -> AUDIT_KEY);
        registry.add("pesaguard.security.allowed-origins", () -> "https://developers.pesaguard.co.ke");
        registry.add("pesaguard.security.registration-enabled", () -> "true");
        registry.add("pesaguard.runtime.maintenance-mode", () -> "false");
        registry.add("pesaguard.organization.invitation-email-dispatch-ms", () -> "3600000");
        registry.add("pesaguard.ratelimit.store", () -> "in-memory");
    }

    @Test
    void anUnconfirmedAddressCannotSignIn() throws Exception {
        String email = uniqueEmail();
        registerUnverified(email);

        // Sign-in is refused until the address is proven. The response must name
        // the reason so the portal can offer resend rather than showing a generic failure.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));

        confirmEmail(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_EMAIL_MFA_REQUIRED"));
    }

    @Test
    void suggestionFeedbackApiIsRemoved() throws Exception {
        String token = register(uniqueEmail());
        mockMvc.perform(get("/api/v1/feedback")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "0")
                        .param("pageSize", "20"))
                .andExpect(status().isNotFound());
    }

    @Test
    void accountPurgeRemovesSharedWorkspaceHistoryButKeepsOtherMemberAccount() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UserAccount owner = UserAccount.create("owner-" + suffix + "@example.com",
                "owner" + suffix.substring(0, 12), "Workspace owner", "unusable-password-hash");
        UserAccount member = UserAccount.create("member-" + suffix + "@example.com",
                "member" + suffix.substring(0, 12), "Workspace member", "unusable-password-hash");
        userAccountRepository.saveAndFlush(owner);
        userAccountRepository.saveAndFlush(member);
        Organization organization = Organization.create(
                "Purge workspace", "purge-" + suffix, owner.getId(), java.time.Instant.now());
        organizationRepository.saveAndFlush(organization);
        organizationMembershipRepository.saveAndFlush(OrganizationMembership.owner(organization, owner));
        organizationMembershipRepository.saveAndFlush(
                OrganizationMembership.member(organization, member,
                        com.pesaguard.backend.organization.domain.OrganizationRole.DEVELOPER));
        jdbcTemplate.update("""
                insert into audit_events (
                    id, organization_id, sequence_number, actor_user_id, action, resource_type,
                    resource_id, request_id, correlation_id, metadata, previous_hash, event_hash, created_at
                ) values (?, ?, 1, ?, 'test.event', 'organization', ?, ?, ?, '{}', ?, ?, now())
                """,
                UUID.randomUUID(), organization.getId(), owner.getId(), organization.getId().toString(),
                UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64), "1".repeat(64));
        assertThat(accountDataPurgeRepository.purge(owner.getId())).isFalse();
        owner.requestDeletion(java.time.Instant.now().minus(java.time.Duration.ofDays(31)));
        userAccountRepository.saveAndFlush(owner);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where organization_id = ?",
                Long.class, organization.getId())).isPositive();

        assertThat(accountDataPurgeRepository.purge(owner.getId())).isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?", Long.class, owner.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from organizations where id = ?", Long.class, organization.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from organization_memberships where organization_id = ?",
                Long.class, organization.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where organization_id = ?",
                Long.class, organization.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?", Long.class, member.getId())).isEqualTo(1);
    }

    @Test
    void registrationAcceptsAndPersistsUsernameAndPhoneNumber() throws Exception {
        String email = uniqueEmail();
        String username = "developer" + UUID.randomUUID().toString().substring(0, 8).replaceAll("[0-9]", "a");
        String password = TEST_PASSWORD;
        String phoneNumber = "+254712345678";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\","
                                + "\"displayName\":\"Test User\",\"organizationName\":\"Test Organization\","
                                + "\"termsAccepted\":true,"
                                + "\"username\":\"" + username + "\",\"phoneNumber\":\"" + phoneNumber + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value(username));

        assertThat(jdbcTemplate.queryForObject(
                "select phone_number from users where email = ?", String.class, email))
                .isEqualTo(phoneNumber);
    }

    @Test
    void registrationRejectsPhoneNumberAlreadyUsedByAnotherAccount() throws Exception {
        String phoneNumber = "+2547" + String.format(java.util.Locale.ROOT, "%08d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L));
        String firstEmail = uniqueEmail();
        String secondEmail = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + firstEmail + "\",\"password\":\"" + TEST_PASSWORD + "\","
                                + "\"displayName\":\"Test User\",\"organizationName\":\"First Organization\","
                                + "\"termsAccepted\":true,\"phoneNumber\":\"" + phoneNumber + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + secondEmail + "\",\"password\":\"" + TEST_PASSWORD + "\","
                                + "\"displayName\":\"Test User\",\"organizationName\":\"Second Organization\","
                                + "\"termsAccepted\":true,\"phoneNumber\":\"" + phoneNumber + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    void registrationGeneratesInternalUsernameAndCreatesNamedOrganization() throws Exception {
        String email = uniqueEmail();
        String organizationName = "North Star Labs";
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\","
                                + "\"displayName\":\"Test User\",\"organizationName\":\"" + organizationName + "\","
                                + "\"termsAccepted\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationName").value(organizationName))
                .andExpect(jsonPath("$.data.username").value(org.hamcrest.Matchers.matchesRegex("dev[a-p]{22}")))
                .andReturn().getResponse().getContentAsString();

        String username = jsonValue(response, "username");
        assertThat(jdbcTemplate.queryForObject(
                "select username from users where email = ?", String.class, email))
                .isEqualTo(username);
        assertThat(jdbcTemplate.queryForObject(
                "select name from organizations where id = (select organization_id from organization_memberships "
                        + "where user_id = (select id from users where email = ?) limit 1)",
                String.class, email))
                .isEqualTo(organizationName);
    }

    @Test
    void emailVerificationAfterUnverifiedLoginCompletesInitialSession() throws Exception {
        String email = uniqueEmail();
        registerUnverified(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));

        String code = latestEmailCode(email);
        String response = mockMvc.perform(post("/api/v1/auth/verify-email/complete-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code
                                + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + jsonValue(response, "accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value(email));
    }

    @Test
    void registrationVerificationCompletesInitialSessionButLaterSignInsStillRequireMfa() throws Exception {
        String email = uniqueEmail();
        registerUnverified(email);
        String code = latestEmailCode(email);

        String response = mockMvc.perform(post("/api/v1/auth/verify-email/complete-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code
                                + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String accessToken = jsonValue(response, "accessToken");

        mockMvc.perform(get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value(email));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_EMAIL_MFA_REQUIRED"));
    }


    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AccountDataPurgeRepository accountDataPurgeRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    OrganizationMembershipRepository organizationMembershipRepository;

    @Autowired
    InvitationEmailDeliveryScheduler invitationEmailDeliveryScheduler;

    /**
     * Captured rather than sent.
     *
     * <p>Sign-in now requires a confirmed address, so most of these tests need a
     * verified account. The token only exists in the email body, and the database
     * keeps a one-way hash of it, so the message has to be intercepted to complete
     * the flow. Replaces the sender rather than the service: the verification
     * endpoint under test is still the real one, driven by a real token.
     */
    @MockitoBean
    JavaMailSender mailSender;

    private final List<MimeMessage> sentEmails = new ArrayList<>();

    @BeforeEach
    void captureEmailsWithoutSending() {
        sentEmails.clear();
        when(mailSender.createMimeMessage()).thenAnswer(
                invocation -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(invocation -> {
            sentEmails.add(invocation.getArgument(0));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void registrationCreatesTenantScopedResourcesAndOneTimeApiKey() throws Exception {
        String email = uniqueEmail();
        String token = register(email);
        String projectId = createProject(token);
        String environmentId = createEnvironment(token, projectId);

        String idempotencyKey = "integration-" + UUID.randomUUID();
        String keyResponse = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/environments/{environmentId}/api-keys", projectId, environmentId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"local-worker\",\"scopes\":[\"developer:read\"],\"expiresIn\":\"PT24H\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.key", containsString("pgk_")))
                .andExpect(jsonPath("$.data.baseUrl").value(
                        "https://sandbox-api.pesaguard.co.ke"))
                .andReturn().getResponse().getContentAsString();
        String keyId = jsonValue(keyResponse, "id");

        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/environments/{environmentId}/api-keys", projectId, environmentId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"local-worker\",\"scopes\":[\"developer:read\"],\"expiresIn\":\"PT24H\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.baseUrl").value(
                        "https://sandbox-api.pesaguard.co.ke"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/environments/{environmentId}/api-keys", projectId, environmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].key").doesNotExist())
                .andExpect(jsonPath("$.data[0].prefix").exists());
        mockMvc.perform(delete("/api/v1/projects/{projectId}/environments/{environmentId}/api-keys/{keyId}",
                        projectId, environmentId, keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/audit-events/verify").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));
    }

    @Test
    void registrationCreatesMyWorkspaceAndUsernameSupportsSignInAndUpdates() throws Exception {
        String email = uniqueEmail();
        registerUnverified(email);

        String username = jdbcTemplate.queryForObject(
                "select username from users where email = ?", String.class, email);
        String workspaceName = jdbcTemplate.queryForObject(
                "select name from organizations where owner_user_id = (select id from users where email = ?)",
                String.class, email);
        assertThat(username).isNotBlank();
        assertThat(workspaceName).isEqualTo("Acme test-" + email.substring("test-".length(), email.indexOf('@')));

        confirmEmail(email);
        String token = login(username);
        mockMvc.perform(get("/api/v1/auth/username").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));

        String updatedUsername = "newdeveloper"
                + UUID.randomUUID().toString().substring(0, 8).replaceAll("[0-9]", "a");
        mockMvc.perform(patch("/api/v1/auth/username")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + updatedUsername + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(updatedUsername));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + updatedUsername
                                + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_EMAIL_MFA_REQUIRED"));
    }

    @Test
    void crossTenantProjectLookupIsNotFound() throws Exception {
        String firstToken = register(uniqueEmail());
        String projectId = createProject(firstToken);
        String secondToken = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void logoutInvalidatesBearerSession() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void organizationLifecycleIsTenantScopedAndAudited() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.type").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.slug").exists());

        mockMvc.perform(patch("/api/v1/organization")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Org\",\"type\":\"ENTERPRISE\",\"metadata\":{\"region\":\"ke\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed Org"))
                .andExpect(jsonPath("$.data.metadata.region").value("ke"));

        mockMvc.perform(post("/api/v1/organization/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"verification-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationReference").value("verification-1"));

        mockMvc.perform(post("/api/v1/organization/suspend").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invitationsGrantMembershipOnceAndCannotBeReused() throws Exception {
        String ownerToken = register(uniqueEmail());
        String inviteeEmail = uniqueEmail();
        String invitationToken = createInvitation(ownerToken, inviteeEmail);

        mockMvc.perform(post("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", "duplicate-test-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_ALREADY_PENDING"));

        mockMvc.perform(get("/api/v1/invitations/{token}/preview", invitationToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.invitedEmailHint").value(containsString("@example.com")));

        String issuedToken = acceptInvitation(invitationToken, inviteeEmail);

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", invitationToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/invitations/{token}/accept", invitationToken)
                        .header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("INVITATION_NOT_ACTIVE"));

        mockMvc.perform(get("/api/v1/organization/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        mockMvc.perform(get("/api/v1/organization/members").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        mockMvc.perform(get("/api/v1/organization/security-settings").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void invitationAcceptanceAndDeclineRequireTheVerifiedInvitedIdentity() throws Exception {
        String ownerToken = register(uniqueEmail());
        String invitedEmail = uniqueEmail();
        String invitationToken = createInvitation(ownerToken, invitedEmail);
        String otherAccountToken = register(uniqueEmail());

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", invitationToken)
                        .header("Authorization", "Bearer " + otherAccountToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVITATION_EMAIL_MISMATCH"));
        mockMvc.perform(post("/api/v1/invitations/{token}/decline", invitationToken)
                        .header("Authorization", "Bearer " + otherAccountToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVITATION_EMAIL_MISMATCH"));

        String invitedAccountToken = register(invitedEmail);
        mockMvc.perform(post("/api/v1/invitations/{token}/decline", invitationToken)
                        .header("Authorization", "Bearer " + invitedAccountToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("DECLINED"))
                .andExpect(jsonPath("$.data[0].declinedAt").isNotEmpty());
    }

    @Test
    void invitationCreationIsIdempotentAndRejectsKeyReuseForDifferentInput() throws Exception {
        String ownerToken = register(uniqueEmail());
        String invitedEmail = uniqueEmail();
        String key = "invite-idempotency-" + UUID.randomUUID();

        String firstResponse = mockMvc.perform(post("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitedEmail + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String replayResponse = mockMvc.perform(post("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitedEmail + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonValue(firstResponse, "id")).isEqualTo(jsonValue(replayResponse, "id"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from organization_invitation_email_deliveries where recipient_email = ?",
                Integer.class, invitedEmail)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void organizationScopedInvitationRoutesSupportDetailAndCancellation() throws Exception {
        String ownerToken = register(uniqueEmail());
        String organizationId = organizationId(ownerToken);
        String invitedEmail = uniqueEmail();
        String invitationId = jsonValue(mockMvc.perform(post(
                        "/api/v1/organizations/{organizationId}/invitations", organizationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", "scoped-invite-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitedEmail + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/v1/organizations/{organizationId}/invitations", organizationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(invitationId));
        mockMvc.perform(get("/api/v1/organizations/{organizationId}/invitations/{invitationId}",
                        organizationId, invitationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(invitedEmail));

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/invitations/{invitationId}/cancel",
                        organizationId, invitationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/organizations/{organizationId}/invitations/{invitationId}",
                        organizationId, invitationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/organizations/{organizationId}/invitations",
                        UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void memberSuspensionRevokesSessionsAndIsRecordedInHistory() throws Exception {
        String ownerToken = register(uniqueEmail());
        String inviteeEmail = uniqueEmail();
        String memberToken = acceptInvitation(createInvitation(ownerToken, inviteeEmail), inviteeEmail);
        String membershipId = developerMembershipId(ownerToken);

        mockMvc.perform(patch("/api/v1/organization/members/{membershipId}/status", membershipId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"policy review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        mockMvc.perform(get("/api/v1/auth/session").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/organization/members/{membershipId}/history", membershipId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                // Newest first (createdAt desc), so the suspension is index 0 and the
                // original acceptance is index 1.
                .andExpect(jsonPath("$.data[0].toStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.data[0].fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].reason").value("policy review"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + inviteeEmail + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void multiOrganizationAccountsUseLastWorkspaceByDefaultAndAcceptExplicitSelection() throws Exception {
        String email = uniqueEmail();
        String token = register(email);
        String firstOrganizationId = organizationId(token);
        String secondOrganizationId = jsonValue(mockMvc.perform(post("/api/v1/organization")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second Org\",\"type\":\"DEVELOPER\",\"metadata\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_EMAIL_MFA_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\","
                                + "\"organizationId\":\"" + secondOrganizationId + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_EMAIL_MFA_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\","
                                + "\"organizationId\":\"" + firstOrganizationId + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_EMAIL_MFA_REQUIRED"));
    }

    @Test
    void securitySettingsRejectUnsupportedProviders() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(put("/api/v1/organization/security-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedAuthMethods\":[\"PASSWORD\",\"OIDC\"],\"sessionTtlMinutes\":120,"
                                + "\"idleTimeoutMinutes\":60,\"maxSessions\":5,\"credentialMinLength\":14,"
                                + "\"credentialMaxLength\":72,\"mfaRequired\":false,\"ipAllowlist\":[],"
                                + "\"securityEventTypes\":[\"LOGIN_FAILURE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_AUTH_METHOD"));

        mockMvc.perform(put("/api/v1/organization/security-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedAuthMethods\":[\"PASSWORD\"],\"sessionTtlMinutes\":120,"
                                + "\"idleTimeoutMinutes\":60,\"maxSessions\":5,\"credentialMinLength\":14,"
                                + "\"credentialMaxLength\":72,\"mfaRequired\":true,\"ipAllowlist\":[],"
                                + "\"securityEventTypes\":[\"LOGIN_FAILURE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_SECURITY_POLICY"));

        mockMvc.perform(put("/api/v1/organization/security-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedAuthMethods\":[\"PASSWORD\"],\"sessionTtlMinutes\":120,"
                                + "\"idleTimeoutMinutes\":60,\"maxSessions\":5,\"credentialMinLength\":14,"
                                + "\"credentialMaxLength\":72,\"mfaRequired\":false,\"ipAllowlist\":[],"
                                + "\"securityEventTypes\":[\"LOGIN_FAILURE\",\"MEMBERSHIP_CHANGED\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionTtlMinutes").value(120))
                .andExpect(jsonPath("$.data.credentialMinLength").value(14));
    }

    @Test
    void membershipOperationsAreIsolatedPerTenant() throws Exception {
        String firstOwnerToken = register(uniqueEmail());
        String inviteeEmail = uniqueEmail();
        acceptInvitation(createInvitation(firstOwnerToken, inviteeEmail), inviteeEmail);
        String membershipId = developerMembershipId(firstOwnerToken);

        String secondOwnerToken = register(uniqueEmail());
        mockMvc.perform(patch("/api/v1/organization/members/{membershipId}/status", membershipId)
                        .header("Authorization", "Bearer " + secondOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVOKED\",\"reason\":\"cross tenant attempt\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/organization/members/{membershipId}/history", membershipId)
                        .header("Authorization", "Bearer " + secondOwnerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void repeatedInvalidLoginIsRateLimited() throws Exception {
        String email = uniqueEmail();
        String body = "{\"email\":\"" + email + "\",\"password\":\"wrong-password-value\"}";
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /** Registers, confirms the address, then signs in. Returns a usable token. */
    private String register(String email) throws Exception {
        registerUnverified(email);
        confirmEmail(email);
        return login(email);
    }

    /**
     * Registration returns no session.
     *
     * <p>The response reports the pending verification window, but never returns
     * the emailed OTP or an access token. Tests extract the one-time code from the
     * captured email to exercise the same public verification endpoint as a user.
     */
    private String registerUnverified(String email) throws Exception {
        String organization = "Acme " + email.substring(0, email.indexOf('@'));
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\","
                                + "\"displayName\":\"Test User\",\"organizationName\":\"" + organization + "\","
                                + "\"termsAccepted\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.username").isNotEmpty())
                .andExpect(jsonPath("$.data.verificationRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "email");
    }

    private String login(String email) throws Exception {
        String challengeResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_EMAIL_MFA_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        String challengeId = jsonValue(challengeResponse, "loginChallengeId");
        String code = latestEmailCode(email);
        String response = mockMvc.perform(post("/api/v1/auth/login/email-mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "accessToken");
    }

    /** Completes the real verification flow using the OTP from the captured email. */
    private void confirmEmail(String email) throws Exception {
        String code = latestEmailCode(email);

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    private String latestEmailCode(String email) {
        return sentEmails.stream()
                .filter(message -> isSentTo(message, email))
                .map(DeveloperPlatformIntegrationTest::plainTextContent)
                .filter(text -> java.util.regex.Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)").matcher(text).find())
                .map(text -> {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("(?<!\\d)(\\d{6})(?!\\d)").matcher(text);
                    if (!matcher.find()) throw new AssertionError("Email did not contain a six-digit code");
                    return matcher.group(1);
                })
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No matching verification email was sent to " + email));
    }

    private static boolean isSentTo(MimeMessage message, String email) {
        try {
            jakarta.mail.Address[] recipients = message.getRecipients(jakarta.mail.Message.RecipientType.TO);
            return recipients != null && java.util.Arrays.stream(recipients)
                    .anyMatch(recipient -> recipient.toString().toLowerCase(java.util.Locale.ROOT)
                            .contains(email.toLowerCase(java.util.Locale.ROOT)));
        } catch (jakarta.mail.MessagingException exception) {
            throw new AssertionError("Could not read captured email recipients", exception);
        }
    }

    private static String plainTextContent(MimeMessage message) {
        try {
            StringBuilder content = new StringBuilder();
            appendTextParts(message, content);
            return content.toString();
        } catch (Exception exception) {
            throw new AssertionError("Could not read captured email content", exception);
        }
    }

    private static void appendTextParts(Part part, StringBuilder content) throws Exception {
        if (part.isMimeType("text/*")) {
            content.append(part.getContent()).append('\n');
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                appendTextParts(multipart.getBodyPart(index), content);
            }
        }
    }

    private String createProject(String token) throws Exception {
        String slug = "project-" + UUID.randomUUID().toString().substring(0, 8);
        String response = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Payments\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "id");
    }

    private String createEnvironment(String token, String projectId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects/{projectId}/environments", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sandbox\",\"type\":\"SANDBOX\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseUrl").value(
                        "https://sandbox-api.pesaguard.co.ke"))
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "id");
    }

    private String createInvitation(String ownerToken, String inviteeEmail) throws Exception {
        mockMvc.perform(post("/api/v1/organization/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", "invite-create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.deliveryStatus").value("QUEUED"));
        invitationEmailDeliveryScheduler.deliverDue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from organization_invitation_email_deliveries "
                        + "where recipient_email = ? and status = 'SENT' and token_ciphertext is null",
                Integer.class, inviteeEmail)).isEqualTo(1);
        return invitationTokenFromEmail(inviteeEmail);
    }

    private String acceptInvitation(String invitationToken, String inviteeEmail) throws Exception {
        String inviteeToken = register(inviteeEmail);
        String response = mockMvc.perform(post("/api/v1/invitations/{token}/accept", invitationToken)
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String organizationId = jsonValue(response, "organizationId");
        String switchedSession = mockMvc.perform(post("/api/v1/auth/switch-workspace")
                        .header("Authorization", "Bearer " + inviteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":\"" + organizationId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(switchedSession, "accessToken");
    }

    private String invitationTokenFromEmail(String email) {
        return sentEmails.stream()
                .filter(message -> isSentTo(message, email))
                .map(DeveloperPlatformIntegrationTest::plainTextContent)
                .filter(text -> text.contains("/accept-invitation?token="))
                .map(text -> {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("/accept-invitation\\?token=([A-Za-z0-9_-]+)")
                            .matcher(text);
                    if (!matcher.find()) throw new AssertionError("Invitation email did not contain its private token link");
                    return matcher.group(1);
                })
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No invitation email was sent to " + email));
    }

    private String developerMembershipId(String ownerToken) throws Exception {
        String response = mockMvc.perform(get("/api/v1/organization/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int roleIndex = response.indexOf("\"role\":\"DEVELOPER\"");
        if (roleIndex < 0) {
            throw new AssertionError("No developer membership was created: " + response);
        }
        int marker = response.lastIndexOf("\"id\":\"", roleIndex);
        int start = marker + "\"id\":\"".length();
        return response.substring(start, response.indexOf('"', start));
    }

    private String organizationId(String token) throws Exception {
        String response = mockMvc.perform(get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "organizationId");
    }

    private static String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }

    private static String jsonValue(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
