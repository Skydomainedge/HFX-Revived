package top.weixiansen574.hybridfilexfer.core.ipc;

import com.google.gson.JsonElement;

/**
 * JSON-RPC 2.0 notification (no {@code id} field) used for Engine -> Display Layer event streaming.
 */
public class JsonRpcNotification {
    public String jsonrpc = "2.0";
    public String method;
    public JsonElement params;

    public JsonRpcNotification() {
    }

    public JsonRpcNotification(String method, JsonElement params) {
        this.method = method;
        this.params = params;
    }
}
