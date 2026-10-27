package org.clinicsync.common.model;

import java.io.Serializable;
import java.util.List;

/**
 * Bounded page of changes returned by central for a pull request.
 *
 * <p>{@code nextGlobalSeq} is the cursor the edge must persist after applying this page. It is the
 * highest central {@code global_seq} <em>scanned</em>, including changes filtered out by clinic
 * scope, so the edge never re-scans or skips rows. {@code hasMore} tells the edge whether to keep
 * paging with the new cursor.</p>
 */
public record PullResponse(
        String nodeId,
        List<PulledChange> changes,
        long nextGlobalSeq,
        boolean hasMore
) implements Serializable {
    public PullResponse {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
