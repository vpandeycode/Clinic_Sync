package org.clinicsync.edge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.clinicsync.edge.repository.EdgeResourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/fhir/Patient")
public class EdgePatientController {

    private final EdgeResourceRepository repository;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    public EdgePatientController(
            EdgeResourceRepository repository,
            ObjectMapper objectMapper,
            @Value("${clinic.node-id}") String nodeId
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createPatient(@RequestBody String patientJson) throws Exception {
        JsonNode root = objectMapper.readTree(patientJson);
        String patientId;
        if (root.hasNonNull("id")) {
            patientId = root.get("id").asText();
        } else {
            patientId = UUID.randomUUID().toString();
            ((ObjectNode) root).put("id", patientId);
        }

        String versionId = "1";
        String hlc = Instant.now().toEpochMilli() + ":0:" + nodeId;
        String finalJson = objectMapper.writeValueAsString(root);

        repository.saveLocalResource(
                "Patient",
                patientId,
                versionId,
                finalJson,
                null,
                nodeId,
                hlc,
                "clinician",
                false
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/fhir/Patient/" + patientId));
        headers.setETag("\"" + versionId + "\"");

        return new ResponseEntity<>(finalJson, headers, HttpStatus.CREATED);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPatient(@PathVariable("id") String id) {
        return repository.getResourceHeadContent("Patient", id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
