package org.clinicsync.common.exception;

public class InvalidFhirException extends RuntimeException {
    public InvalidFhirException(String message) {
        super(message);
    }

    public InvalidFhirException(String message, Throwable cause) {
        super(message, cause);
    }
}
