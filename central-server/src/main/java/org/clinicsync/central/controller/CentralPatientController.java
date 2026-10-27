package org.clinicsync.central.controller;

import org.clinicsync.central.service.CentralSyncService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fhir/Patient")
public class CentralPatientController {

    private final CentralSyncService syncService;

    public CentralPatientController(CentralSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPatient(@PathVariable("id") String id) {
        return syncService.getResourceHeadContent("Patient", id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
