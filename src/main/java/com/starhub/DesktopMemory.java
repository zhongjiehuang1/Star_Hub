package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;
import java.util.Properties;

final class DesktopMemory {
    private static final String PURPOSE = "starhub/desktop-memory/v1";
    private static final long LIFETIME = 90L * 24 * 3600;

    static String issue(Properties config, long user, String generation, String password, Instant now) throws Exception {
        return DesktopScope.issue(config, user, generation, password, now, PURPOSE, LIFETIME);
    }

    static JsonNode decode(Properties config, String header, Instant now) {
        return header.startsWith("Bearer ") ? DesktopScope.decode(config, header.substring(7), now, PURPOSE, LIFETIME) : null;
    }

    static ObjectNode apply(Connection db, Properties config, JsonNode grant, JsonNode input) throws Exception {
        String owner = grant.path("generation").asText();
        long user = grant.path("user").asLong();
        try (var query = db.prepareStatement("SELECT generation,password_hash FROM users WHERE id=? AND active=1 FOR UPDATE")) {
            query.setLong(1, user);
            try (var row = query.executeQuery()) {
                if (!row.next() || !owner.equals(row.getString(1)) ||
                    !DesktopConnection.constantEquals(grant.path("password").asText(), DesktopConnection.digest(row.getBytes(2))))
                    throw new StorageFailure(401,"CONNECTION_EXPIRED","Refresh your desktop connection.");
            }
        }
        String action = input.path("action").asText();
        long revision = input.path("revision").isIntegralNumber() ? input.path("revision").asLong(-1) : -1;
        byte[] key = DesktopConnection.vault(config);
        String id = input.path("id").asText(), text = input.path("text").textValue();
        int fields = switch (action) {
            case "read" -> 1;
            case "delete" -> 3;
            case "add", "edit" -> 4;
            case "enabled" -> 3;
            default -> 0;
        };
        if (fields == 0 || input.size() != fields || (!action.equals("read") && revision < 0))
            throw new StorageFailure(400,"INVALID_MEMORY","Invalid memory request.");
        switch (action) {
            case "enabled" -> {
                if (!input.path("enabled").isBoolean()) throw new StorageFailure(400,"INVALID_MEMORY","Invalid setting.");
                AccountMemories.setEnabled(db,user,owner,revision,input.get("enabled").booleanValue());
            }
            case "delete" -> AccountMemories.delete(db,user,owner,revision,id);
            case "edit" -> {
                AccountMemories.requireEnabledRevision(db,user,owner,revision);
                AccountMemories.edit(db,user,owner,revision,id,text,key);
            }
            case "add" -> AccountMemories.addNote(db,user,owner,revision,id,text,key);
        }
        return AccountMemories.state(db,user,owner,key).put("owner",owner);
    }
}
