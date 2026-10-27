package org.clinicsync.central.controller;

import org.clinicsync.central.repository.NodeRegistryRepository.NodeRecord;
import org.clinicsync.central.service.NodeRegistryService;
import org.clinicsync.common.model.NodeRegistration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Node enrolment endpoints. Authentication hardening (enrollment token / admin role) lands in
 * Phase 5; see DECISIONS.md.
 */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeRegistryController {

    private final NodeRegistryService nodeRegistryService;

    public NodeRegistryController(NodeRegistryService nodeRegistryService) {
        this.nodeRegistryService = nodeRegistryService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody NodeRegistration registration) {
        NodeRecord node = nodeRegistryService.register(registration);
        return ResponseEntity.ok(toView(node));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(nodeRegistryService.list().stream().map(NodeRegistryController::toView).toList());
    }

    @PostMapping("/{nodeId}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String nodeId) {
        return ResponseEntity.ok(toView(nodeRegistryService.revoke(nodeId)));
    }

    private static Map<String, Object> toView(NodeRecord node) {
        return Map.of(
                "nodeId", node.nodeId(),
                "clinicScope", node.clinicScope(),
                "status", node.status()
        );
    }
}
