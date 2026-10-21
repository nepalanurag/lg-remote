package com.nepalanurag.lgremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Small client for the Google TV / Android TV remote protocol v2.
 *
 * <p>webOS does not expose CEC to remote clients, so Home and Settings are sent straight to a
 * paired Android TV box over the same TLS protocol the Google TV app uses. Pairing is a
 * one-time, six-character code shown on the box. The client identity is kept in the Android
 * Keystore-backed store.</p>
 */
public final class AndroidTvRemote {
    private static final String TAG = "LgRemote";
    private static final String SERVICE_TYPE = "_androidtvremote2._tcp.";
    private static final String CLIENT_NAME = "LG Remote";
    private static final int API_PORT = 6466;
    private static final int PAIR_PORT = 6467;
    private static final int MAX_MESSAGE = 1024 * 1024;

    private static final int FEATURE_PING = 1;
    private static final int FEATURE_KEY = 2;
    private static final int FEATURE_IME = 4;
    private static final int FEATURE_POWER = 32;
    private static final int FEATURE_VOLUME = 64;
    private static final int FEATURE_APP_LINK = 512;
    private static final int REQUESTED_FEATURES =
            FEATURE_PING | FEATURE_KEY | FEATURE_IME | FEATURE_POWER | FEATURE_VOLUME
                    | FEATURE_APP_LINK;
    private static final int KEYCODE_HOME = 3;
    private static final int KEYCODE_BACK = 4;
    private static final int KEYCODE_DPAD_UP = 19;
    private static final int KEYCODE_DPAD_DOWN = 20;
    private static final int KEYCODE_DPAD_LEFT = 21;
    private static final int KEYCODE_DPAD_RIGHT = 22;
    private static final int KEYCODE_DPAD_CENTER = 23;
    private static final int KEYCODE_ENTER = 66;
    private static final int KEYCODE_DEL = 67;
    private static final int KEYCODE_MEDIA_PLAY_PAUSE = 85;
    private static final int KEYCODE_MEDIA_REWIND = 89;
    private static final int KEYCODE_MEDIA_FAST_FORWARD = 90;
    private static final int KEYCODE_SETTINGS = 176;
    private static final int DIRECTION_SHORT = 3;

    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AndroidTvRemote() {
    }

    public interface HostCallback {
        void onHost(String host, String error);
    }

    public interface ResultCallback {
        void onResult(boolean ok, String error);
    }

    /** A live pairing connection. Keep it open while the code on the TV is being entered. */
    public static final class Pairing {
        private final Context context;
        private final String host;
        private final ResultCallback startedCallback;
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile Identity identity;
        private volatile SSLSocket socket;
        private volatile boolean closed;

        private Pairing(Context context, String host, ResultCallback startedCallback) {
            this.context = context;
            this.host = host;
            this.startedCallback = startedCallback;
        }

        public void finish(String code, ResultCallback callback) {
            IO.execute(() -> {
                SSLSocket current = socket;
                if (!started.get() || current == null || closed) {
                    postResult(callback, false, "The Android TV pairing session has expired. Try again.");
                    return;
                }
                try {
                    Identity identity = this.identity;
                    if (identity == null) {
                        identity = loadIdentity(context);
                    }
                    X509Certificate server = (X509Certificate) current.getSession()
                            .getPeerCertificates()[0];
                    byte[] digest = secretHash(identity.cert, server, code);
                    Log.d(TAG, "Android TV pairing: submitting code");
                    send(current, secretMessage(digest));
                    while (!closed) {
                        OuterInfo info = parseOuter(readMessage(current));
                        if (!info.ok) {
                            throw new IOException(info.error);
                        }
                        if (info.secretAck) {
                            Log.d(TAG, "Android TV pairing: accepted");
                            SecureStore.setBoxPaired(context, true);
                            SecureStore.setBoxHost(context, host);
                            close();
                            postResult(callback, true, null);
                            return;
                        }
                    }
                    throw new IOException("The Android TV box closed the pairing connection.");
                } catch (Exception e) {
                    close();
                    postResult(callback, false, friendly(e));
                }
            });
        }

        public void close() {
            closed = true;
            SSLSocket current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void begin() {
            IO.execute(() -> {
                try {
                    Identity identity = loadIdentity(context);
                    this.identity = identity;
                    SSLSocket current = openSocket(identity, host, PAIR_PORT);
                    socket = current;
                    Log.d(TAG, "Android TV pairing: fresh identity, connected to "
                            + host + ":" + PAIR_PORT);
                    send(current, pairingRequest(CLIENT_NAME));
                    while (!closed && !started.get()) {
                        OuterInfo info = parseOuter(readMessage(current));
                        if (!info.ok) {
                            throw new IOException(info.error);
                        }
                        if (info.pairingRequestAck) {
                            send(current, optionsMessage());
                        } else if (info.options) {
                            send(current, configurationMessage());
                        } else if (info.configurationAck) {
                            started.set(true);
                            Log.d(TAG, "Android TV pairing: waiting for the code on screen");
                            postResult(startedCallback, true, null);
                            return;
                        }
                    }
                    throw new IOException("Pairing was cancelled.");
                } catch (Exception e) {
                    close();
                    postResult(startedCallback, false, friendly(e));
                }
            });
        }
    }

    public static boolean isPaired(Context context) {
        return SecureStore.isBoxPaired(context);
    }

    public static void forget(Context context) {
        SecureStore.forgetBox(context);
    }

    public static Pairing startPairing(Context context, String host, ResultCallback callback) {
        // A new pairing must use a new certificate. Otherwise a box that still trusts the old
        // session skips the code prompt and the user never sees a fresh pairing code.
        SecureStore.resetBoxIdentity(context);
        SecureStore.setBoxHost(context, host);
        Log.d(TAG, "Android TV pairing: cleared old identity before pairing");
        Pairing pairing = new Pairing(context.getApplicationContext(), host, callback);
        pairing.begin();
        return pairing;
    }

    /** Finds the first Android TV remote service with mDNS. */
    public static void discover(Context context, HostCallback callback) {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            postHost(callback, null, "The phone cannot search for local devices.");
            return;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wifi != null) {
            lock = wifi.createMulticastLock("lgremote-androidtv");
            lock.setReferenceCounted(false);
            lock.acquire();
        }

        AtomicBoolean done = new AtomicBoolean();
        WifiManager.MulticastLock held = lock;
        NsdManager.DiscoveryListener[] discoveryRef = new NsdManager.DiscoveryListener[1];
        Runnable[] timeoutRef = new Runnable[1];
        Runnable cleanup = () -> {
            if (timeoutRef[0] != null) {
                MAIN.removeCallbacks(timeoutRef[0]);
            }
            release(held);
            NsdManager.DiscoveryListener active = discoveryRef[0];
            if (active != null) {
                try {
                    nsd.stopServiceDiscovery(active);
                } catch (Exception ignored) {
                }
            }
        };
        Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {
                cleanup.run();
                postHost(callback, null, "Could not find an Android TV box on this Wi-Fi.");
            }
        };
        timeoutRef[0] = timeout;
        MAIN.postDelayed(timeout, 6500);

        NsdManager.ResolveListener resolve = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                if (done.compareAndSet(false, true)) {
                    cleanup.run();
                    postHost(callback, null, "Found a box but could not resolve its address.");
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                cleanup.run();
                InetAddress address = serviceInfo.getHost();
                if (address == null) {
                    postHost(callback, null, "The Android TV box did not provide an address.");
                    return;
                }
                String name = serviceInfo.getServiceName();
                if (name != null && !name.trim().isEmpty()) {
                    SecureStore.setBoxName(context, name.trim());
                }
                SecureStore.setBoxHost(context, address.getHostAddress());
                postHost(callback, address.getHostAddress(), null);
            }
        };

        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                try {
                    nsd.resolveService(serviceInfo, resolve);
                } catch (Exception e) {
                    if (done.compareAndSet(false, true)) {
                        cleanup.run();
                        postHost(callback, null, "Could not resolve the Android TV box.");
                    }
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (done.compareAndSet(false, true)) {
                    cleanup.run();
                    postHost(callback, null, "Local discovery failed on this phone.");
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };

        discoveryRef[0] = discovery;
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            cleanup.run();
            postHost(callback, null, "Local discovery is not available.");
        }
    }

    /** Sends Home to the paired Android TV box, discovering it again if its address is stale. */
    public static void sendHome(Context context, ResultCallback callback) {
        sendKey(context, KEYCODE_HOME, "Home", callback);
    }

    /** Opens the Android TV settings screen on the paired box. */
    public static void sendSettings(Context context, ResultCallback callback) {
        sendKey(context, KEYCODE_SETTINGS, "Settings", callback);
    }

    /**
     * Sends the navigation and playback buttons straight to the box while its HDMI input is active.
     * Volume and mute stay with the TV and are intentionally not handled here.
     */
    public static void sendNavigation(Context context, String name, ResultCallback callback) {
        int code;
        String label;
        switch (name) {
            case "UP":
                code = KEYCODE_DPAD_UP;
                label = "Up";
                break;
            case "DOWN":
                code = KEYCODE_DPAD_DOWN;
                label = "Down";
                break;
            case "LEFT":
                code = KEYCODE_DPAD_LEFT;
                label = "Left";
                break;
            case "RIGHT":
                code = KEYCODE_DPAD_RIGHT;
                label = "Right";
                break;
            case "ENTER":
                code = KEYCODE_DPAD_CENTER;
                label = "OK";
                break;
            case "BACK":
                code = KEYCODE_BACK;
                label = "Back";
                break;
            case "PLAY":
                code = KEYCODE_MEDIA_PLAY_PAUSE;
                label = "Play/pause";
                break;
            case "REWIND":
                code = KEYCODE_MEDIA_REWIND;
                label = "Rewind";
                break;
            case "FASTFORWARD":
                code = KEYCODE_MEDIA_FAST_FORWARD;
                label = "Forward";
                break;
            default:
                postResult(callback, false, "That button is not sent to the Android TV box.");
                return;
        }
        sendKey(context, code, label, callback);
    }

    /** Sends typed text to the box's focused field using the Android TV IME protocol. */
    public static void sendText(Context context, String text, ResultCallback callback) {
        if (text == null || text.isEmpty()) {
            postResult(callback, false, "Type some text first.");
            return;
        }
        withBoxHost(context, (host) -> sendTextTo(context, host, text), callback);
    }

    /** Sends delete to the box's focused text field. */
    public static void sendDelete(Context context, ResultCallback callback) {
        sendKey(context, KEYCODE_DEL, "Delete", callback);
    }

    /** Sends enter to the box's focused text field. */
    public static void sendEnter(Context context, ResultCallback callback) {
        sendKey(context, KEYCODE_ENTER, "Enter", callback);
    }

    /** Notified on the main thread when a text field on the box gains or loses focus. */
    public interface TextFocusCallback {
        void onFieldFocus(boolean focused, String label);
    }

    private static volatile TextMonitor textMonitor;

    /**
     * Keeps one remote session open so the box can tell us when a text field is selected. The
     * session also answers the box's pings; it is separate from the short-lived command sockets.
     */
    public static synchronized void startTextMonitor(Context context, String host,
                                                     TextFocusCallback callback) {
        if (context == null || host == null || host.isEmpty() || !isPaired(context)) {
            return;
        }
        TextMonitor current = textMonitor;
        if (current != null) {
            current.updateCallback(callback);
            if (host.equals(current.host)) {
                return;
            }
            current.stop();
        }
        TextMonitor monitor = new TextMonitor(context.getApplicationContext(), host, callback);
        textMonitor = monitor;
        monitor.start();
    }

    public static synchronized void stopTextMonitor() {
        TextMonitor current = textMonitor;
        textMonitor = null;
        if (current != null) {
            current.stop();
        }
    }

    /** Sends text over the persistent IME session that also tracks the focused box field. */
    public static void sendMonitoredText(String text, ResultCallback callback) {
        TextMonitor current = textMonitor;
        if (current == null) {
            postResult(callback, false, "The box text connection is not ready.");
            return;
        }
        current.sendText(text, callback);
    }

    public static void sendMonitoredDelete(ResultCallback callback) {
        TextMonitor current = textMonitor;
        if (current == null) {
            postResult(callback, false, "The box text connection is not ready.");
            return;
        }
        current.sendKey(KEYCODE_DEL, "Delete", callback);
    }

    public static void sendMonitoredEnter(ResultCallback callback) {
        TextMonitor current = textMonitor;
        if (current == null) {
            postResult(callback, false, "The box text connection is not ready.");
            return;
        }
        current.sendKey(KEYCODE_ENTER, "Enter", callback);
    }

    private static final class TextMonitor implements Runnable {
        private final Context context;
        private final String host;
        private volatile TextFocusCallback callback;
        private volatile boolean stopped;
        private volatile SSLSocket socket;
        private final Object writeLock = new Object();
        private volatile boolean started;
        private volatile boolean imeSupported = true;
        private volatile int imeCounter;
        private volatile int fieldCounter;
        private volatile boolean countersKnown;
        private volatile boolean fieldFocused;
        private boolean lastFocused;
        private String lastLabel = "";

        TextMonitor(Context context, String host, TextFocusCallback callback) {
            this.context = context;
            this.host = host;
            this.callback = callback;
        }

        void updateCallback(TextFocusCallback callback) {
            this.callback = callback;
        }

        void start() {
            Thread thread = new Thread(this, "box-text-monitor");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stopped = true;
            closeQuietly(socket);
        }

        private void write(byte[] message) throws IOException {
            synchronized (writeLock) {
                SSLSocket current = socket;
                if (current == null) {
                    throw new IOException("The Android TV box is not connected.");
                }
                send(current, message);
            }
        }

        void sendText(String text, ResultCallback callback) {
            if (!fieldFocused) {
                postResult(callback, false, "Select a text field on the box first.");
                return;
            }
            if (!started || socket == null) {
                postResult(callback, false, "The box is still connecting. Try again.");
                return;
            }
            if (!imeSupported) {
                postResult(callback, false,
                        "The Android TV box does not support remote text entry.");
                return;
            }
            IO.execute(() -> {
                long deadline = System.currentTimeMillis() + 450;
                while (!countersKnown && started && socket != null
                        && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                int ime = imeCounter;
                int field = fieldCounter;
                try {
                    byte[] payload = remoteImeBatchEdit(text, ime, field);
                    StringBuilder hex = new StringBuilder();
                    for (byte value : payload) {
                        hex.append(String.format("%02x", value));
                    }
                    Log.d(TAG, "Box IME: payload=" + hex);
                    write(payload);
                    Log.d(TAG, "Box IME: text sent length=" + text.length()
                            + " counters=" + ime + "/" + field
                            + (countersKnown ? "" : " (counters not reported yet)"));
                    postResult(callback, true, null);
                } catch (Exception e) {
                    postResult(callback, false, friendly(e));
                }
            });
        }

        void sendKey(int keyCode, String label, ResultCallback callback) {
            if (!started || socket == null) {
                postResult(callback, false, "The box is still connecting. Try again.");
                return;
            }
            IO.execute(() -> {
                try {
                    write(remoteKey(keyCode));
                    Log.d(TAG, "Box IME: " + label + " sent");
                    postResult(callback, true, null);
                } catch (Exception e) {
                    postResult(callback, false, friendly(e));
                }
            });
        }

        @Override
        public void run() {
            long backoff = 1200;
            while (!stopped) {
                try {
                    monitor();
                    backoff = 1200;
                } catch (Exception e) {
                    if (!stopped) {
                        Log.d(TAG, "Box IME: monitor disconnected: " + e.getMessage());
                    }
                }
                if (stopped) {
                    break;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(8000, backoff * 2);
            }
            Log.d(TAG, "Box IME: monitor stopped");
        }

        private void monitor() throws Exception {
            Identity identity = loadIdentity(context);
            SSLSocket current = openSocket(identity, host, API_PORT);
            socket = current;
            started = false;
            imeSupported = true;
            imeCounter = 0;
            fieldCounter = 0;
            countersKnown = false;
            fieldFocused = false;
            current.setSoTimeout(2000);
            Log.d(TAG, "Box IME: monitor connected to " + host);
            try {
                int features = REQUESTED_FEATURES;
                while (!stopped) {
                    RemoteInfo info;
                    try {
                        info = parseRemote(readMessage(current));
                    } catch (SocketTimeoutException timeout) {
                        continue;
                    }
                    if (info.error) {
                        throw new IOException("The Android TV box rejected the remote session.");
                    }
                    if (info.configure) {
                        features = info.features & REQUESTED_FEATURES;
                        if ((features & FEATURE_KEY) == 0) {
                            features |= FEATURE_KEY;
                        }
                        imeSupported = (features & FEATURE_IME) != 0;
                        write(remoteConfigure(features));
                        Log.d(TAG, "Box IME: configured, features=" + features);
                    }
                    if (info.setActive) {
                        write(remoteSetActive(features));
                    }
                    if (info.ping) {
                        write(remotePingResponse(info.pingValue));
                    }
                    if (info.start) {
                        started = true;
                        Log.d(TAG, "Box IME: remote started");
                    }
                    if (info.imeBatchEdit) {
                        imeCounter = info.imeCounter;
                        fieldCounter = info.fieldCounter;
                        countersKnown = true;
                        Log.d(TAG, "Box IME: batch counters="
                                + imeCounter + "/" + fieldCounter);
                    }
                    if (info.imeShow) {
                        fieldFocused = true;
                        report(true, info.fieldLabel);
                    } else if (info.imeInject) {
                        String label = info.fieldLabel;
                        if (!countersKnown) {
                            imeCounter = info.imeCounter;
                            fieldCounter = info.fieldCounter;
                        }
                        Log.d(TAG, "Box IME: inject app=" + info.imeApp
                                + " hasField=" + info.hasFieldStatus
                                + " label=" + label
                                + " valueLen=" + info.fieldValue.length()
                                + " selection=" + info.fieldStart + "/" + info.fieldEnd
                                + " counters=" + imeCounter + "/" + fieldCounter);
                        if (!info.hasFieldStatus) {
                            fieldFocused = false;
                            report(false, "");
                        } else if (!info.fieldValue.isEmpty()
                                || (label != null && !label.isEmpty())
                                || info.fieldStart > 0 || info.fieldEnd > 0) {
                            fieldFocused = true;
                            report(true, label);
                        }
                    }
                }
            } finally {
                started = false;
                fieldFocused = false;
                if (socket == current) {
                    socket = null;
                }
                closeQuietly(current);
            }
        }

        private void report(boolean focused, String label) {
            String safe = label == null ? "" : label;
            if (focused == lastFocused && safe.equals(lastLabel)) {
                return;
            }
            lastFocused = focused;
            lastLabel = safe;
            Log.d(TAG, "Box IME: field focus=" + focused + " label=" + safe);
            TextFocusCallback target = callback;
            if (target != null && !stopped) {
                MAIN.post(() -> {
                    if (!stopped) {
                        target.onFieldFocus(focused, safe);
                    }
                });
            }
        }
    }

    private interface BoxAction {
        void run(String host) throws Exception;
    }

    private static void sendKey(Context context, int keyCode, String label,
                                ResultCallback callback) {
        withBoxHost(context,
                (host) -> sendKeyTo(context, host, keyCode, label), callback);
    }

    private static void withBoxHost(Context context, BoxAction action, ResultCallback callback) {
        if (!isPaired(context)) {
            postResult(callback, false, "The Android TV box is not paired yet.");
            return;
        }
        String saved = SecureStore.getBoxHost(context);
        if (saved != null && !saved.isEmpty()) {
            IO.execute(() -> {
                try {
                    action.run(saved);
                    postResult(callback, true, null);
                } catch (Exception e) {
                    String message = friendly(e);
                    Log.d(TAG, "Box at " + saved + " failed: " + message);
                    if (isPairingFailure(e)) {
                        postResult(callback, false, message);
                        return;
                    }
                    SecureStore.setBoxHost(context, "");
                    discover(context, (host, error) -> {
                        if (host == null) {
                            postResult(callback, false, error == null
                                    ? "Could not find the Android TV box." : error);
                            return;
                        }
                        IO.execute(() -> {
                            try {
                                action.run(host);
                                postResult(callback, true, null);
                            } catch (Exception retry) {
                                postResult(callback, false, friendly(retry));
                            }
                        });
                    });
                }
            });
            return;
        }
        discover(context, (host, error) -> {
            if (host == null) {
                postResult(callback, false, error == null
                        ? "Could not find the Android TV box." : error);
                return;
            }
            IO.execute(() -> {
                try {
                    action.run(host);
                    postResult(callback, true, null);
                } catch (Exception e) {
                    postResult(callback, false, friendly(e));
                }
            });
        });
    }

    private static void sendKeyTo(Context context, String host, int keyCode, String label)
            throws Exception {
        Identity identity = loadIdentity(context);
        SSLSocket socket = openSocket(identity, host, API_PORT);
        socket.setSoTimeout(800);
        Log.d(TAG, "Box " + label + ": connected to " + host + ":" + API_PORT);
        try {
            int features = REQUESTED_FEATURES;
            boolean configured = false;
            boolean keySent = false;
            long configuredAt = 0;
            long deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline && !keySent) {
                try {
                    RemoteInfo info = parseRemote(readMessage(socket));
                    if (info.error) {
                        throw new IOException("The Android TV box rejected the remote session.");
                    }
                    if (info.configure) {
                        features = info.features & REQUESTED_FEATURES;
                        if ((features & FEATURE_KEY) == 0) {
                            features |= FEATURE_KEY;
                        }
                        send(socket, remoteConfigure(features));
                        Log.d(TAG, "Box " + label + ": configured, features=" + features);
                        configured = true;
                        configuredAt = System.currentTimeMillis();
                    }
                    if (info.setActive) {
                        send(socket, remoteSetActive(features));
                    }
                    if (info.ping) {
                        send(socket, remotePingResponse(info.pingValue));
                    }
                    if (info.start) {
                        Log.d(TAG, "Box " + label + ": remote started, sending key");
                        send(socket, remoteKey(keyCode));
                        keySent = true;
                    }
                } catch (SocketTimeoutException timeout) {
                    // Some Android TV builds answer configure but do not send remote_start.
                    if (configured && !keySent
                            && System.currentTimeMillis() - configuredAt > 1200) {
                        Log.d(TAG, "Box " + label + ": no remote_start, sending key after configure");
                        send(socket, remoteKey(keyCode));
                        keySent = true;
                    }
                }
            }
            if (!keySent) {
                throw new IOException("The Android TV box did not become ready.");
            }
            Log.d(TAG, "Box " + label + ": key sent");
            Thread.sleep(250);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sendTextTo(Context context, String host, String text) throws Exception {
        Identity identity = loadIdentity(context);
        SSLSocket socket = openSocket(identity, host, API_PORT);
        socket.setSoTimeout(600);
        Log.d(TAG, "Box text: connected to " + host + ":" + API_PORT
                + ", length=" + text.length());
        try {
            int features = REQUESTED_FEATURES;
            boolean configured = false;
            boolean started = false;
            boolean sent = false;
            boolean acknowledged = false;
            boolean imeSupported = true;
            int imeCounter = 0;
            int fieldCounter = 0;
            int lastFieldLength = -1;
            String lastApp = "";
            String lastFieldLabel = "";
            long configuredAt = 0;
            long startedAt = 0;
            long sentAt = 0;
            long deadline = System.currentTimeMillis() + 6500;
            while (System.currentTimeMillis() < deadline
                    && (!sent || System.currentTimeMillis() - sentAt < 700)) {
                try {
                    RemoteInfo info = parseRemote(readMessage(socket));
                    if (info.error) {
                        throw new IOException("The Android TV box rejected the remote session.");
                    }
                    if (info.configure) {
                        features = info.features & REQUESTED_FEATURES;
                        if ((features & FEATURE_KEY) == 0) {
                            features |= FEATURE_KEY;
                        }
                        imeSupported = (features & FEATURE_IME) != 0;
                        send(socket, remoteConfigure(features));
                        Log.d(TAG, "Box text: configured, features=" + features);
                        configured = true;
                        configuredAt = System.currentTimeMillis();
                    }
                    if (info.setActive) {
                        send(socket, remoteSetActive(features));
                    }
                    if (info.ping) {
                        send(socket, remotePingResponse(info.pingValue));
                    }
                    if (info.imeInject) {
                        int previousLength = lastFieldLength;
                        imeCounter = info.imeCounter;
                        fieldCounter = info.fieldCounter;
                        lastFieldLength = info.fieldValue.length();
                        lastApp = info.imeApp;
                        lastFieldLabel = info.fieldLabel;
                        Log.d(TAG, "Box text: IME field app=" + lastApp
                                + " label=" + lastFieldLabel
                                + " valueLen=" + lastFieldLength
                                + " counters=" + imeCounter + "/" + fieldCounter);
                        if (sent && previousLength >= 0 && lastFieldLength != previousLength) {
                            acknowledged = true;
                            Log.d(TAG, "Box text: IME acknowledged, valueLen=" + lastFieldLength);
                            break;
                        }
                    }
                    if (info.imeBatchEdit) {
                        imeCounter = info.imeCounter;
                        fieldCounter = info.fieldCounter;
                        Log.d(TAG, "Box text: IME batch counters="
                                + imeCounter + "/" + fieldCounter);
                    }
                    if (info.start) {
                        started = true;
                        startedAt = System.currentTimeMillis();
                    }
                    long now = System.currentTimeMillis();
                    boolean waitedForIme = now - startedAt >= 420;
                    if (started && waitedForIme && !sent) {
                        sendImeText(socket, text, imeCounter, fieldCounter, imeSupported);
                        sent = true;
                        sentAt = now;
                    }
                } catch (SocketTimeoutException timeout) {
                    long now = System.currentTimeMillis();
                    if (started && now - startedAt >= 700 && !sent) {
                        sendImeText(socket, text, imeCounter, fieldCounter, imeSupported);
                        sent = true;
                        sentAt = now;
                    } else if (!started && configured && now - configuredAt > 1400 && !sent) {
                        // A few Android TV builds answer configure but never send remote_start.
                        Log.d(TAG, "Box text: no remote_start, sending after configure");
                        sendImeText(socket, text, imeCounter, fieldCounter, imeSupported);
                        sent = true;
                        sentAt = now;
                    }
                }
            }
            if (!sent) {
                throw new IOException("The Android TV box did not become ready for text.");
            }
            Log.d(TAG, "Box text: sent, app=" + lastApp + " label=" + lastFieldLabel
                    + (acknowledged ? ", acknowledged" : ", no IME echo"));
            Thread.sleep(160);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sendImeText(SSLSocket socket, String text, int imeCounter,
                                    int fieldCounter, boolean imeSupported) throws IOException {
        if (!imeSupported) {
            throw new IOException("The Android TV box does not support remote text entry.");
        }
        send(socket, remoteImeBatchEdit(text, imeCounter, fieldCounter));
    }

    // ------------------------------------------------------------------ TLS and identity

    private static final class Identity {
        final X509Certificate cert;
        final PrivateKey key;

        Identity(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }
    }

    private static Identity loadIdentity(Context context) throws Exception {
        byte[] certBytes = SecureStore.getBoxCert(context);
        byte[] keyBytes = SecureStore.getBoxKey(context);
        if (certBytes != null && keyBytes != null) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(certBytes));
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            return new Identity(cert, key);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=" + CLIENT_NAME);
        BigInteger serial = new BigInteger(64, new SecureRandom()).abs();
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, new Date(now - 24L * 60L * 60L * 1000L),
                new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L),
                name, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(pair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
        SecureStore.saveBoxIdentity(context, cert.getEncoded(), pair.getPrivate().getEncoded());
        return new Identity(cert, pair.getPrivate());
    }

    private static SSLSocket openSocket(Identity identity, String host, int port) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setKeyEntry("client", identity.key, new char[0], new Certificate[]{identity.cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, new char[0]);

        TrustManager[] trust = new TrustManager[]{new X509TrustManager() {
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
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), trust, new SecureRandom());
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress(host, port), 4500);
        socket.setSoTimeout(5000);
        socket.startHandshake();
        return socket;
    }

    private static byte[] secretHash(X509Certificate client, X509Certificate server, String code)
            throws Exception {
        if (code == null || !code.matches("[0-9A-Fa-f]{6}")) {
            throw new IOException("Enter the 6-character code shown on the Android TV screen.");
        }
        byte[] codeBytes = hexBytes(code);
        RSAPublicKey clientKey = (RSAPublicKey) client.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) server.getPublicKey();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(hexBytes(clientKey.getModulus().toString(16)));
        digest.update(hexBytes(clientKey.getPublicExponent().toString(16)));
        digest.update(hexBytes(serverKey.getModulus().toString(16)));
        digest.update(hexBytes(serverKey.getPublicExponent().toString(16)));
        digest.update(Arrays.copyOfRange(codeBytes, 1, 3));
        byte[] hash = digest.digest();
        if ((hash[0] & 0xFF) != (codeBytes[0] & 0xFF)) {
            throw new IOException("That code did not match. Check the code on the Android TV screen.");
        }
        return hash;
    }

    private static byte[] hexBytes(String hex) {
        if ((hex.length() & 1) == 1) {
            hex = "0" + hex;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ------------------------------------------------------------------ protocol messages

    private static byte[] pairingRequest(String clientName) {
        Writer request = new Writer();
        request.string(1, "atvremote");
        request.string(2, clientName);
        Writer outer = new Writer();
        outer.varint(1, 2);
        outer.varint(2, 200);
        outer.bytes(10, request.bytes());
        return outer.bytes();
    }

    private static byte[] optionsMessage() {
        Writer encoding = new Writer();
        encoding.varint(1, 3);
        encoding.varint(2, 6);
        Writer options = new Writer();
        options.bytes(1, encoding.bytes());
        options.varint(3, 1);
        Writer outer = new Writer();
        outer.varint(1, 2);
        outer.varint(2, 200);
        outer.bytes(20, options.bytes());
        return outer.bytes();
    }

    private static byte[] configurationMessage() {
        Writer encoding = new Writer();
        encoding.varint(1, 3);
        encoding.varint(2, 6);
        Writer configuration = new Writer();
        configuration.bytes(1, encoding.bytes());
        configuration.varint(2, 1);
        Writer outer = new Writer();
        outer.varint(1, 2);
        outer.varint(2, 200);
        outer.bytes(30, configuration.bytes());
        return outer.bytes();
    }

    private static byte[] secretMessage(byte[] secret) {
        Writer secretMessage = new Writer();
        secretMessage.bytes(1, secret);
        Writer outer = new Writer();
        outer.varint(1, 2);
        outer.varint(2, 200);
        outer.bytes(40, secretMessage.bytes());
        return outer.bytes();
    }

    private static byte[] remoteConfigure(int features) {
        Writer device = new Writer();
        device.varint(3, 1);
        device.string(4, "1");
        device.string(5, "lgremote");
        device.string(6, "1.0");
        Writer configure = new Writer();
        configure.varint(1, features);
        configure.bytes(2, device.bytes());
        Writer message = new Writer();
        message.bytes(1, configure.bytes());
        return message.bytes();
    }

    private static byte[] remoteSetActive(int features) {
        Writer setActive = new Writer();
        setActive.varint(1, features);
        Writer message = new Writer();
        message.bytes(2, setActive.bytes());
        return message.bytes();
    }

    private static byte[] remotePingResponse(int value) {
        Writer response = new Writer();
        response.varint(1, value);
        Writer message = new Writer();
        message.bytes(9, response.bytes());
        return message.bytes();
    }

    static byte[] remoteKey(int keyCode) {
        Writer key = new Writer();
        key.varint(1, keyCode);
        key.varint(2, DIRECTION_SHORT);
        Writer message = new Writer();
        message.bytes(10, key.bytes());
        return message.bytes();
    }

    static byte[] remoteImeBatchEdit(String text, int imeCounter, int fieldCounter) {
        int end = Math.max(0, text.codePointCount(0, text.length()) - 1);
        Writer textField = new Writer();
        textField.varint(1, end);
        textField.varint(2, end);
        textField.string(3, text);
        Writer edit = new Writer();
        edit.varint(1, 1);
        edit.bytes(2, textField.bytes());
        Writer batch = new Writer();
        batch.varint(1, imeCounter);
        batch.varint(2, fieldCounter);
        batch.bytes(3, edit.bytes());
        Writer message = new Writer();
        message.bytes(21, batch.bytes());
        return message.bytes();
    }

    // ------------------------------------------------------------------ framing and protobuf

    private static void send(SSLSocket socket, byte[] message) throws IOException {
        OutputStream out = socket.getOutputStream();
        writeVarint(out, message.length);
        out.write(message);
        out.flush();
    }

    private static byte[] readMessage(SSLSocket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int length = readVarint(in);
        if (length <= 0 || length > MAX_MESSAGE) {
            throw new IOException("Invalid message from the Android TV box.");
        }
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException("The Android TV box closed the connection.");
            }
            offset += read;
        }
        return data;
    }

    private static void writeVarint(OutputStream out, int value) throws IOException {
        long v = value & 0xFFFFFFFFL;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static int readVarint(InputStream in) throws IOException {
        int shift = 0;
        long result = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("The Android TV box closed the connection.");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0 || result > MAX_MESSAGE) {
                    throw new IOException("Invalid message from the Android TV box.");
                }
                return (int) result;
            }
            shift += 7;
            if (shift > 35) {
                throw new IOException("Invalid message length from the Android TV box.");
            }
        }
    }

    /** Minimal protobuf writer. Package-private so the payloads can be asserted in unit tests. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void varint(int field, long value) {
            writeRawVarint(((long) field << 3));
            writeRawVarint(value);
        }

        void bytes(int field, byte[] value) {
            writeRawVarint(((long) field << 3) | 2);
            writeRawVarint(value.length);
            out.write(value, 0, value.length);
        }

        void string(int field, String value) {
            bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private void writeRawVarint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }
    }

    /** Minimal protobuf reader. Package-private so the payloads can be asserted in unit tests. */
    static final class Reader {
        private final byte[] data;
        private int pos;
        private int field;
        private int wire;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean next() throws IOException {
            if (pos >= data.length) {
                return false;
            }
            long tag = varint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            if (field <= 0) {
                throw new IOException("Invalid protobuf field.");
            }
            return true;
        }

        int field() {
            return field;
        }

        long varint() throws IOException {
            int shift = 0;
            long result = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IOException("Truncated protobuf.");
                }
                int b = data[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IOException("Invalid protobuf varint.");
                }
            }
        }

        byte[] bytes() throws IOException {
            int length = (int) varint();
            if (length < 0 || pos + length > data.length) {
                throw new IOException("Truncated protobuf.");
            }
            byte[] value = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return value;
        }

        String string() throws IOException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        void skip() throws IOException {
            if (wire == 0) {
                varint();
            } else if (wire == 1) {
                pos += 8;
            } else if (wire == 2) {
                int length = (int) varint();
                pos += length;
            } else if (wire == 5) {
                pos += 4;
            } else {
                throw new IOException("Unsupported protobuf wire type.");
            }
            if (pos > data.length) {
                throw new IOException("Truncated protobuf.");
            }
        }
    }

    private static final class OuterInfo {
        boolean ok = true;
        String error;
        boolean pairingRequestAck;
        boolean options;
        boolean configurationAck;
        boolean secretAck;
    }

    private static OuterInfo parseOuter(byte[] data) throws IOException {
        OuterInfo info = new OuterInfo();
        Reader reader = new Reader(data);
        while (reader.next()) {
            int field = reader.field();
            if (field == 1) {
                // protocol_version is a varint, not a length-delimited field.
                reader.varint();
            } else if (field == 2) {
                long status = reader.varint();
                if (status != 200) {
                    info.ok = false;
                    info.error = status == 402
                            ? "That pairing code was not accepted."
                            : "The Android TV box refused the pairing request.";
                }
            } else if (field == 11) {
                reader.bytes();
                info.pairingRequestAck = true;
            } else if (field == 20) {
                reader.bytes();
                info.options = true;
            } else if (field == 31) {
                reader.bytes();
                info.configurationAck = true;
            } else if (field == 41) {
                reader.bytes();
                info.secretAck = true;
            } else {
                reader.skip();
            }
        }
        return info;
    }

    /** A decoded message from the box. Package-private so unit tests can assert on it. */
    static final class RemoteInfo {
        boolean configure;
        boolean setActive;
        boolean start;
        boolean ping;
        boolean error;
        boolean imeInject;
        boolean imeBatchEdit;
        boolean imeShow;
        boolean hasFieldStatus;
        int features;
        int pingValue;
        int imeCounter;
        int fieldCounter;
        String imeApp = "";
        String imeLabel = "";
        String fieldValue = "";
        String fieldLabel = "";
        int fieldStart = -1;
        int fieldEnd = -1;
    }

    static RemoteInfo parseRemote(byte[] data) throws IOException {
        RemoteInfo info = new RemoteInfo();
        Reader reader = new Reader(data);
        while (reader.next()) {
            int field = reader.field();
            if (field == 1) {
                info.configure = true;
                Reader configure = new Reader(reader.bytes());
                while (configure.next()) {
                    int sub = configure.field();
                    if (sub == 1) {
                        info.features = (int) configure.varint();
                    } else {
                        configure.skip();
                    }
                }
            } else if (field == 2) {
                info.setActive = true;
                reader.skip();
            } else if (field == 3) {
                info.error = true;
                reader.skip();
            } else if (field == 8) {
                info.ping = true;
                Reader ping = new Reader(reader.bytes());
                while (ping.next()) {
                    int sub = ping.field();
                    if (sub == 1) {
                        info.pingValue = (int) ping.varint();
                    } else {
                        ping.skip();
                    }
                }
            } else if (field == 20) {
                info.imeInject = true;
                Reader ime = new Reader(reader.bytes());
                while (ime.next()) {
                    int sub = ime.field();
                    if (sub == 1) {
                        Reader app = new Reader(ime.bytes());
                        while (app.next()) {
                            int appField = app.field();
                            if (appField == 1) {
                                info.imeCounter = (int) app.varint();
                            } else if (appField == 10) {
                                info.imeLabel = app.string();
                            } else if (appField == 12) {
                                info.imeApp = app.string();
                            } else {
                                app.skip();
                            }
                        }
                    } else if (sub == 2) {
                        parseTextFieldStatus(new Reader(ime.bytes()), info);
                    } else {
                        ime.skip();
                    }
                }
            } else if (field == 21) {
                info.imeBatchEdit = true;
                Reader batch = new Reader(reader.bytes());
                while (batch.next()) {
                    if (batch.field() == 1) {
                        info.imeCounter = (int) batch.varint();
                    } else if (batch.field() == 2) {
                        info.fieldCounter = (int) batch.varint();
                    } else {
                        batch.skip();
                    }
                }
            } else if (field == 22) {
                info.imeShow = true;
                Reader show = new Reader(reader.bytes());
                while (show.next()) {
                    if (show.field() == 2) {
                        parseTextFieldStatus(new Reader(show.bytes()), info);
                    } else {
                        show.skip();
                    }
                }
            } else if (field == 40) {
                info.start = true;
                reader.skip();
            } else {
                reader.skip();
            }
        }
        return info;
    }

    private static void parseTextFieldStatus(Reader status, RemoteInfo info) throws IOException {
        info.hasFieldStatus = true;
        while (status.next()) {
            int statusField = status.field();
            if (statusField == 1) {
                info.fieldCounter = (int) status.varint();
            } else if (statusField == 2) {
                info.fieldValue = status.string();
            } else if (statusField == 3) {
                info.fieldStart = (int) status.varint();
            } else if (statusField == 4) {
                info.fieldEnd = (int) status.varint();
            } else if (statusField == 6) {
                info.fieldLabel = status.string();
            } else {
                status.skip();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface MainCallback {
        void run();
    }

    private static void postResult(ResultCallback callback, boolean ok, String error) {
        MAIN.post(() -> callback.onResult(ok, error));
    }

    private static void postHost(HostCallback callback, String host, String error) {
        MAIN.post(() -> callback.onHost(host, error));
    }

    private static void closeQuietly(SSLSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void release(WifiManager.MulticastLock lock) {
        if (lock != null) {
            try {
                lock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isPairingFailure(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.US);
                if (lower.contains("certificate") || lower.contains("trust")
                        || lower.contains("pairing") || lower.contains("not paired")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String friendly(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return "Could not reach the Android TV box. Make sure it is on the same Wi-Fi.";
        }
        String lower = message.toLowerCase(java.util.Locale.US);
        if (lower.contains("certificate") || lower.contains("trust")) {
            return "The Android TV box no longer trusts this pairing. Pair it again.";
        }
        if (message.contains("Connection refused") || message.contains("failed to connect")) {
            return "Could not reach the Android TV box. Make sure it is awake.";
        }
        return message;
    }
}
