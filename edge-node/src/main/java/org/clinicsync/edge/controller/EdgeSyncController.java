package org.clinicsync.edge.controller;

import org.clinicsync.common.model.SyncAck;
import org.clinicsync.edge.repository.EdgeResourceRepository;
import org.clinicsync.edge.service.EdgeSyncClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sync")
public class EdgeSyncController {

    private final EdgeSyncClient syncClient;
    private final EdgeResourceRepository repository;

    public EdgeSyncController(EdgeSyncClient syncClient, EdgeResourceRepository repository) {
        this.syncClient = syncClient;
        this.repository = repository;
    }

    @PostMapping("/push-now")
    public ResponseEntity<SyncAck> pushNow() {
        SyncAck ack = syncClient.pushPendingChanges();
        return ResponseEntity.ok(ack);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        long lastPushedSeq = repository.getLastPushedSeq("central");
        return ResponseEntity.ok(Map.of(
                "peer", "central",
                "lastPushedSeq", lastPushedSeq
        ));
    }
}
