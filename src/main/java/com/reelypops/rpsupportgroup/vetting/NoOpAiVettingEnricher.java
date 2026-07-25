package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The default {@link AiVettingEnricher}: AI off. It returns the Tier&nbsp;0 (dHash-cluster) proposal unchanged, so the
 * vetting pipeline is fail-open and the admin vets manually until a real {@code rpaigateway}-backed enricher (Tier 1/2)
 * is chosen and wired in. That future enricher is a deliberately deferred decision (provider / model); when it lands it
 * takes over the {@link AiVettingEnricher} binding (e.g. {@code @Primary}) without touching the Tier&nbsp;0 engine.
 */
@Component
public class NoOpAiVettingEnricher implements AiVettingEnricher {

    @Override
    public DetectorProfileProposal enrich(DetectorProfileProposal tier0, List<CorpusSnapshotItem> items) {
        return tier0;
    }
}
