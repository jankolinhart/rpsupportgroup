package com.reelypops.rpsupportgroup.corpus;

import com.reelypops.rpsupportgroup.aigateway.MarkerOcrService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final MarkerOcrService markerOcr;

    public InternalCorpusController(MarkerCorpusService service, MarkerOcrService markerOcr) {
        this.service = service;
        this.markerOcr = markerOcr;
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
        return SnapshotDetailResponse.of(d.snapshot(), d.items(), d.representativeShortcodes());
    }

    /** Upsert a representative thumbnail (raw image bytes) for a post in a snapshot. */
    @PutMapping("/snapshots/{snapshotId}/representatives/{shortcode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putRepresentative(@PathVariable UUID snapshotId, @PathVariable String shortcode,
                                  @RequestHeader(value = "Content-Type", required = false) String contentType,
                                  @RequestBody(required = false) byte[] image) {
        service.putRepresentative(snapshotId, shortcode, image, contentType);
    }

    /** Serve a post's representative thumbnail — 404 when none has been contributed. */
    @GetMapping("/snapshots/{snapshotId}/representatives/{shortcode}")
    public ResponseEntity<byte[]> getRepresentative(@PathVariable UUID snapshotId, @PathVariable String shortcode) {
        return service.getRepresentative(snapshotId, shortcode)
                .map(r -> ResponseEntity.ok().contentType(MediaType.parseMediaType(r.getContentType())).body(r.getImage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * OCR a corpus post's representative image (Vetting Portal "add from grid"): transcribe its overlay text so a
     * hand-added {@code TEXT_OVERLAY} marker carries the OCR target the client matches on. 404 when no representative
     * exists; {@code ocrText} is {@code null} when the gateway is off/unavailable or the image has no readable text (the
     * operator types it in). Fail-open — a gateway outage yields a null {@code ocrText}, never an error.
     */
    @PostMapping("/snapshots/{snapshotId}/representatives/{shortcode}/ocr")
    public MarkerOcrResponse ocrRepresentative(@PathVariable UUID snapshotId, @PathVariable String shortcode) {
        CorpusRepresentative rep = service.getRepresentative(snapshotId, shortcode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no representative for " + shortcode + " in snapshot " + snapshotId));
        String ocrText = markerOcr.readOverlayText(rep.getImage(), rep.getContentType(), null).orElse(null);
        return new MarkerOcrResponse(ocrText);
    }
}
