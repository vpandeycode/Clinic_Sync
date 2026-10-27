package org.clinicsync.common.model;

import java.io.Serializable;

/**
 * Represents a single discrete change to a versioned FHIR resource.
 */
public record ChangeRecord(
        long originSeq,
        String resourceType,
        String resourceId,
        String versionId,
        String contentJson,
        String parentVersionIds,
        String originNodeId,
        String hlc,
        String author,
        boolean isTombstone
) implements Serializable {}
