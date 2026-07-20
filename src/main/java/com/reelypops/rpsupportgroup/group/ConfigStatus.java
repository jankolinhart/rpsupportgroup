package com.reelypops.rpsupportgroup.group;

/**
 * Lifecycle of an SG config (§6). A config is auto-registered {@code UNCLAIMED} by the first client to
 * configure a group; a subscribed owner later {@code CLAIMS} it, making it the authoritative template.
 */
public enum ConfigStatus {
    UNCLAIMED,
    CLAIMED
}
