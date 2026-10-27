package org.clinicsync.common.exception;

/**
 * Raised by the sync protocol when a batch, pull request or change is refused.
 * Carries a stable machine-readable reason and the HTTP status the controller should return.
 */
public class SyncRejectedException extends RuntimeException {

    public static final String NODE_NOT_REGISTERED = "NODE_NOT_REGISTERED";
    public static final String NODE_REVOKED = "NODE_REVOKED";
    public static final String SCOPE_MISMATCH = "SCOPE_MISMATCH";
    public static final String HLC_DRIFT = "HLC_DRIFT";
    public static final String INVALID_BATCH = "INVALID_BATCH";
    public static final String TAMPERED = "TAMPERED";

    private final String reason;
    private final int httpStatus;

    public SyncRejectedException(String reason, int httpStatus, String message) {
        super(message);
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public String getReason() {
        return reason;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static SyncRejectedException nodeNotRegistered(String nodeId) {
        return new SyncRejectedException(NODE_NOT_REGISTERED, 403, "Edge node is not registered: " + nodeId);
    }

    public static SyncRejectedException nodeRevoked(String nodeId) {
        return new SyncRejectedException(NODE_REVOKED, 403, "Edge node is revoked or suspended: " + nodeId);
    }

    public static SyncRejectedException scopeMismatch(String nodeId, String expected, String actual) {
        return new SyncRejectedException(SCOPE_MISMATCH, 403,
                "Clinic scope mismatch for node " + nodeId + ": registered '" + expected + "' but batch declared '" + actual + "'");
    }

    public static SyncRejectedException hlcDrift(String nodeId, long remoteL, long localNow, long maxDriftMs) {
        return new SyncRejectedException(HLC_DRIFT, 422,
                "Change from " + nodeId + " has HLC physical time " + remoteL + " which exceeds local time " + localNow
                        + " by more than the allowed drift of " + maxDriftMs + " ms");
    }

    public static SyncRejectedException invalidBatch(String message) {
        return new SyncRejectedException(INVALID_BATCH, 400, message);
    }

    public static SyncRejectedException tampered(String message) {
        return new SyncRejectedException(TAMPERED, 400, message);
    }
}
