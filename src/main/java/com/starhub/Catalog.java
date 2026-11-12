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
    // Curated picker; the full index remains available for existing conversations and jobs.
    static JsonNode publicData() {
        String[] ids={
            "openai|gpt-6.1-sol", "openai|gpt-6-astra", "openai|gpt-6-luna",
            "openai|gpt-image-2.5-sunburst", "openai|gpt-4o-mini-tts", "openai|gpt-4o-transcribe", "openai|text-embedding-3-large", "openai|gpt-realtime-2.1",
            "anthropic|claude-opus-5-5", "anthropic|claude-fable-5-1", "anthropic|claude-sonnet-5-5",
            "gemini|gemini-4-argon", "gemini|gemini-3.8-flash", "gemini|gemini-3.5-flash-lite",
            "deepseek|deepseek-v4-pro", "deepseek|deepseek-flash",
            "qwen|qwen3.8-max", "qwen|qwen3.7-plus", "qwen|qwen3.8-flash",
            "moonshot|kimi-k3.1-pro", "moonshot|kimi-k3", "xai|grok-4.7"
        };
        com.fasterxml.jackson.databind.node.ObjectNode result=DATA.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode models=result.putArray("models");
        for(String id:ids){JsonNode model=INDEX.get(id);if(model==null)throw new IllegalStateException("Missing curated model: "+id);models.add(model.deepCopy());}
        return result;
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
