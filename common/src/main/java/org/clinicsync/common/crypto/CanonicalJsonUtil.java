package org.clinicsync.common.crypto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Deterministic Canonical JSON serializer (RFC 8785 compliant principles).
 * Ensures identical cryptographic byte representation across heterogeneous systems.
 */
public final class CanonicalJsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJsonUtil() {}

    public static byte[] canonicalize(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode sorted = sort(root);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonGenerator generator = MAPPER.getFactory().createGenerator(out);
            MAPPER.writeTree(generator, sorted);
            generator.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to canonicalize JSON: " + e.getMessage(), e);
        }
    }

    public static String canonicalizeToString(String json) {
        return new String(canonicalize(json), StandardCharsets.UTF_8);
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sortedNode = MAPPER.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);

            for (String fieldName : fieldNames) {
                sortedNode.set(fieldName, sort(node.get(fieldName)));
            }
            return sortedNode;
        } else if (node.isArray()) {
            ArrayNode sortedArray = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                sortedArray.add(sort(child));
            }
            return sortedArray;
        }
        return node;
    }
}
