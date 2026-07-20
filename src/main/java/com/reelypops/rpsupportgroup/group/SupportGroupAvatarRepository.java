package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SupportGroupAvatarRepository extends JpaRepository<SupportGroupAvatar, UUID> {

    Optional<SupportGroupAvatar> findByIgAccount(String igAccount);
}
