package com.reelypops.rpsupportgroup.group;

/**
 * The admin-only pair the Vetting Portal renders side-by-side (M3a): the immutable {@link DetectedProfile} advisory and
 * the editable {@link VettedProfile}. The <em>living diff</em> between them (which fields the admin changed) is computed
 * from this. Never client-facing — both objects are admin-only. Either may be {@code null} (a group not yet analysed
 * has no detected profile; a group not yet saved has no vetted profile).
 *
 * @param detected the vetting advisory (what the pipeline detected) — the diff baseline
 * @param vetted   the admin-confirmed profile (what will ship) — the diff target
 */
public record VettingProfilesResponse(DetectedProfile detected, VettedProfile vetted) {

    static VettingProfilesResponse of(SupportGroupConfig c) {
        return new VettingProfilesResponse(c.getDetectedProfile(), c.getVettedProfile());
    }
}
