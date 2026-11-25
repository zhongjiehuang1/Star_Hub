package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;
import java.util.function.Predicate;

final class Catalog {
    static final String OPEN_PROVIDER = "openai";
    static final JsonNode DATA;
    private static final Map<String, JsonNode> INDEX = new HashMap<>();
    static {
        try (InputStream stream = Catalog.class.getResourceAsStream("/catalog.json")) {
            DATA = ModelGateway.JSON.readTree(Objects.requireNonNull(stream));
            ApiRoutes.enrich(DATA);
            DATA.path("models").forEach(m -> INDEX.put(m.path("uid").asText(), m));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    static boolean isOpen(String provider) { return OPEN_PROVIDER.equals(provider); }

    static void requireOpen(String provider) throws StorageFailure {
        if (!isOpen(provider)) throw new StorageFailure(403, "PROVIDER_DISABLED", "Star Hub 当前仅开放 OpenAI 模型。");
    }

    static JsonNode publicData() {
        ObjectNode published = DATA.deepCopy();
        published.set("models", filter(published.path("models"), model -> isOpen(model.path("provider").asText())));
        published.set("providers", filter(published.path("providers"), provider -> isOpen(provider.path("id").asText())));
        published.set("sources", filter(published.path("sources"), source -> "OpenAI".equals(source.path("name").asText())));
        for (String name : List.of("primary", "secondary", "preferredDefault")) {
            if (published.path("featured").path(name) instanceof ObjectNode choices) choices.retain(OPEN_PROVIDER);
        }
        return published;
    }

    static ArrayNode publicProviders() {
        return filter(ModelGateway.catalogue(), provider -> isOpen(provider.path("id").asText()));
    }

    private static ArrayNode filter(JsonNode entries, Predicate<JsonNode> keep) {
        ArrayNode out = ModelGateway.JSON.createArrayNode();
        for (JsonNode entry : entries) if (keep.test(entry)) out.add(entry);
        return out;
    }
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
