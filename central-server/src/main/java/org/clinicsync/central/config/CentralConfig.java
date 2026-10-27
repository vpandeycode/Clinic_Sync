package org.clinicsync.central.config;

import org.clinicsync.common.hlc.HybridLogicalClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central-side clock: every write performed directly on the central server is stamped with this
 * HLC so ordering is comparable with edge-originated changes.
 */
@Configuration
public class CentralConfig {

    @Bean
    public HybridLogicalClock centralClock(@Value("${clinic.node-id:central}") String nodeId) {
        return new HybridLogicalClock(nodeId);
    }
}
