package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The durable catalogue of client-delivered marker pictures (18/08/2026). */
public interface ClientMarkerImageRepository extends JpaRepository<ClientMarkerImage, UUID> {

    /**
     * The natural key: one row per distinct picture per group, however many times it is observed.
     *
     * <p>Spelled out rather than derived from the method name: Spring Data capitalises a derived property as
     * {@code DHash}, which no longer resolves against the {@code dHash} field, and it fails at CONTEXT STARTUP
     * rather than at the call — every Spring test in the module goes red at once with an unrelated-looking
     * message.</p>
     */
    @Query("select c from ClientMarkerImage c where c.configId = :configId and c.dHash = :dHash")
    Optional<ClientMarkerImage> findByConfigIdAndDHash(@Param("configId") UUID configId,
                                                       @Param("dHash") String dHash);

    /** Everything a client has ever delivered for this group, most recently seen first — the vetting picker. */
    List<ClientMarkerImage> findByConfigIdOrderByLastSeenAtDesc(UUID configId);
}
