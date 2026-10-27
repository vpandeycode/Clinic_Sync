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

/**
 * Operational endpoints for the local sync agent: trigger a full push+pull cycle or inspect the
 * cursors. The agent itself is invoked explicitly (prototype) rather than on a schedule.
 */
@RestController
@RequestMapping("/api/v1/sync")
public class EdgeSyncController {

    private static final String CENTRAL_PEER = "central";

    private final EdgeSyncClient syncClient;
    private final EdgeResourceRepository repository;

    public EdgeSyncController(EdgeSyncClient syncClient, EdgeResourceRepository repository) {
        this.syncClient = syncClient;
        this.repository = repository;
    }

    /** Full sync cycle: push journalled changes, then pull everything new from central. */
    @PostMapping("/run")
    public ResponseEntity<EdgeSyncClient.SyncResult> run() {
        return ResponseEntity.ok(syncClient.syncOnce());
    }

    /** Push-only shortcut (used by the walking-skeleton test and debugging). */
    @PostMapping("/push-now")
    public ResponseEntity<SyncAck> pushNow() {
        return ResponseEntity.ok(syncClient.pushPendingChanges());
    }

    /** Pull-only shortcut. */
    @PostMapping("/pull-now")
    public ResponseEntity<Map<String, Object>> pullNow() {
        var page = syncClient.pullOnce();
        return ResponseEntity.ok(Map.of(
                "changesReceived", page.changes().size(),
                "nextGlobalSeq", page.nextGlobalSeq(),
                "hasMore", page.hasMore()
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "peer", CENTRAL_PEER,
                "lastPushedSeq", repository.getLastPushedSeq(CENTRAL_PEER),
                "lastPulledGlobalSeq", repository.getLastPulledGlobalSeq(CENTRAL_PEER)
        ));
    }
}
