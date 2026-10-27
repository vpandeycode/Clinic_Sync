package org.clinicsync.common.model;

import java.io.Serializable;

/**
 * One change returned by the central pull endpoint.
 *
 * <p>{@code globalSeq} is the central timeline cursor (from {@code applied_changes.global_seq});
 * the remaining fields mirror {@link ChangeRecord} as stored by the originating node.</p>
 */
public record PulledChange(
        long globalSeq,
        long originSeq,
        String originNodeId,
        String resourceType,
        String resourceId,
        String versionId,
        String contentJson,
        String parentVersionIds,
        String hlc,
        String author,
        boolean isTombstone
) implements Serializable {}
