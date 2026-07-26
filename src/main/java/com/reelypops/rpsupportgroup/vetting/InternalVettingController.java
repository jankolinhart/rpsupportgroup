package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal read surface for the advisory vetting proposal on {@code /supportgroup/v1/internal/vetting}, authenticated by
 * the shared {@code X-Internal-Api-Key} (SecurityConfig internal chain). The admin vetting tool (via the rpadminserver
 * BFF) fetches a snapshot's Tier&nbsp;0 proposal to pre-fill the review form. Never client-facing; never auto-vets.
 */
@RestController
@RequestMapping("/supportgroup/v1/internal/vetting")
public class InternalVettingController {

    private final VettingProposalService service;
    private final DetectedProfileService detectedProfiles;
    private final AiDiscoveryService aiDiscovery;
    private final MarkerImageService markerImages;

    public InternalVettingController(VettingProposalService service, DetectedProfileService detectedProfiles,
                                     AiDiscoveryService aiDiscovery, MarkerImageService markerImages) {
        this.service = service;
        this.detectedProfiles = detectedProfiles;
        this.aiDiscovery = aiDiscovery;
        this.markerImages = markerImages;
    }

    /** The advisory vetting proposal derived from a snapshot's corpus (404 if the snapshot is unknown). */
    @GetMapping("/snapshots/{snapshotId}/proposal")
    public DetectorProfileProposal proposal(@PathVariable UUID snapshotId) {
        return service.propose(snapshotId);
    }

    /**
     * Detect + persist the admin-only vetting advisory ({@link DetectedProfile}, M3a) for a snapshot onto its group
     * config, then return it. 404 if the snapshot or its group config is unknown. Regenerated on every (re-)vet.
     */
    @PostMapping("/snapshots/{snapshotId}/detect")
    public DetectedProfile detect(@PathVariable UUID snapshotId) {
        return detectedProfiles.detect(snapshotId);
    }

    /**
     * Run the explicit AI-discovery action (M4): refresh the Tier-0 advisory, then (when the AI gateway is configured
     * + reachable) attach the {@link DetectedProfile.AiDiscovery} verdict and persist it. Fail-open — returns the
     * unchanged Tier-0 advisory when the gateway is off/unavailable. 404 if the snapshot or its config is unknown.
     */
    @PostMapping("/snapshots/{snapshotId}/detect-ai")
    public DetectedProfile detectAi(@PathVariable UUID snapshotId) {
        return aiDiscovery.runAiDiscovery(snapshotId);
    }

    /**
     * Store a marker image an operator uploaded by hand in the Vetting Portal (M3 follow-up) when the auto-detected
     * corpus missed a marker. Returns the new id (for serving) and its best-effort perceptual dHash (for matching).
     * 400 when the body is empty or not a decodable image.
     */
    @PostMapping("/marker-images")
    @ResponseStatus(HttpStatus.CREATED)
    public MarkerImageResponse uploadMarkerImage(
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody(required = false) byte[] image) {
        return MarkerImageResponse.of(markerImages.upload(image, contentType));
    }

    /** Serve an uploaded marker image's bytes (404 when the id is unknown). */
    @GetMapping("/marker-images/{id}")
    public ResponseEntity<byte[]> getMarkerImage(@PathVariable UUID id) {
        return markerImages.get(id)
                .map(m -> ResponseEntity.ok().contentType(MediaType.parseMediaType(m.getContentType())).body(m.getImage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
