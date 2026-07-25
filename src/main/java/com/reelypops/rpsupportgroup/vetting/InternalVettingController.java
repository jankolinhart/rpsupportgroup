package com.reelypops.rpsupportgroup.vetting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public InternalVettingController(VettingProposalService service) {
        this.service = service;
    }

    /** The advisory vetting proposal derived from a snapshot's corpus (404 if the snapshot is unknown). */
    @GetMapping("/snapshots/{snapshotId}/proposal")
    public DetectorProfileProposal proposal(@PathVariable UUID snapshotId) {
        return service.propose(snapshotId);
    }
}
