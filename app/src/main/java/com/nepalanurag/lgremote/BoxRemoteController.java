package com.nepalanurag.lgremote;

import android.content.Context;

/**
 * Routes remote actions to the paired Android TV box while its HDMI input is active.
 *
 * <p>Everything travels on one persistent session. The box only listens to the remote session that
 * is currently active, so a key sent on a second connection is ignored while the session that
 * watches the text field is open. The session is therefore used for the keys as well as for the
 * text, and a short-lived connection is only opened when the session is not up yet.
 */
final class BoxRemoteController implements RemoteController {
    private final RemoteService service;
    private final Context context;
    private final LgRemoteController fallback;
    private String monitorHost = "";

    BoxRemoteController(RemoteService service, LgRemoteController fallback) {
        this.service = service;
        this.context = service.getApplicationContext();
        this.fallback = fallback;
    }

    /**
     * Starts or stops the persistent session for the box. The session is what tells us when a
     * text field is focused, and it carries the keys and the IME counters used for text injection.
     */
    void setActive(boolean active) {
        if (!active) {
            stopMonitor();
            return;
        }
        if (!AndroidTvRemote.isPaired(context)) {
            stopMonitor();
            return;
        }
        String host = SecureStore.getBoxHost(context);
        if (host == null || host.isEmpty()) {
            stopMonitor();
            return;
        }
        if (!host.equals(monitorHost)) {
            stopMonitor();
            monitorHost = host;
        }
        AndroidTvRemote.startTextMonitor(context, host, service::setBoxTextFieldState);
    }

    void shutdown() {
        stopMonitor();
    }

    private void stopMonitor() {
        if (!monitorHost.isEmpty()) {
            AndroidTvRemote.stopTextMonitor();
            monitorHost = "";
        }
        service.setBoxTextFieldState(false, "");
    }

    /** Sends one key to the box, preferring the session that is already open. */
    private void sendKey(int code, String label, Callback callback) {
        if (code == 0) {
            callback.onResult(false, "That button is not sent to the Android TV box.");
            return;
        }
        if (AndroidTvRemote.isMonitorReady()) {
            AndroidTvRemote.sendMonitoredKey(code, label, callback::onResult);
            return;
        }
        AndroidTvRemote.sendKey(context, code, label, callback::onResult);
    }

    @Override
    public void sendNavigation(String name, Callback callback) {
        if (!AndroidTvRemote.isPaired(context)) {
            // The TV can still forward basic keys over HDMI-CEC before the box is paired.
            fallback.sendNavigation(name, callback);
            return;
        }
        sendKey(AndroidTvRemote.keyCodeFor(name), AndroidTvRemote.labelFor(name), callback);
    }

    @Override
    public void sendHome(Callback callback) {
        if (!AndroidTvRemote.isPaired(context)) {
            callback.onResult(false, "Pair the Android TV box to use Home on this screen.");
            return;
        }
        sendKey(AndroidTvRemote.KEYCODE_HOME, "Home", callback);
    }

    @Override
    public void sendSettings(Callback callback) {
        if (!AndroidTvRemote.isPaired(context)) {
            callback.onResult(false, "Pair the Android TV box to open its settings.");
            return;
        }
        sendKey(AndroidTvRemote.KEYCODE_SETTINGS, "Settings", callback);
    }

    @Override
    public void sendText(String text, Callback callback) {
        if (!AndroidTvRemote.isPaired(context)) {
            callback.onResult(false, "Pair the Android TV box to type on it.");
            return;
        }
        if (text == null || text.isEmpty()) {
            callback.onResult(false, "Type some text first.");
            return;
        }
        AndroidTvRemote.sendMonitoredText(text, callback::onResult);
    }

    @Override
    public void sendDelete(Callback callback) {
        if (!AndroidTvRemote.isPaired(context)) {
            callback.onResult(false, "Pair the Android TV box to type on it.");
            return;
        }
        sendKey(AndroidTvRemote.KEYCODE_DEL, "Delete", callback);
    }

    @Override
    public void sendEnter(Callback callback) {
        if (!AndroidTvRemote.isPaired(context)) {
            callback.onResult(false, "Pair the Android TV box to type on it.");
            return;
        }
        sendKey(AndroidTvRemote.KEYCODE_ENTER, "Enter", callback);
    }
}
