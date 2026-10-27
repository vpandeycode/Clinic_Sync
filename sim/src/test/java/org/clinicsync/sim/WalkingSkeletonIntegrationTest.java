package org.clinicsync.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.clinicsync.central.CentralServerApplication;
import org.clinicsync.common.model.SyncAck;
import org.clinicsync.edge.repository.EdgeResourceRepository;
import org.clinicsync.edge.service.EdgeSyncClient;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CentralServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class WalkingSkeletonIntegrationTest {

    @LocalServerPort
    private int centralPort;

    @Autowired
    private RestClient.Builder restClientBuilder;

    private EdgeResourceRepository edgeRepository;
    private EdgeSyncClient edgeSyncClient;
    private RestClient centralRestClient;

    @BeforeEach
    void setUp() {
        // Setup isolated in-memory H2 database for Edge Node A
        String edgeDbUrl = "jdbc:h2:mem:edge_sim_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcDataSource edgeDataSource = new JdbcDataSource();
        edgeDataSource.setURL(edgeDbUrl);
        edgeDataSource.setUser("sa");
        edgeDataSource.setPassword("");

        // Run Edge Flyway migrations
        Flyway edgeFlyway = Flyway.configure()
                .dataSource(edgeDataSource)
                .locations("classpath:db/migration/edge")
                .load();
        edgeFlyway.migrate();

        JdbcClient edgeJdbcClient = JdbcClient.create(edgeDataSource);
        edgeRepository = new EdgeResourceRepository(edgeJdbcClient);

        String centralUrl = "http://localhost:" + centralPort;
        RestClient client = restClientBuilder.build();
        edgeSyncClient = new EdgeSyncClient(edgeRepository, client, "clinic-alpha", "clinic-alpha", centralUrl);
        centralRestClient = RestClient.builder().baseUrl(centralUrl).build();
    }

    @Test
    @DisplayName("Walking Skeleton: Patient created on Edge Node A is pushed to Central Server and verified via Central FHIR API")
    void testPatientWalkingSkeletonFlow() {
        String patientId = "patient-alpha-001";
        String patientJson = """
                {
                    "resourceType": "Patient",
                    "id": "%s",
                    "name": [{"family": "Musa", "given": ["Fatima"]}],
                    "gender": "female",
                    "birthDate": "1988-11-23"
                }
                """.formatted(patientId);

        // 1. Edge creates patient locally
        long originSeq = edgeRepository.saveLocalResource(
                "Patient",
                patientId,
                "1",
                patientJson,
                null,
                "clinic-alpha",
                System.currentTimeMillis() + ":0:clinic-alpha",
                "nurse-joy",
                false
        );
        assertThat(originSeq).isEqualTo(1L);

        // Verify edge stored the patient
        String localContent = edgeRepository.getResourceHeadContent("Patient", patientId).orElseThrow();
        assertThat(localContent).contains("Fatima");

        // 2. Edge triggers push to Central Server
        SyncAck ack = edgeSyncClient.pushPendingChanges();

        // 3. Verify Central acknowledged the batch
        assertThat(ack).isNotNull();
        assertThat(ack.status()).isEqualTo("APPLIED");
        assertThat(ack.highestContiguousOriginSeq()).isEqualTo(1L);
        assertThat(ack.appliedCount()).isEqualTo(1);

        // Verify Edge advanced its cursor
        assertThat(edgeRepository.getLastPushedSeq("central")).isEqualTo(1L);

        // 4. Query Central Server directly via its FHIR REST endpoint
        String centralPatientJson = centralRestClient.get()
                .uri("/fhir/Patient/" + patientId)
                .retrieve()
                .body(String.class);

        assertThat(centralPatientJson).isNotNull();
        assertThat(centralPatientJson).contains("Musa");
        assertThat(centralPatientJson).contains("Fatima");
        assertThat(centralPatientJson).contains("1988-11-23");

        // 5. Test Idempotency: Trigger push again; no duplicate entries or errors
        SyncAck secondAck = edgeSyncClient.pushPendingChanges();
        assertThat(secondAck.appliedCount()).isEqualTo(0);
        assertThat(secondAck.status()).isEqualTo("UP_TO_DATE");
    }
}
