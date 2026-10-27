package org.clinicsync.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    @Test
    @DisplayName("Generated UUID should have version 7 and variant 2")
    void testVersionAndVariant() {
        UUID uuid = UuidV7Generator.generate();
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);

        long now = System.currentTimeMillis();
        long extracted = UuidV7Generator.extractTimestampMs(uuid);
        assertThat(extracted).isCloseTo(now, org.assertj.core.data.Offset.offset(2000L));
    }

    @Test
    @DisplayName("Successive UUIDv7s should be monotonically ordered")
    void testMonotonicOrdering() {
        List<UUID> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            list.add(UuidV7Generator.generate());
        }

        List<UUID> sorted = new ArrayList<>(list);
        Collections.sort(sorted);

        assertThat(list).isEqualTo(sorted);
    }
}
