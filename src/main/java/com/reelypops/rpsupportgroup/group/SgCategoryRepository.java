package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SgCategoryRepository extends JpaRepository<SgCategory, UUID> {

    Optional<SgCategory> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<SgCategory> findAllByOrderByLabelAsc();
}
