package org.clinicsync.common.sync;

import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.model.SyncBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SigningPayloadTest {

    @Test
    @DisplayName("Signature payload is identical regardless of field order and ignores the signature field")
    void payloadIsDeterministicAndExcludesSignature() {
        ChangeRecord change = new ChangeRecord(
                1L, "Patient", "p-1", "1-clinic-alpha", "{\"resourceType\":\"Patient\"}",
                null, "clinic-alpha", "1000:0:clinic-alpha", "clinician", false
        );

        SyncBatch unsigned = new SyncBatch("b-1", "clinic-alpha", "clinic-alpha", List.of(change));
        SyncBatch signed = new SyncBatch("b-1", "clinic-alpha", "clinic-alpha", List.of(change), "BASE64SIG==");

        assertThat(SigningPayload.forBatch(signed)).isEqualTo(SigningPayload.forBatch(unsigned));
    }

    @Test
    @DisplayName("Any change to the batch content changes the signed bytes")
    void payloadChangesWhenContentChanges() {
        ChangeRecord change = new ChangeRecord(
                1L, "Patient", "p-1", "1-clinic-alpha", "{\"resourceType\":\"Patient\"}",
                null, "clinic-alpha", "1000:0:clinic-alpha", "clinician", false
        );
        ChangeRecord tampered = new ChangeRecord(
                1L, "Patient", "p-1", "1-clinic-alpha", "{\"resourceType\":\"Patient\",\"gender\":\"male\"}",
                null, "clinic-alpha", "1000:0:clinic-alpha", "clinician", false
        );

        SyncBatch original = new SyncBatch("b-1", "clinic-alpha", "clinic-alpha", List.of(change));
        SyncBatch modified = new SyncBatch("b-1", "clinic-alpha", "clinic-alpha", List.of(tampered));

        assertThat(SigningPayload.forBatch(original)).isNotEqualTo(SigningPayload.forBatch(modified));
    }
}
