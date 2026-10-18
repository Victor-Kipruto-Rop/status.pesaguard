package com.pesaguard.backend.security.authentication;

import java.time.Clock;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class UsernameService {

    private final UserAccountRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final UsernamePolicy usernamePolicy;

    public UsernameService(
            UserAccountRepository userRepository,
            AuditService auditService,
            Clock clock,
            UsernamePolicy usernamePolicy) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.usernamePolicy = usernamePolicy;
    }

    @Transactional(readOnly = true)
    public UsernameResponse get(AuthenticatedUser principal) {
        return new UsernameResponse(requireUser(principal).getUsername());
    }

    @Transactional
    public UsernameResponse update(AuthenticatedUser principal, UpdateUsernameRequest request) {
        UserAccount user = requireUser(principal);
        String username = usernamePolicy.validate(request.username());
        if (!username.equals(user.getUsername())
                && userRepository.existsByUsernameIgnoreCaseAndIdNot(username, user.getId())) {
            throw new ResourceConflictException("USERNAME_ALREADY_TAKEN",
                    "That username is already in use. Choose another one.");
        }

        String previous = user.getUsername();
        if (!username.equals(previous)) {
            user.updateUsername(username, clock.instant());
            userRepository.saveAndFlush(user);
            auditService.append(
                    principal.organizationId(), user.getId(), "account.username.updated",
                    "user", user.getId().toString(), RequestContext.currentRequestId(),
                    Map.of("previousUsername", previous, "username", username));
        }
        return new UsernameResponse(user.getUsername());
    }

    private UserAccount requireUser(AuthenticatedUser principal) {
        return userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User"));
    }
}
