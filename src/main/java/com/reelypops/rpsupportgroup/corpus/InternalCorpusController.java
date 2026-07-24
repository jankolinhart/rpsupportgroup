package com.reelypops.rpsupportgroup.corpus;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Internal corpus intake + read surface on {@code /supportgroup/v1/internal/corpus}, authenticated by the shared
 * {@code X-Internal-Api-Key}. Clients (via the BFF) stream deep-scrape evidence in per scroll; the admin vetting tool
 * reads a group's snapshots back. Never client-facing.
 */
@RestController
@RequestMapping("/supportgroup/v1/internal/corpus")
public class InternalCorpusController {

    private final MarkerCorpusService service;

    public InternalCorpusController(MarkerCorpusService service) {
        this.service = service;
    }

    /** Open a new snapshot to stream a deep pass into. */
    @PostMapping("/groups/{igAccount}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse open(@PathVariable String igAccount, @Valid @RequestBody OpenSnapshotRequest req) {
        return SnapshotResponse.of(service.open(igAccount, req.source(), req.capturedByAccount()));
    }

    /** Append a per-scroll batch of items to an open snapshot. */
    @PostMapping("/snapshots/{snapshotId}/items")
    public SnapshotResponse append(@PathVariable UUID snapshotId, @Valid @RequestBody AppendItemsRequest req) {
        return SnapshotResponse.of(service.append(snapshotId, req.items()));
    }

    /** Seal a completed pass. */
    @PostMapping("/snapshots/{snapshotId}/seal")
    public SnapshotResponse seal(@PathVariable UUID snapshotId) {
        return SnapshotResponse.of(service.seal(snapshotId));
    }

    /** List a group's snapshots (newest first) — the admin vetting-evidence list. */
    @GetMapping("/groups/{igAccount}/snapshots")
    public List<SnapshotResponse> list(@PathVariable String igAccount) {
        return service.list(igAccount).stream().map(SnapshotResponse::of).toList();
    }

    /** Read one snapshot with its items in grid order. */
    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotDetailResponse detail(@PathVariable UUID snapshotId) {
        MarkerCorpusService.SnapshotDetail d = service.detail(snapshotId);
        return SnapshotDetailResponse.of(d.snapshot(), d.items());
    }
}
