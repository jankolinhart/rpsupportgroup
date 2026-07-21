package com.reelypops.rpsupportgroup.group;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportGroupConfigRepository extends JpaRepository<SupportGroupConfig, UUID> {

    Optional<SupportGroupConfig> findByIgAccount(String igAccount);

    boolean existsByIgAccount(String igAccount);

    List<SupportGroupConfig> findAllByOrderByCreatedAtDesc();

    /** Configs carrying a given category (Cycle 12) — used to unassign it from every group when a category is deleted. */
    List<SupportGroupConfig> findByCategories_Slug(String slug);

    /**
     * Browse the vetted registry (Cycle 12): keep only configs matching <em>any</em> of the given category slugs
     * (OR — pass {@code noCats=true} to skip the category filter) <em>and</em> an optional case-insensitive substring
     * on the group's IG account, newest first, paged.
     */
    @Query(value = """
            SELECT c FROM SupportGroupConfig c
            WHERE c.vetted = true
              AND (:noCats = true OR EXISTS (SELECT 1 FROM c.categories cat WHERE cat.slug IN :slugs))
              AND lower(c.igAccount) LIKE lower(concat('%', :q, '%'))
            ORDER BY c.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(c) FROM SupportGroupConfig c
            WHERE c.vetted = true
              AND (:noCats = true OR EXISTS (SELECT 1 FROM c.categories cat WHERE cat.slug IN :slugs))
              AND lower(c.igAccount) LIKE lower(concat('%', :q, '%'))
            """)
    Page<SupportGroupConfig> browseVetted(@Param("noCats") boolean noCats,
                                          @Param("slugs") Collection<String> slugs,
                                          @Param("q") String q,
                                          Pageable pageable);
}
