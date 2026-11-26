package top.weixiansen574.hybridfilexfer.core.ipc;

import com.google.gson.JsonElement;

public class JsonRpcError {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static final int UNAUTHORIZED = -32001;
    public static final int INVALID_STATE = -32002;
    public static final int PAIRING_FAILED = -32003;
    public static final int CONNECTION_FAILED = -32004;
    public static final int IO_ERROR = -32005;

    public int code;
    public String message;
    public JsonElement data;

    public JsonRpcError() {
    }

    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    public JsonRpcError(int code, String message, JsonElement data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }
}
