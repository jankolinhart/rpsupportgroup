package com.reelypops.rpsupportgroup.group;

import java.time.Instant;
import java.util.List;

/**
 * One picture a CLIENT has delivered for a group's marker, with the fingerprint that client computed for it — a
 * candidate an operator can pick during vetting.
 *
 * <p><strong>Why this exists as a catalogue rather than a drift detail.</strong> Drift observations are a work
 * queue: they get resolved and pruned, and the picture behind them goes with them. But directive B1 means no cloud
 * service can ever go and fetch that picture again — only a client can supply it, and only while it is still being
 * posted. So what a client sends is filed durably the moment it arrives, and stays selectable long after the report
 * that carried it is closed.</p>
 *
 * <p><strong>Prefer these over an operator upload.</strong> An uploaded picture is fingerprinted by this service,
 * in a dialect 15–34 bits from what clients compute; these carry the client's own value, which is the only one a
 * client can match.</p>
 */
public record ClientMarkerImageResponse(
        String dHash,
        String imageLocator,
        String markerRole,
        String markerText,
        String evidencePostId,
        /** How many times a client has reported this exact picture — a one-off looks different from a routine. */
        long timesSeen,
        Instant firstSeenAt,
        Instant lastSeenAt) {

    public static ClientMarkerImageResponse of(ClientMarkerImage i) {
        return new ClientMarkerImageResponse(i.getDHash(), i.getImageLocator(), i.getMarkerRole(), i.getMarkerText(),
                i.getEvidencePostId(), i.getTimesSeen(), i.getFirstSeenAt(), i.getLastSeenAt());
    }

    public static List<ClientMarkerImageResponse> of(List<ClientMarkerImage> images) {
        return images.stream().map(ClientMarkerImageResponse::of).toList();
    }
}
