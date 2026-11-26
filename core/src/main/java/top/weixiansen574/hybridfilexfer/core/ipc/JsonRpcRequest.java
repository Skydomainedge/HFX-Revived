package top.weixiansen574.hybridfilexfer.core.ipc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class JsonRpcRequest {
    public String jsonrpc = "2.0";
    public JsonElement id;
    public String method;
    public JsonObject params;
    public String token;

    public JsonRpcRequest() {
    }

    public JsonRpcRequest(JsonElement id, String method, JsonObject params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }
}
