package com.reelypops.rpsupportgroup.group;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * SG config registry (Phase 1, F1). A config is auto-registered {@code UNCLAIMED} by the first client to
 * configure a group (§6); it is keyed on the group's Instagram account, so a second registration for the same
 * account is a conflict. The authoritative definition is served to both the client (liking) and the scanner
 * (analytics) — the single shared contract.
 */
@Service
public class SupportGroupConfigService {

    private final SupportGroupConfigRepository configs;

    public SupportGroupConfigService(SupportGroupConfigRepository configs) {
        this.configs = configs;
    }

    @Transactional
    public SupportGroupConfig create(String igAccount, GroupDefinition definition) {
        if (configs.existsByIgAccount(igAccount)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "config already exists for " + igAccount);
        }
        return configs.save(SupportGroupConfig.createUnclaimed(igAccount, definition));
    }

    @Transactional(readOnly = true)
    public SupportGroupConfig get(String igAccount) {
        return require(igAccount);
    }

    @Transactional(readOnly = true)
    public List<SupportGroupConfig> list() {
        return configs.findAllByOrderByCreatedAtDesc();
    }

    /** An owner claims an unclaimed config (§6). Idempotent guard: re-claiming a claimed config is a conflict. */
    @Transactional
    public SupportGroupConfig claim(String igAccount, UUID ownerId) {
        SupportGroupConfig c = require(igAccount);
        if (c.getStatus() == ConfigStatus.CLAIMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " is already claimed");
        }
        c.claim(ownerId);
        return configs.save(c);
    }

    /** Register a client-discovered marker owner (Q4). Any authenticated client may report; the add is idempotent. */
    @Transactional
    public SupportGroupConfig addMarkerOwner(String igAccount, String handle) {
        SupportGroupConfig c = require(igAccount);
        c.addMarkerOwner(handle);
        return configs.save(c);
    }

    /** Owner revokes a marker owner (Q4 safety-net) — only the config's owner may do so. */
    @Transactional
    public SupportGroupConfig removeMarkerOwner(String igAccount, String handle, UUID caller) {
        SupportGroupConfig c = require(igAccount);
        if (c.getOwnerId() == null || !c.getOwnerId().equals(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the owner may revoke a marker owner");
        }
        c.removeMarkerOwner(handle);
        return configs.save(c);
    }

    private SupportGroupConfig require(String igAccount) {
        return configs.findByIgAccount(igAccount)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no config for " + igAccount));
    }
}
