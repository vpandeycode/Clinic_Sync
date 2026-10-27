package org.clinicsync.edge.config;

import org.clinicsync.common.hlc.HybridLogicalClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Edge-side clock. Every local write is stamped with this HLC and it is merged with the HLC of
 * every change pulled from central, which keeps causal order across offline partitions.
 */
@Configuration
public class EdgeConfig {

    @Bean
    public HybridLogicalClock edgeClock(@Value("${clinic.node-id}") String nodeId) {
        return new HybridLogicalClock(nodeId);
    }
}
