package top.weixiansen574.hybridfilexfer.core.ipc;

import com.google.gson.JsonElement;

public class JsonRpcResponse {
    public String jsonrpc = "2.0";
    public JsonElement id;
    public JsonElement result;
    public JsonRpcError error;

    public JsonRpcResponse() {
    }

    public static JsonRpcResponse success(JsonElement id, JsonElement result) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.id = id;
        resp.result = result;
        return resp;
    }

    public static JsonRpcResponse error(JsonElement id, int code, String message) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.id = id;
        resp.error = new JsonRpcError(code, message);
        return resp;
    }

    public static JsonRpcResponse error(JsonElement id, JsonRpcError error) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.id = id;
        resp.error = error;
        return resp;
    }

    public boolean isSuccess() {
        return error == null;
    }
}
