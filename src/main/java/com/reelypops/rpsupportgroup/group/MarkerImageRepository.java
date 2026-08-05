package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

/** Durable, content-addressed marker display images, keyed by their sha-256 content locator. */
public interface MarkerImageRepository extends JpaRepository<MarkerImage, String> {
}
