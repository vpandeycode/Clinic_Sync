package org.clinicsync.common.sync;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure logic over the version DAG stored in {@code resource_head.head_version_ids}.
 *
 * <p>Head version ids are stored as a comma-separated list of leaf version ids. A new version
 * supersedes every head listed in its {@code parent_version_ids}; heads that are not parents stay
 * leaves, which means a concurrent branch is preserved instead of overwritten.</p>
 */
public final class VersionGraph {

    private VersionGraph() {}

    /** Result of applying a new version to the current head set. */
    public record HeadUpdate(String headVersionIds, boolean hasConflict) {}

    /** Parses a comma-separated id list into an ordered set; null/blank yields an empty set. */
    public static Set<String> ids(String commaSeparated) {
        Set<String> result = new LinkedHashSet<>();
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return result;
        }
        for (String part : commaSeparated.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** Serializes ids back to the canonical comma-separated storage form (sorted for determinism). */
    public static String join(Collection<String> versionIds) {
        return String.join(",", new TreeSet<>(versionIds));
    }

    /**
     * Computes the next per-resource version ordinal from a head id list.
     * Version ids have the form {@code <ordinal>-<originNodeId>}, so concurrent branches produced by
     * different nodes share an ordinal but never a full id.
     */
    public static int nextOrdinal(String headVersionIds) {
        int max = 0;
        for (String id : ids(headVersionIds)) {
            String ordinalPart = id.split("-", 2)[0];
            try {
                max = Math.max(max, Integer.parseInt(ordinalPart.trim()));
            } catch (NumberFormatException ignored) {
                // Unknown id shape contributes no ordinal.
            }
        }
        return max + 1;
    }

    /**
     * Applies {@code newVersionId} (whose parents are {@code parentVersionIds}) to the current head.
     *
     * <ul>
     *   <li>Empty current head (resource is new here): the new version becomes the only head.</li>
     *   <li>Every current head is a parent: fast-forward, the new version is the only head.</li>
     *   <li>Some heads are not parents: those heads survive as concurrent leaves and the update is
     *       flagged as a conflict so it can be routed to human review.</li>
     * </ul>
     */
    public static HeadUpdate apply(String currentHeadVersionIds, String parentVersionIds, String newVersionId) {
        Set<String> head = ids(currentHeadVersionIds);
        if (head.isEmpty()) {
            return new HeadUpdate(newVersionId, false);
        }

        Set<String> parents = ids(parentVersionIds);
        Set<String> newHead = new LinkedHashSet<>(head);
        newHead.removeAll(parents);
        newHead.add(newVersionId);

        boolean conflict = newHead.size() > 1;
        return new HeadUpdate(join(newHead), conflict);
    }

    /** True when {@code versionId} is already part of the head set. */
    public static boolean isHead(String headVersionIds, String versionId) {
        return ids(headVersionIds).contains(versionId);
    }
}
