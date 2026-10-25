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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * LG webOS SSAP client. Pairing is register-with-manifest. New pairings use webOS's PIN mode so
 * the phone can finish setup without a TV remote; the on-screen prompt remains the fallback for
 * older firmware. Buttons are sent over the separate pointer socket.
 */
public final class LgTvClient {
    private static final String TAG = "LgRemote";
    private static final long REQUEST_TIMEOUT_MS = 15_000;
    private static final long PIN_PAIRING_TIMEOUT_MS = 5 * 60_000L;
    private static final String PAIRING_PIN = "PIN";

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

        /**
         * PIN pairing is active when required is true. submitting is true while the code is
         * being checked; error is a short, user-facing message when the last code failed.
         */
        void onPairingPin(boolean required, boolean submitting, String error);
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
    private boolean pinPairing;
    private boolean pinSubmitting;
    private boolean pinRetry;
    private String pinError;
    private boolean keyRejectedRetry;

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

    public boolean isPinPairingRequired() {
        return pinPairing;
    }

    public boolean isPinSubmitting() {
        return pinSubmitting;
    }

    public String getPinError() {
        return pinError;
    }

    public String getIp() {
        return ip;
    }

    /** Sends the code displayed on the TV; the TV answers with registered or an error. */
    public boolean submitPairingPin(String pin) {
        if (!pinPairing || registered || socket == null) {
            setPinState(pinPairing, false, "Pairing is not ready. Tap Retry.");
            return false;
        }
        String value = pin == null ? "" : pin.trim();
        if (!Protocol.isValidPairingPin(value)) {
            setPinState(true, false, "Enter the 4–8 digit PIN shown on the TV.");
            return false;
        }
        final int gen = generation;
        pinSubmitting = true;
        setPinState(true, true, null);
        postState(State.PAIRING, "Checking PIN…");
        // Replace the five-minute entry window with a short final-registration window,
        // so a PIN submitted at the last second cannot race the entry timeout.
        scheduleHandshakeTimeout(gen, 30_000);
        JsonObject payload = new JsonObject();
        payload.addProperty("pin", value);
        // setPin must be sent before registration is complete, so it cannot use the
        // normal request() guard that requires a registered session.
        requestDuringPairing(Protocol.SET_PAIRING_PIN, payload, new Callback() {
            @Override
            public void onSuccess(JsonObject response) {
                if (gen != generation || registered || socket == null) {
                    return;
                }
                pinSubmitting = false;
                setPinState(true, false, null);
                postState(State.PAIRING, "PIN accepted. Finishing pairing…");
            }

            @Override
            public void onError(String message) {
                if (gen != generation || registered || socket == null) {
                    return;
                }
                pinSubmitting = false;
                String lower = message == null ? "" : message.toLowerCase(Locale.US);
                if (lower.contains("invalid pin") || lower.contains("rejected pairing")
                        || lower.contains("application error")
                        || lower.contains("access denied")) {
                    // webOS ends the pairing session after a bad PIN, so request a
                    // fresh code instead of leaving the user with a dead dialog.
                    restartPinPairing(gen,
                            "That PIN didn't match. Requesting a new PIN…");
                } else {
                    setPinState(true, false, friendlyPinError(message));
                    postState(State.PAIRING, "The PIN wasn't accepted. Try again.");
                }
            }
        }, false);
        return true;
    }

    public void connect(String ip, String clientKey) {
        this.ip = ip;
        this.clientKey = clientKey;
        this.userStopped = false;
        this.attempt = 0;
        this.candidate = 0;
        this.secure = null;
        this.keyRejectedRetry = false;
        this.pinRetry = false;
        this.pinSubmitting = false;
        setPinState(false, false, null);
        cancelTimers();
        clearPending();
        closeSocket();
        closePointer();
        startAttempt();
    }

    public void reconnectNow() {
        if (ip == null) {
            return;
        }
        userStopped = false;
        keyRejectedRetry = false;
        pinRetry = false;
        pinSubmitting = false;
        setPinState(false, false, null);
        cancelTimers();
        clearPending();
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
        pinRetry = false;
        pinSubmitting = false;
        setPinState(false, false, null);
        postState(State.IDLE, "Disconnected");
    }

    /**
     * Stops waiting for a PIN. The user can reconnect from the banner or setup screen later.
     * This does not set userStopped, so an explicit Retry still works.
     */
    public void cancelPairing() {
        if (registered) {
            return;
        }
        generation++;
        cancelTimers();
        failPending("Pairing cancelled");
        closePointer();
        closeSocket();
        registered = false;
        pointerReady = false;
        listener.onPointer(false);
        setPinState(false, false, null);
        postState(State.ERROR, "Pairing cancelled.");
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
        if (pinPairing) {
            postState(State.PAIRING, pinRetry
                    ? "Requesting a new pairing PIN…" : "Reconnecting for pairing…");
        } else if (attempt == 0 && candidate == 0) {
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
        // Time to get the initial pairing answer. Once the TV puts a PIN on screen,
        // beginPinPairing extends this to the full five-minute entry window.
        scheduleHandshakeTimeout(gen, 20_000);
    }

    private void sendRegister(int gen) {
        String id = "register_" + sequence.incrementAndGet();
        registerId = id;
        boolean freshPairing = clientKey == null || clientKey.isEmpty();
        Pending p = new Pending(new Callback() {
            @Override
            public void onSuccess(JsonObject payload) {
            }

            @Override
            public void onError(String message) {
                if (!registered) {
                    Log.d(TAG, "register failed: " + message);
                    setPinState(false, false, null);
                    postState(State.ERROR, friendlyPairingError(message));
                    cancelTimers();
                    closeSocket();
                }
            }
        }, false);
        pending.put(id, p);
        if (socket != null) {
            // New pairings use PIN so the TV only has to display a code. Saved keys still
            // register normally through the manifest's PROMPT mode.
            socket.send(Protocol.registerPayload(pairingJson, clientKey,
                    freshPairing ? PAIRING_PIN : null, id));
            if (pinPairing) {
                // Keep the PIN sheet open while a new code is being requested.
                postState(State.PAIRING, "Requesting a new pairing PIN…");
            } else {
                postState(State.CONNECTING,
                        freshPairing ? "Requesting a pairing PIN…" : "Signing in…");
            }
        }
    }

    private void scheduleHandshakeTimeout(int gen, long ms) {
        cancelHandshakeTimeout();
        handshakeTimeout = () -> {
            if (gen == generation && !registered) {
                Log.d(TAG, "handshake timeout");
                if ((clientKey == null || clientKey.isEmpty()) && opened) {
                    boolean wasPin = pinPairing;
                    setPinState(false, false, null);
                    postState(State.ERROR, wasPin
                            ? "The PIN request timed out. Tap Retry for a new PIN."
                            : "The TV didn't answer with a PIN. Tap Retry.");
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
            // Registration is already sent from onOpen. Re-registering here would replace
            // registerId and could lose the PIN response during a fresh pairing.
            return;
        }
        if ("error".equals(type)) {
            String error = str(payload, "errorText");
            if (error == null || error.isEmpty()) {
                error = str(message, "error");
            }
            // A rejected PIN ends the original register request and the PIN request. The
            // register error carries no useful detail, so let the PIN request report the
            // real "Invalid pin code" message and do not tear down the session here.
            boolean pinRegisterError = !registered && pinPairing && id != null
                    && id.equals(registerId) && (pinSubmitting || pinError != null || pinRetry);
            Pending p = id == null ? null : pending.remove(id);
            if (pinRegisterError) {
                Log.d(TAG, "register ended during PIN pairing: " + error);
            } else if (p != null) {
                p.callback.onError(error == null ? "The TV rejected the request" : error);
            } else if (!registered) {
                setPinState(false, false, null);
                postState(State.ERROR, friendlyPairingError(error));
            }
            return;
        }
        if ("response".equals(type) || "subscribe".equals(type)) {
            // A fresh register with pairingType PIN is answered with a response that only
            // advertises the mode. The PIN prompt is then shown on the TV; registration is
            // completed later by ssap://pairing/setPin + a "registered" message.
            if (!registered && id != null && id.equals(registerId)
                    && payload.has("pairingType")) {
                String mode = str(payload, "pairingType");
                boolean accepted = !payload.has("returnValue")
                        || !payload.get("returnValue").isJsonPrimitive()
                        || payload.get("returnValue").getAsBoolean();
                Pending register = pending.remove(id);
                if (register != null && register.timeout != null) {
                    main.removeCallbacks(register.timeout);
                }
                if (!accepted) {
                    String error = str(payload, "errorText");
                    if (error == null) {
                        error = str(payload, "errorCode");
                    }
                    setPinState(false, false, null);
                    postState(State.ERROR, friendlyPairingError(error));
                    closeSocket();
                    return;
                }
                if (clientKey != null && !clientKey.isEmpty() && !keyRejectedRetry) {
                    retryWithoutSavedKey(gen, mode);
                } else if (PAIRING_PIN.equalsIgnoreCase(mode)) {
                    beginPinPairing(gen);
                } else {
                    beginPromptPairing(gen);
                }
                return;
            }
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

    private void beginPinPairing(int gen) {
        if (gen != generation || registered) {
            return;
        }
        boolean retry = pinRetry;
        pinRetry = false;
        setPinState(true, false, null);
        postState(State.PAIRING, retry
                ? "Enter the new PIN shown on your TV"
                : "Enter the PIN shown on your TV");
        // PIN entry can take longer than an on-screen prompt; keep the socket alive.
        scheduleHandshakeTimeout(gen, PIN_PAIRING_TIMEOUT_MS);
    }

    private void beginPromptPairing(int gen) {
        if (gen != generation || registered) {
            return;
        }
        pinRetry = false;
        setPinState(false, false, null);
        postState(State.PAIRING, "Approve the request on your TV");
        scheduleHandshakeTimeout(gen, 90_000);
    }

    /**
     * A TV may retain a client key across a factory reset policy change while rejecting it
     * later. Drop the stale in-memory key once and reconnect in PIN mode, without deleting the
     * stored key until the TV gives us a new one.
     */
    private void retryWithoutSavedKey(int gen, String mode) {
        if (gen != generation || registered) {
            return;
        }
        if (keyRejectedRetry) {
            if (PAIRING_PIN.equalsIgnoreCase(mode)) {
                beginPinPairing(gen);
            } else {
                beginPromptPairing(gen);
            }
            return;
        }
        keyRejectedRetry = true;
        clientKey = null;
        pinRetry = false;
        setPinState(false, false, null);
        // CONNECTING avoids showing the old "approve on TV" fallback while the fresh
        // PIN request is in flight; the PIN sheet appears when the TV answers.
        postState(State.CONNECTING, "Saved pairing expired. Requesting a new PIN…");
        Log.d(TAG, "saved client key was not accepted; retrying with PIN pairing");
        restartSocket(1_200);
    }

    /** webOS invalidates the pairing session after a wrong PIN; reconnect for a fresh code. */
    private void restartPinPairing(int gen, String error) {
        if (gen != generation || registered) {
            return;
        }
        pinRetry = true;
        pinSubmitting = false;
        setPinState(true, false, error);
        postState(State.PAIRING, "Requesting a new PIN…");
        restartSocket(1_200);
    }

    private void restartSocket(long delayMs) {
        final int restartGen = ++generation;
        cancelTimers();
        clearPending();
        closePointer();
        closeSocket();
        registered = false;
        pointerReady = false;
        listener.onPointer(false);
        main.postDelayed(() -> {
            // Cancel, retry, or a fresh connect bumps the generation, which makes this
            // delayed restart stale. Otherwise a quick Cancel could reopen the PIN sheet.
            if (restartGen == generation && !userStopped && !registered) {
                attempt = 0;
                candidate = 0;
                secure = null;
                startAttempt();
            }
        }, delayMs);
    }

    private void onRegistered(JsonObject payload) {
        registered = true;
        pointerReady = false;
        attempt = 0;
        pinRetry = false;
        keyRejectedRetry = false;
        setPinState(false, false, null);
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
                            appId = "com.webos.app."
                                    + id.toLowerCase(Locale.ROOT).replace("_", "");
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
        request(uri, payload, callback, persistent, false);
    }

    /**
     * webOS pairing commands (notably setPin) are sent while the socket is open but before
     * the registered message arrives, so they bypass the normal registered-session guard.
     */
    private void requestDuringPairing(String uri, JsonObject payload, Callback callback,
                                      boolean persistent) {
        request(uri, payload, callback, persistent, true);
    }

    /**
     * Pairing commands run on an open socket before the registered message, so they pass
     * allowUnregistered. Everything else needs a fully registered TV session.
     */
    static boolean canSendRequest(boolean registered, boolean socketPresent,
                                  boolean allowUnregistered) {
        return socketPresent && (registered || allowUnregistered);
    }

    private void request(String uri, JsonObject payload, Callback callback, boolean persistent,
                         boolean allowUnregistered) {
        if (!canSendRequest(registered, socket != null, allowUnregistered)) {
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
        boolean sent;
        try {
            sent = socket.send(Protocol.request(id, uri, payload));
        } catch (Exception e) {
            sent = false;
        }
        if (!sent) {
            // Do not wait for the timeout when the socket has already gone away.
            Pending current = pending.remove(id);
            if (current != null) {
                if (current.timeout != null) {
                    main.removeCallbacks(current.timeout);
                }
                current.callback.onError("Not connected");
            }
        }
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
        boolean wasPin = pinPairing;
        boolean firstPairing = clientKey == null || clientKey.isEmpty();
        socket = null;
        registered = false;
        pointerReady = false;
        pinSubmitting = false;
        cancelTimers();
        closePointer();
        failPending("Connection lost");
        setPinState(false, false, null);
        listener.onPointer(false);
        if (userStopped) {
            return;
        }
        Log.d(TAG, "connection dropped: " + reason);

        if (!wasRegistered && firstPairing && wasOpened) {
            // The TV declined the request (or the PIN session ended). Do not loop prompts.
            postState(State.ERROR, wasPin
                    ? "Pairing was interrupted. Tap Retry for a new PIN."
                    : "Pairing was not approved on the TV. Tap Retry.");
            return;
        }
        if (!wasOpened && secure == null && candidate == 0) {
            candidate = 1;
            main.postDelayed(() -> {
                if (gen == generation && !userStopped && !registered) {
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
        registerId = null;
    }

    private void clearPending() {
        for (Pending p : pending.values()) {
            if (p.timeout != null) {
                main.removeCallbacks(p.timeout);
            }
        }
        pending.clear();
        registerId = null;
    }

    private void setPinState(boolean required, boolean submitting, String error) {
        if (!required) {
            submitting = false;
            error = null;
        }
        if (pinPairing == required && pinSubmitting == submitting
                && Objects.equals(pinError, error)) {
            return;
        }
        pinPairing = required;
        pinSubmitting = submitting;
        pinError = error;
        listener.onPairingPin(required, submitting, error);
    }

    private static String friendlyPinError(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        if (lower.contains("too many pairing")) {
            return "The TV is busy with another pairing request. Wait a moment and retry.";
        }
        if (lower.contains("invalid pin") || lower.contains("rejected")) {
            return "That PIN didn't match. Check the code on the TV and try again.";
        }
        if (lower.contains("did not answer") || lower.contains("timeout")) {
            return "The TV didn't answer. Try again.";
        }
        return "The TV didn't accept that PIN. Try again.";
    }

    private static String friendlyPairingError(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        if (lower.contains("too many pairing")) {
            return "The TV is busy with another pairing request. Wait a moment and tap Retry.";
        }
        if (lower.contains("rejected") || lower.contains("denied")) {
            return "The TV declined pairing. Tap Retry for a new PIN.";
        }
        return "The TV rejected pairing. Tap Retry.";
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
