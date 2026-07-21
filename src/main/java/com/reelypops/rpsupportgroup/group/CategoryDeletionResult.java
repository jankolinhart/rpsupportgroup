package com.reelypops.rpsupportgroup.group;

/**
 * Result of deleting a content category (Cycle 12): how many groups it was unassigned from as the delete cascaded,
 * so the admin UI can confirm the impact.
 */
public record CategoryDeletionResult(int affectedGroups) {
}
