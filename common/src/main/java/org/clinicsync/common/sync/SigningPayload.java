package org.clinicsync.common.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.clinicsync.common.crypto.CanonicalJsonUtil;
import org.clinicsync.common.model.SyncBatch;

import java.nio.charset.StandardCharsets;

/**
 * Builds the exact byte sequence an Ed25519 batch signature is computed over.
 *
 * <p>The signed document is the canonical JSON of {@code {batchId, clinicScope, originNodeId,
 * changes}} — every batch field except {@code signature}. Central recomputes the same bytes from
 * the received batch and verifies, so a tampered change list, batch id or origin node fails
 * verification. Canonical JSON (sorted keys, no insignificant whitespace) guarantees both sides
 * serialize identically regardless of JVM or transport.</p>
 */
public final class SigningPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SigningPayload() {}

    /** Canonical bytes for a batch; the {@code signature} field itself is never signed. */
    public static byte[] forBatch(SyncBatch batch) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("batchId", batch.batchId());
        root.put("clinicScope", batch.clinicScope());
        root.put("originNodeId", batch.originNodeId());
        root.set("changes", MAPPER.valueToTree(batch.changes()));
        return CanonicalJsonUtil.canonicalize(root.toString());
    }

    public static String forBatchAsString(SyncBatch batch) {
        return new String(forBatch(batch), StandardCharsets.UTF_8);
    }
}
