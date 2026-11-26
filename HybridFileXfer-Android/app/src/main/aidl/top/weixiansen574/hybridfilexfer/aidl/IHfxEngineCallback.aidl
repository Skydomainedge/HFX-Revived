package top.weixiansen574.hybridfilexfer.aidl;

/**
 * Asynchronous Engine -> Display Layer event stream callback aligned 1:1 with :core/ipc IpcEvents.
 */
oneway interface IHfxEngineCallback {
    void onEvent(String eventName, String payloadJson);
}
