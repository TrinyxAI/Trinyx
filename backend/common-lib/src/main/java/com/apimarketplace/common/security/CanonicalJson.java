package com.apimarketplace.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Stable recursive JSON canonicalization used for projection equivocation detection. */
public final class CanonicalJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CanonicalJson() {}

    public static byte[] bytes(JsonNode value) {
        try {
            return JSON.writeValueAsBytes(sort(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not canonicalize JSON", e);
        }
    }

    public static String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sort(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) sorted.set(name, sort(value.get(name)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(sort(item)));
            return array;
        }
        return value.deepCopy();
    }
}
