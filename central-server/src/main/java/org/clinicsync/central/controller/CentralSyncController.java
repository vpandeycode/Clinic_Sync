package org.clinicsync.central.controller;

import org.clinicsync.common.model.PullResponse;
import org.clinicsync.common.model.SyncAck;
import org.clinicsync.common.model.SyncBatch;
import org.clinicsync.central.service.CentralSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sync protocol endpoints: push (edge -> central batches) and pull (central -> edge feed).
 */
@RestController
@RequestMapping("/api/v1/sync")
public class CentralSyncController {

    private final CentralSyncService syncService;

    public CentralSyncController(CentralSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/push")
    public ResponseEntity<SyncAck> push(@RequestBody SyncBatch batch) {
        SyncAck ack = syncService.ingestBatch(batch);
        return ResponseEntity.ok(ack);
    }

    @GetMapping("/pull")
    public ResponseEntity<PullResponse> pull(
            @RequestParam("nodeId") String nodeId,
            @RequestParam(value = "since", defaultValue = "0") long since,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(syncService.pull(nodeId, since, limit));
    }
}
