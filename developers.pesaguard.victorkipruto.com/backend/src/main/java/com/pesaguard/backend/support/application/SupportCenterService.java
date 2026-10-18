package com.pesaguard.backend.support.application;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.support.api.SupportArticlePageView;
import com.pesaguard.backend.support.api.SupportArticleView;
import com.pesaguard.backend.support.api.SupportCategoryView;
import com.pesaguard.backend.support.api.SupportCenterView;
import com.pesaguard.backend.support.api.SupportSearchResult;
import com.pesaguard.backend.support.domain.SupportArticle;
import com.pesaguard.backend.support.domain.SupportArticleFeedback;
import com.pesaguard.backend.support.domain.SupportCategory;
import com.pesaguard.backend.support.infrastructure.SupportArticleFeedbackRepository;
import com.pesaguard.backend.support.infrastructure.SupportArticleRepository;
import com.pesaguard.backend.support.infrastructure.SupportCategoryRepository;
import com.pesaguard.backend.support.infrastructure.SupportTicketRepository;

@Service
public class SupportCenterService {

    private final SupportArticleRepository articleRepository;
    private final SupportCategoryRepository categoryRepository;
    private final SupportArticleFeedbackRepository feedbackRepository;
    private final SupportTicketRepository ticketRepository;
    private final Clock clock;

    public SupportCenterService(
            SupportArticleRepository articleRepository,
            SupportCategoryRepository categoryRepository,
            SupportArticleFeedbackRepository feedbackRepository,
            SupportTicketRepository ticketRepository,
            Clock clock) {
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.feedbackRepository = feedbackRepository;
        this.ticketRepository = ticketRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SupportCenterView overview() {
        return new SupportCenterView(
                "https://docs.pesaguard.co.ke",
                "https://status.pesaguard.co.ke",
                "https://github.com/Victor-Kipruto-Rop/status.pesaguard.victorkipruto.com/discussions",
                categories(),
                articleRepository.findTop5ByStatusOrderByPublishedAtDesc("PUBLISHED")
                        .stream().map(SupportArticleView::from).toList());
    }

    @Transactional(readOnly = true)
    public List<SupportCategoryView> categories() {
        return categoryRepository.findAllByOrderBySortOrderAsc()
                .stream().map(SupportCategoryView::from).toList();
    }

    @Transactional(readOnly = true)
    public SupportArticlePageView articles(String category, int page, int pageSize) {
        Page<SupportArticle> result = articleRepository.findPublished(
                category, PageRequest.of(page, pageSize));
        return new SupportArticlePageView(result.getContent().stream()
                .map(SupportArticleView::from).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public SupportArticlePageView searchArticles(String query, int page, int pageSize) {
        Page<SupportArticle> result = articleRepository.searchPublished(
                query, PageRequest.of(page, pageSize));
        return new SupportArticlePageView(result.getContent().stream()
                .map(SupportArticleView::from).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public SupportArticleView article(String slug) {
        return articleRepository.findBySlugAndStatus(slug, "PUBLISHED")
                .map(SupportArticleView::from)
                .orElseThrow(() -> new ResourceNotFoundException("Support article"));
    }

    @Transactional
    public void recordFeedback(String slug, AuthenticatedUser principal, boolean helpful) {
        SupportArticle article = articleRepository.findBySlugAndStatus(slug, "PUBLISHED")
                .orElseThrow(() -> new ResourceNotFoundException("Support article"));
        SupportArticleFeedback feedback = feedbackRepository
                .findByArticleIdAndUserId(article.getId(), principal.userId())
                .orElseGet(() -> new SupportArticleFeedback(
                        article.getId(), principal.userId(), helpful, clock.instant()));
        feedback.updateHelpful(helpful, clock.instant());
        feedbackRepository.save(feedback);
    }

    @Transactional(readOnly = true)
    public List<SupportSearchResult> search(String query, AuthenticatedUser principal) {
        List<SupportSearchResult> results = articleRepository
                .searchPublished(query, PageRequest.of(0, 10))
                .stream()
                .map(article -> new SupportSearchResult(
                        "HELP_ARTICLE", article.getPublicId(), article.getTitle(), article.getSummary(),
                        article.getCategoryId(), "/support/help/" + article.getSlug()))
                .toList();
        List<SupportSearchResult> tickets = ticketRepository.searchOwnedTickets(
                        principal.organizationId(), principal.userId(), query, PageRequest.of(0, 10))
                .stream()
                .map(ticket -> new SupportSearchResult(
                        "SUPPORT_TICKET", ticket.getPublicId(), ticket.getSubject(),
                        "Your support request · " + ticket.getStatus().name().replace('_', ' '),
                        ticket.getCategory().name(), "/support/tickets"))
                .toList();
        return java.util.stream.Stream.concat(results.stream(), tickets.stream()).limit(20).toList();
    }
}
