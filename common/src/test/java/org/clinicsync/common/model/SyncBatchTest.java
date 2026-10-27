package org.clinicsync.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncBatchTest {

    @Test
    @DisplayName("SyncBatch should correctly hold change records and generate valid metadata")
    void testSyncBatchCreation() {
        ChangeRecord change = new ChangeRecord(
                1L,
                "Patient",
                "0192e2b0-8f92-7f23-8cfb-a7e7829281a1",
                "1",
                "{\"resourceType\":\"Patient\",\"id\":\"0192e2b0-8f92-7f23-8cfb-a7e7829281a1\"}",
                null,
                "clinic-alpha",
                "1729000000000:0:clinic-alpha",
                "dr-smith",
                false
        );

        SyncBatch batch = new SyncBatch("batch-101", "clinic-alpha", "clinic-alpha", List.of(change));
        assertThat(batch.batchId()).isEqualTo("batch-101");
        assertThat(batch.changes()).hasSize(1);
        assertThat(batch.changes().getFirst().originSeq()).isEqualTo(1L);

        SyncAck ack = new SyncAck("batch-101", "clinic-alpha", 1L, 1, "APPLIED");
        assertThat(ack.highestContiguousOriginSeq()).isEqualTo(1L);
        assertThat(ack.status()).isEqualTo("APPLIED");
    }
}
