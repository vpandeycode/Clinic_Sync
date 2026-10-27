package org.clinicsync.central.service;

import org.clinicsync.central.repository.NodeRegistryRepository;
import org.clinicsync.central.repository.NodeRegistryRepository.NodeRecord;
import org.clinicsync.common.exception.SyncRejectedException;
import org.clinicsync.common.model.NodeRegistration;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Enrolment and lifecycle for edge nodes.
 *
 * <p>A node must be registered (public key on record) before it may push or pull. Registration is
 * idempotent for live nodes but can never resurrect a revoked node — that is what makes revocation
 * stick even if a lost device tries to re-enrol.</p>
 */
@Service
public class NodeRegistryService {

    private final NodeRegistryRepository repository;

    public NodeRegistryService(NodeRegistryRepository repository) {
        this.repository = repository;
    }

    public Optional<NodeRecord> find(String nodeId) {
        return repository.findByNodeId(nodeId);
    }

    public List<NodeRecord> list() {
        return repository.findAll();
    }

    public NodeRecord register(NodeRegistration registration) {
        Optional<NodeRecord> existing = repository.findByNodeId(registration.nodeId());

        if (existing.isEmpty()) {
            repository.insert(
                    registration.nodeId(),
                    registration.publicKey(),
                    registration.clinicScope(),
                    NodeRegistryRepository.STATUS_ACTIVE
            );
            return repository.findByNodeId(registration.nodeId()).orElseThrow();
        }

        NodeRecord node = existing.get();
        if (NodeRegistryRepository.STATUS_REVOKED.equals(node.status())) {
            throw SyncRejectedException.nodeRevoked(node.nodeId());
        }

        repository.updateCredentials(node.nodeId(), registration.publicKey(), registration.clinicScope());
        return repository.findByNodeId(node.nodeId()).orElseThrow();
    }

    public NodeRecord revoke(String nodeId) {
        repository.findByNodeId(nodeId)
                .orElseThrow(() -> SyncRejectedException.nodeNotRegistered(nodeId));
        repository.updateStatus(nodeId, NodeRegistryRepository.STATUS_REVOKED);
        return repository.findByNodeId(nodeId).orElseThrow();
    }

    /** Guard used by both sync endpoints: the node must exist and be ACTIVE. */
    public NodeRecord requireActive(String nodeId) {
        NodeRecord node = repository.findByNodeId(nodeId)
                .orElseThrow(() -> SyncRejectedException.nodeNotRegistered(nodeId));
        if (!NodeRegistryRepository.STATUS_ACTIVE.equals(node.status())) {
            throw SyncRejectedException.nodeRevoked(nodeId);
        }
        return node;
    }
}
