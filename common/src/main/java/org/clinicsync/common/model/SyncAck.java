package org.clinicsync.common.model;

import java.io.Serializable;

/**
 * Acknowledgment returned by central server upon batch ingestion.
 */
public record SyncAck(
        String batchId,
        String originNodeId,
        long highestContiguousOriginSeq,
        int appliedCount,
        String status
) implements Serializable {}
