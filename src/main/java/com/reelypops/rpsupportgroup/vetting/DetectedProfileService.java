package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile;
import com.reelypops.rpsupportgroup.group.MarkerStyle;
import com.reelypops.rpsupportgroup.group.SupportGroupConfig;
import com.reelypops.rpsupportgroup.group.SupportGroupConfigRepository;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.ProposedType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Generates the persisted, admin-only <strong>vetting advisory</strong> (M3a): it runs the Tier&nbsp;0
 * {@link VettingProposalService} over a sealed snapshot, maps the proposal into a facet-structured
 * {@link DetectedProfile}, and stores it on the snapshot's {@link SupportGroupConfig} to pre-fill the Vetting Portal.
 * Regenerated on every (re-)vet; never client-facing. Depends only <em>downward</em> on the {@code group} package (no
 * cycle: the config entity never references the vetting pipeline).
 */
@Service
public class DetectedProfileService {

    private final VettingProposalService proposals;
    private final SupportGroupConfigRepository configs;

    public DetectedProfileService(VettingProposalService proposals, SupportGroupConfigRepository configs) {
        this.proposals = proposals;
        this.configs = configs;
    }

    /**
     * Detect + persist the advisory for a sealed snapshot, then return it. 404 if the snapshot is unknown (via the
     * analysis) or if no {@link SupportGroupConfig} exists for the snapshot's {@code igAccount}.
     */
    @Transactional
    public DetectedProfile detect(UUID snapshotId) {
        SnapshotAnalysis analysis = proposals.analyze(snapshotId);
        DetectorProfileProposal proposal = analysis.proposal();
        DetectedProfile profile = new DetectedProfile(
                proposal.snapshotId(), proposal.igAccount(), proposal.itemCount(), System.currentTimeMillis(),
                proposal.provenance(), proposal.escalate(),
                new DetectedProfile.StyleFacet(toStyle(proposal.proposedType()), proposal.confidence()),
                new DetectedProfile.OwnerFacet(proposal.ownerRoster(), proposal.confidence()),
                analysis.references(),
                analysis.candidates(),
                analysis.schedule());
        SupportGroupConfig config = configs.findByIgAccount(proposal.igAccount())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no config for " + proposal.igAccount()));
        config.updateDetectedProfile(profile);
        configs.save(config);
        return profile;
    }

    private static MarkerStyle toStyle(ProposedType type) {
        return switch (type) {
            case FLAT_BANNER -> MarkerStyle.FLAT_BANNER;
            case TEXT_OVERLAY -> MarkerStyle.TEXT_OVERLAY;
            case UNKNOWN -> MarkerStyle.UNKNOWN;
        };
    }
}
