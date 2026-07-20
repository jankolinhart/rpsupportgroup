package com.reelypops.rpsupportgroup.group;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
        return configs.findByIgAccount(igAccount)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no config for " + igAccount));
    }

    @Transactional(readOnly = true)
    public List<SupportGroupConfig> list() {
        return configs.findAllByOrderByCreatedAtDesc();
    }
}
