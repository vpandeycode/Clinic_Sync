package org.clinicsync.common.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionGraphTest {

    @Test
    @DisplayName("First version on an empty head becomes the only head without conflict")
    void createOnEmptyHead() {
        VersionGraph.HeadUpdate update = VersionGraph.apply(null, null, "1-clinic-alpha");
        assertThat(update.headVersionIds()).isEqualTo("1-clinic-alpha");
        assertThat(update.hasConflict()).isFalse();
    }

    @Test
    @DisplayName("Fast-forward: child of the current head replaces it")
    void fastForward() {
        VersionGraph.HeadUpdate update = VersionGraph.apply("1-clinic-alpha", "1-clinic-alpha", "2-clinic-alpha");
        assertThat(update.headVersionIds()).isEqualTo("2-clinic-alpha");
        assertThat(update.hasConflict()).isFalse();
    }

    @Test
    @DisplayName("Concurrent branch: a version whose parent is not the head keeps both versions")
    void concurrentBranchKeepsBothVersions() {
        VersionGraph.HeadUpdate update = VersionGraph.apply("2-clinic-alpha", "1-clinic-alpha", "2-clinic-beta");
        assertThat(VersionGraph.ids(update.headVersionIds()))
                .containsExactlyInAnyOrder("2-clinic-alpha", "2-clinic-beta");
        assertThat(update.hasConflict()).isTrue();
    }

    @Test
    @DisplayName("Merge version whose parents cover every head resolves to a single head")
    void mergeOfAllHeadsResolvesConflict() {
        VersionGraph.HeadUpdate update = VersionGraph.apply(
                "2-clinic-alpha,2-clinic-beta", "1-clinic-alpha,2-clinic-alpha,2-clinic-beta", "3-clinic-beta");
        assertThat(update.headVersionIds()).isEqualTo("3-clinic-beta");
        assertThat(update.hasConflict()).isFalse();
    }

    @Test
    @DisplayName("Version based on one of two heads keeps the other head as a concurrent leaf")
    void partialParentKeepsUntouchedHead() {
        VersionGraph.HeadUpdate update = VersionGraph.apply("2-clinic-alpha,2-clinic-beta", "2-clinic-alpha", "3-clinic-alpha");
        assertThat(VersionGraph.ids(update.headVersionIds()))
                .containsExactlyInAnyOrder("2-clinic-beta", "3-clinic-alpha");
        assertThat(update.hasConflict()).isTrue();
    }

    @Test
    @DisplayName("Next version ordinal is max head ordinal + 1 regardless of branch shape")
    void nextOrdinalAcrossBranches() {
        assertThat(VersionGraph.nextOrdinal(null)).isEqualTo(1);
        assertThat(VersionGraph.nextOrdinal("1-clinic-alpha")).isEqualTo(2);
        assertThat(VersionGraph.nextOrdinal("2-clinic-alpha,2-clinic-beta")).isEqualTo(3);
        assertThat(VersionGraph.nextOrdinal("2-clinic-alpha,7-clinic-beta")).isEqualTo(8);
    }

    @Test
    @DisplayName("Head id lists round-trip through ids()/join() deterministically")
    void joinIsDeterministic() {
        assertThat(VersionGraph.join(VersionGraph.ids("2-b,1-a"))).isEqualTo("1-a,2-b");
        assertThat(VersionGraph.join(VersionGraph.ids(null))).isEmpty();
    }
}
