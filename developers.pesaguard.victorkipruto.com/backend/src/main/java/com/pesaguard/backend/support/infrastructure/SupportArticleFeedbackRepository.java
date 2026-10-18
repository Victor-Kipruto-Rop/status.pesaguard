package com.pesaguard.backend.support.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.support.domain.SupportArticleFeedback;

public interface SupportArticleFeedbackRepository extends JpaRepository<SupportArticleFeedback, UUID> {

    Optional<SupportArticleFeedback> findByArticleIdAndUserId(UUID articleId, UUID userId);
}
