package top.weixiansen574.hybridfilexfer.core.ipc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Single-line NDJSON-safe JSON serializer shared across PC and Android IPC layers.
 */
public final class IpcJson {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private IpcJson() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static JsonElement toJsonTree(Object value) {
        return GSON.toJsonTree(value);
    }

    public static JsonObject toJsonObject(Object value) {
        JsonElement tree = GSON.toJsonTree(value);
        if (tree == null || !tree.isJsonObject()) {
            return new JsonObject();
        }
        return tree.getAsJsonObject();
    }

    public static <T> T fromJson(String json, Class<T> clazz) throws JsonSyntaxException {
        return GSON.fromJson(json, clazz);
    }

    public static <T> T fromJson(JsonElement element, Class<T> clazz) throws JsonSyntaxException {
        return GSON.fromJson(element, clazz);
    }

    public static JsonElement parse(String json) throws JsonSyntaxException {
        return JsonParser.parseString(json);
    }

    public static JsonRpcNotification event(String eventName, Object payload) {
        return new JsonRpcNotification(eventName, payload == null ? new JsonObject() : toJsonTree(payload));
    }

    public static String encodeEvent(String eventName, Object payload) {
        return toJson(event(eventName, payload));
    }
}
