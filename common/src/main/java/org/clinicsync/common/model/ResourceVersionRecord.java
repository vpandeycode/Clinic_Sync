package org.clinicsync.common.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents an immutable version entry in the versioned resource history.
 */
public record ResourceVersionRecord(
        String resourceType,
        String resourceId,
        String versionId,
        String contentJson,
        String parentVersionIds,
        String originNodeId,
        long originSeq,
        String hlc,
        String author,
        boolean isTombstone,
        Instant createdAt
) implements Serializable {}
