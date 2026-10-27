package org.clinicsync.central.controller;

import org.clinicsync.central.repository.CentralResourceRepository;
import org.clinicsync.common.fhir.FhirUtil;
import org.clinicsync.common.model.ResourceVersionRecord;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/fhir/{resourceType}")
public class CentralFhirController {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "Patient", "Encounter", "Observation", "AllergyIntolerance", "Consent", "AuditEvent"
    );

    private final CentralResourceRepository repository;

    public CentralFhirController(CentralResourceRepository repository) {
        this.repository = repository;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(
            @PathVariable("resourceType") String resourceType,
            @RequestBody String bodyJson
    ) {
        verifySupported(resourceType);
        ResourceVersionRecord created = repository.createResource(resourceType, bodyJson, "central_admin");

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/fhir/" + resourceType + "/" + created.resourceId()));
        headers.setETag("\"" + created.versionId() + "\"");

        return new ResponseEntity<>(created.contentJson(), headers, HttpStatus.CREATED);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> update(
            @PathVariable("resourceType") String resourceType,
            @PathVariable("id") String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody String bodyJson
    ) {
        verifySupported(resourceType);
        ResourceVersionRecord updated = repository.updateResource(resourceType, id, ifMatch, bodyJson, "central_admin");

        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"" + updated.versionId() + "\"");

        return new ResponseEntity<>(updated.contentJson(), headers, HttpStatus.OK);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> read(
            @PathVariable("resourceType") String resourceType,
            @PathVariable("id") String id
    ) {
        verifySupported(resourceType);
        ResourceVersionRecord record = repository.readResource(resourceType, id);

        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"" + record.versionId() + "\"");

        return new ResponseEntity<>(record.contentJson(), headers, HttpStatus.OK);
    }

    @GetMapping(value = "/{id}/_history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> history(
            @PathVariable("resourceType") String resourceType,
            @PathVariable("id") String id
    ) {
        verifySupported(resourceType);
        List<ResourceVersionRecord> versions = repository.getHistory(resourceType, id);
        Bundle bundle = FhirUtil.createHistoryBundle(resourceType, id, versions);

        return ResponseEntity.ok(FhirUtil.toJson(bundle));
    }

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> delete(
            @PathVariable("resourceType") String resourceType,
            @PathVariable("id") String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        verifySupported(resourceType);
        ResourceVersionRecord tombstone = repository.deleteResource(resourceType, id, ifMatch, "central_admin");

        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"" + tombstone.versionId() + "\"");

        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }

    private void verifySupported(String resourceType) {
        if (!SUPPORTED_TYPES.contains(resourceType)) {
            throw new org.clinicsync.common.exception.InvalidFhirException("Unsupported FHIR resource type: " + resourceType);
        }
    }
}
