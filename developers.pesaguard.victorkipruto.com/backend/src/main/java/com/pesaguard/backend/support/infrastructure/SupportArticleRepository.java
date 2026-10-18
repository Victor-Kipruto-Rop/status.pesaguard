package com.pesaguard.backend.support.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.support.domain.SupportArticle;

public interface SupportArticleRepository extends JpaRepository<SupportArticle, UUID> {

    @Query(value = """
            select article.* from support_articles article
            join support_categories category on category.id = article.category_id
            where article.status = 'PUBLISHED'
              and (:category is null or article.category_id = :category)
            order by article.published_at desc, article.title asc
            """,
            countQuery = """
            select count(*) from support_articles article
            where article.status = 'PUBLISHED'
              and (:category is null or article.category_id = :category)
            """,
            nativeQuery = true)
    Page<SupportArticle> findPublished(@Param("category") String category, Pageable pageable);

    @Query(value = """
            select article.* from support_articles article
            where article.status = 'PUBLISHED'
              and (
                lower(article.title) = lower(:query)
                or lower(article.related_error_codes) like lower(concat('%', :query, '%'))
                or lower(article.related_api_endpoints) like lower(concat('%', :query, '%'))
                or to_tsvector('simple',
                    coalesce(article.title, '') || ' ' || coalesce(article.summary, '') || ' ' ||
                    coalesce(article.content, '') || ' ' || coalesce(article.tags, '') || ' ' ||
                    coalesce(article.keywords, '') || ' ' || coalesce(article.related_error_codes, '') || ' ' ||
                    coalesce(article.related_api_endpoints, ''))
                    @@ websearch_to_tsquery('simple', :query)
              )
            order by case when lower(article.title) = lower(:query) then 0 else 1 end,
                ts_rank_cd(to_tsvector('simple',
                    coalesce(article.title, '') || ' ' || coalesce(article.summary, '') || ' ' ||
                    coalesce(article.content, '') || ' ' || coalesce(article.tags, '') || ' ' ||
                    coalesce(article.keywords, '') || ' ' || coalesce(article.related_error_codes, '') || ' ' ||
                    coalesce(article.related_api_endpoints, '')),
                    websearch_to_tsquery('simple', :query)) desc,
                article.published_at desc
            """,
            countQuery = """
            select count(*) from support_articles article
            where article.status = 'PUBLISHED'
              and (
                lower(article.title) = lower(:query)
                or lower(article.related_error_codes) like lower(concat('%', :query, '%'))
                or lower(article.related_api_endpoints) like lower(concat('%', :query, '%'))
                or to_tsvector('simple',
                    coalesce(article.title, '') || ' ' || coalesce(article.summary, '') || ' ' ||
                    coalesce(article.content, '') || ' ' || coalesce(article.tags, '') || ' ' ||
                    coalesce(article.keywords, '') || ' ' || coalesce(article.related_error_codes, '') || ' ' ||
                    coalesce(article.related_api_endpoints, ''))
                    @@ websearch_to_tsquery('simple', :query)
              )
            """,
            nativeQuery = true)
    Page<SupportArticle> searchPublished(@Param("query") String query, Pageable pageable);

    Optional<SupportArticle> findBySlugAndStatus(String slug, String status);

    List<SupportArticle> findTop5ByStatusOrderByPublishedAtDesc(String status);
}
