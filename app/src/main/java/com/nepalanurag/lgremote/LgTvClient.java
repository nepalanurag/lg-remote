package com.nepalanurag.lgremote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * LG webOS SSAP client. Pairing is register-with-manifest, exactly as lgtv2 does; buttons are
 * sent over the separate pointer socket (type:button / type:move / type:click / type:scroll).
 */
public final class LgTvClient {
    private static final String TAG = "LgRemote";
    private static final long REQUEST_TIMEOUT_MS = 15_000;

    public enum State { IDLE, CONNECTING, PAIRING, READY, RECONNECTING, ERROR }

    public static final class TvApp {
        public final String id;
        public final String title;

        TvApp(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    public static final class TvInput {
        public final String id;
        public final String appId;
        public final String label;
        public final boolean connected;

        TvInput(String id, String appId, String label, boolean connected) {
            this.id = id;
            this.appId = appId;
            this.label = label;
            this.connected = connected;
        }
    }

    public interface Listener {
        void onState(State state, String detail);

        void onTvInfo(String name, String model);

        void onVolume(int volume, boolean muted);

        void onApps(List<TvApp> apps);

        void onInputs(List<TvInput> inputs);

        /** The app id of the window currently in front, e.g. com.webos.app.hdmi2. */
        void onForegroundApp(String appId);

        /** The raw webOS power state, e.g. Active, Active Standby, Suspend or Screen Off. */
        void onTvPower(String state);

        /**
         * The TV's IME subscription says a text field has focus. Content type is webOS's hint
         * ("text", "number" or "url"); hidden fields never prompt the phone keyboard.
         */
        void onTextField(boolean focused, String contentType, boolean hidden);

        void onClientKey(String clientKey);

        void onMac(String mac);

        void onPointer(boolean ready);
    }

    private interface Callback {
        void onSuccess(JsonObject payload);

        void onError(String message);
    }

    /** Delivered on the main thread after the TV answers an IME command. */
    public interface TextCallback {
        void onResult(boolean ok, String error);
    }

    private static final class Pending {
        final Callback callback;
        final boolean persistent;
        Runnable timeout;

        Pending(Callback callback, boolean persistent) {
            this.callback = callback;
            this.persistent = persistent;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random jitter = new SecureRandom();
    private final OkHttpClient http;
    private final String pairingJson;
    private final Listener listener;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private WebSocket socket;
    private WebSocket pointerSocket;
    private String ip;
    private String clientKey;
    private String registerId;
    private boolean userStopped = true;
    private boolean registered;
    private volatile boolean pointerReady;
    private boolean opened;
    private int attempt;
    private int generation;
    private Boolean secure;
    private int candidate;
    private State state = State.IDLE;
    private String stateDetail = "";

    private Runnable handshakeTimeout;
    private Runnable reconnectRunnable;
    private Runnable pointerRetryRunnable;

    public LgTvClient(String pairingJson, Listener listener) {
        this.pairingJson = pairingJson;
        this.listener = listener;
        this.http = buildHttpClient();
    }

    private static OkHttpClient buildHttpClient() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(context.getSocketFactory(), trustAll)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) {
            // TLS setup is available on every Android version this app supports. If it somehow
            // fails, the raw ws:// fallback (port 3000) still works.
            return new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .build();
        }
    }

    public State getState() {
        return state;
    }

    public String getStateDetail() {
        return stateDetail;
    }

    public boolean isConnected() {
        return registered;
    }

    public boolean isPointerReady() {
        return pointerReady;
    }

    public String getIp() {
        return ip;
    }

    public void connect(String ip, String clientKey) {
        this.ip = ip;
        this.clientKey = clientKey;
        this.userStopped = false;
        this.attempt = 0;
        this.candidate = 0;
        this.secure = null;
        cancelTimers();
        closeSocket();
        closePointer();
        startAttempt();
    }

    public void reconnectNow() {
        if (ip == null) {
            return;
        }
        userStopped = false;
        cancelTimers();
        closeSocket();
        closePointer();
        attempt = 0;
        candidate = 0;
        registered = false;
        pointerReady = false;
        startAttempt();
    }

    public void disconnect() {
        userStopped = true;
        generation++;
        cancelTimers();
        failPending("Disconnected");
        closePointer();
        closeSocket();
        registered = false;
        pointerReady = false;
        postState(State.IDLE, "Disconnected");
    }

    private void startAttempt() {
        if (userStopped || ip == null) {
            return;
        }
        final int gen = ++generation;
        opened = false;
        registered = false;
        pointerReady = false;

        String url = buildUrl();
        if (attempt == 0 && candidate == 0) {
            postState(State.CONNECTING, "Connecting to " + ip + "…");
        } else {
            postState(State.RECONNECTING, "Reconnecting to " + ip + "…");
        }
        Log.d(TAG, "connect attempt " + attempt + " -> " + url);

        Request request = new Request.Builder().url(url).build();
        final WebSocket ws = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                main.post(() -> handleOpen(gen, webSocket));
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                main.post(() -> handleMessage(gen, webSocket, text));
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                main.post(() -> handleDrop(gen, webSocket, "closed " + code));
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                main.post(() -> handleDrop(gen, webSocket,
                        t == null ? "connection failed" : t.getClass().getSimpleName()));
            }
        });
        socket = ws;
    }

    private String buildUrl() {
        boolean useSecure = secure != null ? secure : candidate == 0;
        return (useSecure ? "wss://" : "ws://") + ip + (useSecure ? ":3001" : ":3000");
    }

    private void handleOpen(int gen, WebSocket ws) {
        if (gen != generation || ws != socket) {
            ws.cancel();
            return;
        }
        opened = true;
        Log.d(TAG, "websocket open");
        sendRegister(gen);
        scheduleHandshakeTimeout(gen, clientKey == null ? 90_000 : 18_000);
    }

    private void sendRegister(int gen) {
        String id = "register_" + sequence.incrementAndGet();
        registerId = id;
        Pending p = new Pending(new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
            }

            @Override
            public void onError(String message) {
                if (!registered) {
                    Log.d(TAG, "register failed");
                    postState(State.ERROR, "Pairing was not approved on the TV. Tap Retry.");
                    cancelTimers();
                    closeSocket();
                }
            }
        }, false);
        pending.put(id, p);
        if (socket != null) {
            socket.send(Protocol.registerPayload(pairingJson, clientKey, id));
            postState(clientKey == null ? State.PAIRING : State.CONNECTING,
                    clientKey == null ? "Approve the request on your TV" : "Signing in…");
        }
    }

    private void scheduleHandshakeTimeout(int gen, long ms) {
        cancelHandshakeTimeout();
        handshakeTimeout = () -> {
            if (gen == generation && !registered) {
                Log.d(TAG, "handshake timeout");
                if (clientKey == null && opened) {
                    postState(State.ERROR, "No answer from the TV. Accept the request on the TV, then retry.");
                    closeSocket();
                } else {
                    dropAndReconnect(gen, socket, "handshake timeout");
                }
            }
        };
        main.postDelayed(handshakeTimeout, ms);
    }

    private void cancelHandshakeTimeout() {
        if (handshakeTimeout != null) {
            main.removeCallbacks(handshakeTimeout);
            handshakeTimeout = null;
        }
    }

    private void handleMessage(int gen, WebSocket ws, String text) {
        if (gen != generation || ws != socket) {
            return;
        }
        JsonObject message;
        try {
            message = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        String type = str(message, "type");
        String id = str(message, "id");
        JsonObject payload = message.has("payload") && message.get("payload").isJsonObject()
                ? message.getAsJsonObject("payload") : new JsonObject();

        if ("registered".equals(type)) {
            onRegistered(payload);
            return;
        }
        if ("hello".equals(type)) {
            if (!registered) {
                sendRegister(gen);
            }
            return;
        }
        if ("error".equals(type)) {
            Pending p = id == null ? null : pending.remove(id);
            if (p != null) {
                p.callback.onError(str(message, "error"));
            } else if (!registered) {
                postState(State.ERROR, "Pairing was not approved on the TV. Tap Retry.");
            }
            return;
        }
        if ("response".equals(type) || "subscribe".equals(type)) {
            Pending p = id == null ? null : pending.get(id);
            if (p == null) {
                return;
            }
            if (!p.persistent) {
                pending.remove(id);
                if (p.timeout != null) {
                    main.removeCallbacks(p.timeout);
                }
            }
            if (payload.has("returnValue") && payload.get("returnValue").isJsonPrimitive()
                    && !payload.get("returnValue").getAsBoolean()) {
                String error = str(payload, "errorText");
                if (error == null) {
                    error = str(payload, "errorCode");
                }
                p.callback.onError(error == null ? "The TV rejected the request" : error);
                return;
            }
            p.callback.onSuccess(payload);
        }
    }

    private void onRegistered(JsonObject payload) {
        registered = true;
        pointerReady = false;
        attempt = 0;
        cancelHandshakeTimeout();
        if (registerId != null) {
            Pending p = pending.remove(registerId);
            if (p != null && p.timeout != null) {
                main.removeCallbacks(p.timeout);
            }
            registerId = null;
        }
        String key = str(payload, "client-key");
        if (key != null && !key.isEmpty()) {
            clientKey = key;
            listener.onClientKey(key);
        }
        // OkHttp canonicalizes ws:// to http:// and wss:// to https:// internally.
        secure = socket != null && socket.request().url().isHttps();
        postState(State.READY, "Connected");
        Log.d(TAG, "registered; pointer socket requested");

        subscribeVolume();
        subscribeForegroundApp();
        subscribePowerState();
        subscribeRemoteKeyboard();
        requestInputs();
        openPointerSocket();
        requestSystemInfo();
        requestMac();
    }

    private void openPointerSocket() {
        if (!registered) {
            return;
        }
        request(Protocol.POINTER_SOCKET, new JsonObject(), new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                String path = str(payload, "socketPath");
                if (path == null || path.isEmpty()) {
                    Log.d(TAG, "pointer socket request gave no path: " + payload);
                    schedulePointerRetry();
                    return;
                }
                openPointer(path);
            }

            @Override
            public void onError(String message) {
                Log.d(TAG, "pointer socket request failed: " + message);
                schedulePointerRetry();
            }
        }, false);
    }

    private void schedulePointerRetry() {
        if (!registered) {
            return;
        }
        if (pointerRetryRunnable != null) {
            main.removeCallbacks(pointerRetryRunnable);
        }
        pointerRetryRunnable = () -> {
            pointerRetryRunnable = null;
            if (registered && !pointerReady) {
                openPointerSocket();
            }
        };
        main.postDelayed(pointerRetryRunnable, 2500);
    }

    private void openPointer(String path) {
        Request request;
        try {
            request = new Request.Builder().url(path).build();
        } catch (Exception e) {
            Log.d(TAG, "pointer socket has an unusable path: " + e.getMessage());
            schedulePointerRetry();
            return;
        }
        final int gen = generation;
        pointerSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                main.post(() -> {
                    if (gen != generation) {
                        webSocket.cancel();
                        return;
                    }
                    pointerReady = true;
                    listener.onPointer(true);
                    postState(State.READY, "Connected");
                    Log.d(TAG, "pointer socket open");
                });
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // The pointer socket is write-only for remotes.
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                main.post(() -> {
                    if (webSocket == pointerSocket) {
                        pointerSocket = null;
                        pointerReady = false;
                        listener.onPointer(false);
                        Log.d(TAG, "pointer socket closed: " + code + " " + reason);
                        schedulePointerRetry();
                    }
                });
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                main.post(() -> {
                    if (webSocket == pointerSocket) {
                        pointerSocket = null;
                        pointerReady = false;
                        listener.onPointer(false);
                        Log.d(TAG, "pointer socket failed: "
                                + (t == null ? "unknown" : t.getClass().getSimpleName() + " " + t.getMessage())
                                + (response == null ? "" : " HTTP " + response.code()));
                        schedulePointerRetry();
                    }
                });
            }
        });
    }

    private void subscribeVolume() {
        String id = "volume";
        Pending old = pending.remove(id);
        if (old != null && old.timeout != null) {
            main.removeCallbacks(old.timeout);
        }
        Pending p = new Pending(new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                onVolumePayload(payload);
            }

            @Override
            public void onError(String message) {
                // Some firmware does not expose getVolume; the rocker still works.
            }
        }, true);
        pending.put(id, p);
        if (socket != null) {
            socket.send(Protocol.subscribe(id, "ssap://audio/getVolume", new JsonObject()));
        }
    }

    private void subscribeForegroundApp() {
        String id = "foreground";
        Pending old = pending.remove(id);
        if (old != null && old.timeout != null) {
            main.removeCallbacks(old.timeout);
        }
        Pending p = new Pending(new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                String appId = str(payload, "appId");
                Log.d(TAG, "foreground app: " + appId);
                if (appId != null) {
                    listener.onForegroundApp(appId);
                }
            }

            @Override
            public void onError(String message) {
                Log.d(TAG, "foreground subscription failed: " + message);
            }
        }, true);
        pending.put(id, p);
        if (socket != null) {
            JsonObject payload = new JsonObject();
            payload.addProperty("subscribe", true);
            socket.send(Protocol.subscribe(id,
                    "ssap://com.webos.applicationManager/getForegroundAppInfo", payload));
        }
    }

    private void subscribePowerState() {
        String id = "power";
        Pending old = pending.remove(id);
        if (old != null && old.timeout != null) {
            main.removeCallbacks(old.timeout);
        }
        Pending p = new Pending(new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                String state = str(payload, "state");
                if (state == null || state.isEmpty()) {
                    state = str(payload, "powerState");
                }
                if (state == null || state.isEmpty()) {
                    state = nestedString(payload, "powerState", "state");
                }
                if (state != null && !state.isEmpty()) {
                    Log.d(TAG, "TV power state: " + state);
                    listener.onTvPower(state);
                }
            }

            @Override
            public void onError(String message) {
                Log.d(TAG, "power subscription failed: " + message);
            }
        }, true);
        pending.put(id, p);
        if (socket != null) {
            JsonObject payload = new JsonObject();
            payload.addProperty("subscribe", true);
            socket.send(Protocol.subscribe(id,
                    "ssap://com.webos.service.tvpower/power/getPowerState", payload));
        }
    }

    private void subscribeRemoteKeyboard() {
        String id = "ime";
        Pending old = pending.remove(id);
        if (old != null && old.timeout != null) {
            main.removeCallbacks(old.timeout);
        }
        Pending p = new Pending(new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                JsonObject widget = payload.has("currentWidget")
                        && payload.get("currentWidget").isJsonObject()
                        ? payload.getAsJsonObject("currentWidget") : null;
                boolean focused = widget != null && bool(widget, "focus");
                boolean hidden = widget != null && bool(widget, "hiddenText");
                String contentType = widget == null ? "text" : str(widget, "contentType");
                if (contentType == null || contentType.isEmpty()) {
                    contentType = "text";
                }
                listener.onTextField(focused, contentType, hidden);
            }

            @Override
            public void onError(String message) {
                Log.d(TAG, "remote keyboard subscription failed: " + message);
            }
        }, true);
        pending.put(id, p);
        if (socket != null) {
            JsonObject payload = new JsonObject();
            payload.addProperty("subscribe", true);
            socket.send(Protocol.subscribe(id,
                    "ssap://com.webos.service.ime/registerRemoteKeyboard", payload));
        }
    }

    /** Debug helper: capture the current TV or HDMI screen and return the TV-hosted image URI. */
    public interface ScreenshotCallback {
        void onResult(String imageUri, String error);
    }

    public void requestScreenshot(ScreenshotCallback callback) {
        JsonObject payload = new JsonObject();
        payload.addProperty("method", "DISPLAY");
        payload.addProperty("format", "JPG");
        request("ssap://tv/executeOneShot", payload, new Callback() {
            @Override
            public void onSuccess(JsonObject response) {
                String uri = str(response, "imageUri");
                if (uri == null || uri.isEmpty()) {
                    uri = str(response, "uri");
                }
                Log.d(TAG, "screenshot response: " + response);
                if (uri == null || uri.isEmpty()) {
                    callback.onResult(null, "The TV did not return an image URI.");
                } else {
                    callback.onResult(uri, null);
                }
            }

            @Override
            public void onError(String message) {
                callback.onResult(null, message);
            }
        }, false);
    }

    /** Inserts committed phone-keyboard text into the TV field that currently has focus. */
    public boolean sendText(String text, TextCallback callback) {
        if (text == null || text.isEmpty()) {
            result(callback, true, null);
            return true;
        }
        if (!registered || socket == null) {
            result(callback, false, "Not connected");
            return false;
        }
        request("ssap://com.webos.service.ime/insertText",
                Protocol.insertTextPayload(text), textCallback(callback), false);
        return true;
    }

    /** Deletes characters immediately before the TV field's cursor. */
    public boolean deleteCharacters(int count, TextCallback callback) {
        if (count <= 0) {
            result(callback, true, null);
            return true;
        }
        if (!registered || socket == null) {
            result(callback, false, "Not connected");
            return false;
        }
        request("ssap://com.webos.service.ime/deleteCharacters",
                Protocol.deleteCharactersPayload(count), textCallback(callback), false);
        return true;
    }

    /** Sends the enter/return key to the focused TV field. */
    public boolean sendEnterKey(TextCallback callback) {
        if (!registered || socket == null) {
            result(callback, false, "Not connected");
            return false;
        }
        request("ssap://com.webos.service.ime/sendEnterKey",
                new JsonObject(), textCallback(callback), false);
        return true;
    }

    private static Callback textCallback(TextCallback callback) {
        return new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                result(callback, true, null);
            }

            @Override
            public void onError(String message) {
                result(callback, false, message);
            }
        };
    }

    private static void result(TextCallback callback, boolean ok, String error) {
        if (callback != null) {
            callback.onResult(ok, error);
        }
    }

    private void onVolumePayload(JsonObject payload) {
        JsonObject status = payload.has("volumeStatus") && payload.get("volumeStatus").isJsonObject()
                ? payload.getAsJsonObject("volumeStatus") : null;
        JsonObject source = status != null ? status : payload;
        int volume = -1;
        boolean muted = false;
        if (source.has("volume") && !source.get("volume").isJsonNull()) {
            try {
                volume = source.get("volume").getAsInt();
            } catch (Exception ignored) {
            }
        }
        if (source.has("muteStatus") && !source.get("muteStatus").isJsonNull()) {
            try {
                muted = source.get("muteStatus").getAsBoolean();
            } catch (Exception ignored) {
            }
        } else if (source.has("muted") && !source.get("muted").isJsonNull()) {
            try {
                muted = source.get("muted").getAsBoolean();
            } catch (Exception ignored) {
            }
        }
        if (volume >= 0) {
            listener.onVolume(volume, muted);
        }
    }

    private void requestSystemInfo() {
        request("ssap://system/getSystemInfo", new JsonObject(), new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                String model = str(payload, "modelName");
                if (model == null) {
                    model = str(payload, "model");
                }
                if (model != null && !model.isEmpty()) {
                    listener.onTvInfo(null, model);
                }
            }

            @Override
            public void onError(String message) {
            }
        }, false);
    }

    private void requestMac() {
        request("ssap://com.webos.service.connectionmanager/getinfo", new JsonObject(), new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                String wifi = nestedString(payload, "wifiInfo", "macAddress");
                String wired = nestedString(payload, "wiredInfo", "macAddress");
                String mac = wifi != null && !wifi.isEmpty() ? wifi : wired;
                if (mac != null && WakeOnLan.isValidMac(mac)) {
                    listener.onMac(WakeOnLan.normalize(mac));
                }
            }

            @Override
            public void onError(String message) {
            }
        }, false);
    }

    public void requestApps() {
        requestLaunchPoints();
    }

    private void requestLaunchPoints() {
        request("ssap://com.webos.applicationManager/listLaunchPoints", new JsonObject(),
                new Callback() {
                    @Override
                    public void onSuccess(JsonObject payload) {
                        List<TvApp> apps = parseApps(payload, "launchPoints");
                        if (apps.isEmpty()) {
                            requestInstalledApps();
                        } else {
                            listener.onApps(apps);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        requestInstalledApps();
                    }
                }, false);
    }

    private void requestInstalledApps() {
        request("ssap://com.webos.applicationManager/listApps", new JsonObject(), new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                listener.onApps(parseApps(payload, "apps"));
            }

            @Override
            public void onError(String message) {
                listener.onApps(Collections.emptyList());
            }
        }, false);
    }

    private static List<TvApp> parseApps(JsonObject payload, String key) {
        List<TvApp> apps = new ArrayList<>();
        JsonArray array = array(payload, key);
        if (array == null) {
            return apps;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject app = element.getAsJsonObject();
            if (app.has("visible") && app.get("visible").isJsonPrimitive()
                    && !app.get("visible").getAsBoolean()) {
                continue;
            }
            String id = str(app, "id");
            String title = str(app, "title");
            if (title == null || title.trim().isEmpty()) {
                title = str(app, "name");
            }
            if (id == null || id.isEmpty() || title == null || title.trim().isEmpty()) {
                continue;
            }
            apps.add(new TvApp(id, title.trim()));
        }
        apps.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return apps;
    }

    public void requestInputs() {
        request("ssap://tv/getExternalInputList", new JsonObject(), new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
                Log.d(TAG, "external inputs: " + payload);
                List<TvInput> inputs = new ArrayList<>();
                JsonArray devices = array(payload, "devices");
                if (devices != null) {
                    for (JsonElement element : devices) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject device = element.getAsJsonObject();
                        String id = str(device, "id");
                        String appId = str(device, "appId");
                        String label = str(device, "label");
                        if (id == null || id.isEmpty()) {
                            continue;
                        }
                        if (appId == null || appId.isEmpty()) {
                            appId = "com.webos.app." + id.toLowerCase().replace("_", "");
                        }
                        boolean connected = false;
                        if (device.has("connected") && !device.get("connected").isJsonNull()) {
                            try {
                                connected = device.get("connected").getAsBoolean();
                            } catch (Exception ignored) {
                            }
                        }
                        inputs.add(new TvInput(id, appId,
                                label == null || label.isEmpty() ? id : label, connected));
                    }
                }
                inputs.sort((a, b) -> {
                    if (a.connected != b.connected) {
                        return a.connected ? -1 : 1;
                    }
                    return a.label.compareToIgnoreCase(b.label);
                });
                listener.onInputs(inputs);
            }

            @Override
            public void onError(String message) {
                listener.onInputs(Collections.emptyList());
            }
        }, false);
    }

    public void launchApp(String appId) {
        if (appId == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("id", appId);
        request("ssap://com.webos.applicationManager/launch", payload, noop(), false);
    }

    /** Opens the TV's own settings app. */
    public void launchSettings() {
        launchApp("com.palm.app.settings");
    }

    public void switchInput(String inputId) {
        if (inputId == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("inputId", inputId);
        request("ssap://tv/switchInput", payload, noop(), false);
    }

    public void powerOff() {
        request("ssap://system/turnOff", new JsonObject(), noop(), false);
    }

    /** Wakes the panel when the TV is still powered but the screen was turned off. */
    public void turnOnScreen() {
        request("ssap://com.webos.service.tvpower/power/turnOnScreen",
                new JsonObject(), noop(), false);
    }

    public boolean sendButton(String name) {
        try {
            return sendPointer(Protocol.pointerButton(name));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean sendClick() {
        return sendPointer(Protocol.pointerClick());
    }

    public boolean sendMove(int dx, int dy, boolean down) {
        return sendPointer(Protocol.pointerMove(dx, dy, down));
    }

    public boolean sendScroll(int dx, int dy) {
        return sendPointer(Protocol.pointerScroll(dx, dy));
    }

    private boolean sendPointer(String message) {
        WebSocket ws = pointerSocket;
        if (ws == null || !pointerReady) {
            return false;
        }
        return ws.send(message);
    }

    private void request(String uri, JsonObject payload, Callback callback, boolean persistent) {
        if (!registered || socket == null) {
            callback.onError("Not connected");
            return;
        }
        String id = "req_" + sequence.incrementAndGet();
        Pending p = new Pending(callback, persistent);
        pending.put(id, p);
        if (!persistent) {
            p.timeout = () -> {
                Pending current = pending.remove(id);
                if (current != null) {
                    current.callback.onError("The TV did not answer");
                }
            };
            main.postDelayed(p.timeout, REQUEST_TIMEOUT_MS);
        }
        socket.send(Protocol.request(id, uri, payload));
    }

    private static Callback noop() {
        return new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
            }

            @Override
            public void onError(String message) {
                Log.d(TAG, "command failed: " + message);
            }
        };
    }

    private void handleDrop(int gen, WebSocket ws, String reason) {
        if (gen != generation || ws != socket) {
            return;
        }
        dropAndReconnect(gen, ws, reason);
    }

    private void dropAndReconnect(int gen, WebSocket ws, String reason) {
        if (gen != generation) {
            return;
        }
        if (ws != null && ws != socket) {
            return;
        }
        boolean wasRegistered = registered;
        boolean wasOpened = opened;
        boolean firstPairing = clientKey == null;
        socket = null;
        registered = false;
        pointerReady = false;
        cancelHandshakeTimeout();
        closePointer();
        failPending("Connection lost");
        listener.onPointer(false);
        if (userStopped) {
            return;
        }
        Log.d(TAG, "connection dropped: " + reason);

        if (!wasRegistered && firstPairing && wasOpened) {
            // The TV declined the prompt (or the user closed it). Do not loop prompts.
            postState(State.ERROR, "Pairing was not approved on the TV. Tap Retry.");
            return;
        }
        if (!wasOpened && secure == null && candidate == 0) {
            candidate = 1;
            main.postDelayed(() -> {
                if (!userStopped && !registered) {
                    startAttempt();
                }
            }, 250);
            return;
        }
        scheduleReconnect(reason);
    }

    private void scheduleReconnect(String reason) {
        if (userStopped) {
            return;
        }
        cancelReconnect();
        int delay = Backoff.delay(attempt, jitter.nextInt(500));
        attempt++;
        int seconds = Math.max(1, delay / 1000);
        postState(State.RECONNECTING, "Reconnecting in " + seconds + "s…");
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            if (!userStopped && !registered) {
                // Each fresh cycle tries the secure port first again, then falls back to 3000.
                candidate = 0;
                startAttempt();
            }
        };
        main.postDelayed(reconnectRunnable, delay);
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            main.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void cancelTimers() {
        cancelHandshakeTimeout();
        cancelReconnect();
        if (pointerRetryRunnable != null) {
            main.removeCallbacks(pointerRetryRunnable);
            pointerRetryRunnable = null;
        }
    }

    private void closeSocket() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.close(1000, "bye");
            } catch (Exception ignored) {
                ws.cancel();
            }
        }
    }

    private void closePointer() {
        WebSocket ws = pointerSocket;
        pointerSocket = null;
        pointerReady = false;
        if (ws != null) {
            try {
                ws.close(1000, "bye");
            } catch (Exception ignored) {
                ws.cancel();
            }
        }
    }

    private void failPending(String reason) {
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pending> entry = it.next();
            Pending p = entry.getValue();
            it.remove();
            if (p.timeout != null) {
                main.removeCallbacks(p.timeout);
            }
            if (!p.persistent) {
                p.callback.onError(reason);
            }
        }
    }

    private void postState(State newState, String detail) {
        if (state == newState && stateDetail.equals(detail)) {
            return;
        }
        state = newState;
        stateDetail = detail;
        Log.d(TAG, "state " + newState + " (" + detail + ")");
        listener.onState(newState, detail);
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return false;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String nestedString(JsonObject object, String parent, String key) {
        if (object == null || !object.has(parent) || !object.get(parent).isJsonObject()) {
            return null;
        }
        return str(object.getAsJsonObject(parent), key);
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(key);
    }
}
