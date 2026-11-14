package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.util.*;

final class Catalog {
    static final JsonNode DATA;
    private static final Map<String, JsonNode> INDEX = new HashMap<>();
    static {
        try (InputStream stream = Catalog.class.getResourceAsStream("/catalog.json")) {
            DATA = ModelGateway.JSON.readTree(Objects.requireNonNull(stream));
            ApiRoutes.enrich(DATA);
            DATA.path("models").forEach(m -> INDEX.put(m.path("uid").asText(), m));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    // Only the reviewed current catalog is callable; saved conversation history stays unchanged.
    static JsonNode publicData() { return DATA.deepCopy(); }
    static JsonNode find(String provider, String model) {
        JsonNode item = INDEX.get(provider + "|" + model);
        if (item == null) throw new ModelGateway.Problem(400, "模型不在当前目录中，请重新选择。");
        return item;
    }
    static boolean contains(JsonNode values, String wanted) {
        for (JsonNode value : values) if (value.asText().equals(wanted)) return true;
        return false;
    }
    static String effort(JsonNode request, JsonNode item) {
        String effort = request.path("effort").asText("default");
        if (effort.equals("default") || effort.isBlank()) return "";
        if (!contains(item.path("reasoning"), effort)) throw new ModelGateway.Problem(400, "此模型不支持所选推理强度。");
        return effort;
    }
}
