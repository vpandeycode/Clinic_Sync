package org.clinicsync.common.model;

import java.io.Serializable;
import java.util.List;

/**
 * A batch of changes pushed from an origin edge node to the central server.
 */
public record SyncBatch(
        String batchId,
        String originNodeId,
        String clinicScope,
        List<ChangeRecord> changes,
        String signature
) implements Serializable {
    public SyncBatch(String batchId, String originNodeId, String clinicScope, List<ChangeRecord> changes) {
        this(batchId, originNodeId, clinicScope, changes, null);
    }
}
