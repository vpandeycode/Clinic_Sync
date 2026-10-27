package org.clinicsync.central.controller;

import org.clinicsync.common.exception.ClockDriftException;
import org.clinicsync.common.exception.InvalidFhirException;
import org.clinicsync.common.exception.OptimisticLockException;
import org.clinicsync.common.exception.ResourceGoneException;
import org.clinicsync.common.exception.ResourceNotFoundException;
import org.clinicsync.common.exception.SyncRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class FhirControllerAdvice {

    @ExceptionHandler(SyncRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleSyncRejected(SyncRejectedException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(createOperationOutcome("error", ex.getReason(), ex.getMessage()));
    }

    @ExceptionHandler(ClockDriftException.class)
    public ResponseEntity<Map<String, Object>> handleClockDrift(ClockDriftException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createOperationOutcome("error", "HLC_DRIFT", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createOperationOutcome("error", "not-found", ex.getMessage()));
    }

    @ExceptionHandler(ResourceGoneException.class)
    public ResponseEntity<Map<String, Object>> handleGone(ResourceGoneException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createOperationOutcome("error", "deleted", ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<Map<String, Object>> handlePreconditionFailed(OptimisticLockException ex) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createOperationOutcome("error", "conflict", ex.getMessage()));
    }

    @ExceptionHandler(InvalidFhirException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(InvalidFhirException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createOperationOutcome("error", "invalid", ex.getMessage()));
    }

    private Map<String, Object> createOperationOutcome(String severity, String code, String diagnostics) {
        return Map.of(
                "resourceType", "OperationOutcome",
                "issue", java.util.List.of(Map.of(
                        "severity", severity,
                        "code", code,
                        "diagnostics", diagnostics
                ))
        );
    }
}
