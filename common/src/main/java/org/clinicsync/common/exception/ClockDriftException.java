package org.clinicsync.common.exception;

public class ClockDriftException extends RuntimeException {
    public ClockDriftException(String message) {
        super(message);
    }
}
