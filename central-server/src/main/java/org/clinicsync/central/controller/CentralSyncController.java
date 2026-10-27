package org.clinicsync.central.controller;

import org.clinicsync.common.model.SyncAck;
import org.clinicsync.common.model.SyncBatch;
import org.clinicsync.central.service.CentralSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
