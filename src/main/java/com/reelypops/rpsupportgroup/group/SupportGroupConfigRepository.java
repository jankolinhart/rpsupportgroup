package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportGroupConfigRepository extends JpaRepository<SupportGroupConfig, UUID> {

    Optional<SupportGroupConfig> findByIgAccount(String igAccount);

    boolean existsByIgAccount(String igAccount);

    List<SupportGroupConfig> findAllByOrderByCreatedAtDesc();

    List<SupportGroupConfig> findByVettedTrueOrderByCreatedAtDesc();
}
