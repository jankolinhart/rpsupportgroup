package com.reelypops.rpsupportgroup.vetting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Store for operator-uploaded marker images ({@link UploadedMarkerImage}). */
public interface UploadedMarkerImageRepository extends JpaRepository<UploadedMarkerImage, UUID> {
}
