package com.nepalanurag.lgremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Owns the long-lived SSAP connection, so it survives rotation and the app going to the background. */
public final class RemoteService extends Service implements LgTvClient.Listener {
    private static final String TAG = "LgRemote";
    private static final String CHANNEL_ID = "remote";
    private static final int NOTIFICATION_ID = 4101;

    public static final String ACTION_CONNECT = "com.nepalanurag.lgremote.CONNECT";
    public static final String ACTION_DISCONNECT = "com.nepalanurag.lgremote.DISCONNECT";
    public static final String ACTION_RECONNECT = "com.nepalanurag.lgremote.RECONNECT";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_NAME = "name";

    public interface Listener {
        void onConnection(LgTvClient.State state, String detail, String name, String model);

        /** PIN pairing state for the first-connect sheet. */
        void onPairingPin(boolean required, boolean submitting, String error);

        void onPointer(boolean ready);

        void onVolume(int volume, boolean muted);

        void onApps(List<LgTvClient.TvApp> apps);

        void onInputs(List<LgTvClient.TvInput> inputs);

        void onForegroundApp(String appId);

        /** Null means the power state is not known right now (offline, connecting or pairing). */
        void onTvPower(String state);

        void onTextField(boolean focused, String contentType, boolean hidden);

        /** Box text-field focus, detected over the Android TV remote IME session. */
        void onBoxTextField(boolean focused, String label);

        default void onBoxMouseStatus() {
        }
    }

    public final class LocalBinder extends Binder {
        public RemoteService service() {
            return RemoteService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private LgTvClient client;
    private String ip;
    private String tvName = "";
    private String tvModel = "";
    private LgTvClient.State state = LgTvClient.State.IDLE;
    private String detail = "";
    private boolean pinPairing;
    private boolean pinSubmitting;
    private String pinError;
    private boolean pointerReady;
    private int volume = -1;
    private boolean muted;
    private List<LgTvClient.TvApp> apps = Collections.emptyList();
    private List<LgTvClient.TvInput> inputs = Collections.emptyList();
    private String foregroundApp = "";
    private String tvPowerState = "";
    private boolean tvPowerKnown;
    private boolean textFieldFocused;
    private String textContentType = "text";
    private boolean textHidden;
    private boolean boxTextFocused;
    private String boxTextFieldLabel = "";
    private boolean foreground;
    private LgRemoteController lgController;
    private BoxRemoteController boxController;
    private BoxBluetoothMouse boxMouse;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        String pairing = readPairingManifest();
        client = new LgTvClient(pairing, this);
        ip = SecureStore.getSavedIp(this);
        tvName = SecureStore.getSavedName(this);
        lgController = new LgRemoteController(this);
        boxController = new BoxRemoteController(this, lgController);
        boxMouse = new BoxBluetoothMouse(this);
        boxMouse.setListener(this::notifyBoxMouseStatus);
        boxMouse.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            disconnectAndStop();
            return START_NOT_STICKY;
        }
        if (ACTION_RECONNECT.equals(action)) {
            if (client.getIp() != null) {
                client.reconnectNow();
            } else if (ip != null) {
                connectInternal(ip, tvName);
            } else {
                stopSelf();
            }
            return START_STICKY;
        }

        String wantedIp = intent == null ? null : intent.getStringExtra(EXTRA_IP);
        String wantedName = intent == null ? null : intent.getStringExtra(EXTRA_NAME);
        String previousIp = ip;
        if (wantedIp == null || wantedIp.isEmpty()) {
            wantedIp = ip != null ? ip : SecureStore.getSavedIp(this);
        }
        if (wantedName == null) {
            wantedName = "";
        }
        if (wantedName.isEmpty() && wantedIp != null && wantedIp.equals(previousIp)) {
            wantedName = tvName != null ? tvName : SecureStore.getSavedName(this);
        }
        if (wantedIp == null || wantedIp.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        ip = wantedIp;
        tvName = wantedName;
        ensureForeground();
        if (client.isConnected() && wantedIp.equals(client.getIp())) {
            updateNotification();
            return START_STICKY;
        }
        connectInternal(wantedIp, tvName);
        return START_STICKY;
    }

    private void connectInternal(String targetIp, String name) {
        ip = targetIp;
        tvName = name == null ? "" : name;
        tvModel = "";
        volume = -1;
        muted = false;
        apps = Collections.emptyList();
        inputs = Collections.emptyList();
        foregroundApp = "";
        tvPowerState = "";
        tvPowerKnown = false;
        textFieldFocused = false;
        textContentType = "text";
        textHidden = false;
        setPinPairing(false, false, null);
        SecureStore.setSavedTv(this, ip, tvName);
        ensureForeground();
        updateNotification();
        String key = SecureStore.getClientKey(this, ip);
        Log.d(TAG, "service connect to " + ip + (key == null ? " (new pairing)" : " (saved key)"));
        client.connect(ip, key);
    }

    private void ensureForeground() {
        if (foreground) {
            return;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    public void disconnectAndStop() {
        if (client != null) {
            client.disconnect();
        }
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public void forgetPairing() {
        String target = client != null && client.getIp() != null ? client.getIp() : ip;
        if (target != null) {
            SecureStore.forgetClientKey(this, target);
        }
        disconnectAndStop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (boxMouse != null) {
            boxMouse.stop();
        }
        if (boxController != null) {
            boxController.shutdown();
        } else {
            AndroidTvRemote.stopTextMonitor();
        }
        if (client != null) {
            client.disconnect();
        }
        foreground = false;
        super.onDestroy();
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        listener.onConnection(state, detail, displayName(), tvModel);
        listener.onPairingPin(pinPairing, pinSubmitting, pinError);
        listener.onPointer(pointerReady);
        listener.onVolume(volume, muted);
        listener.onApps(apps);
        listener.onInputs(inputs);
        listener.onForegroundApp(foregroundApp);
        listener.onTvPower(tvPowerKnown ? tvPowerState : null);
        listener.onTextField(textFieldFocused, textContentType, textHidden);
        listener.onBoxTextField(boxTextFocused, boxTextFieldLabel);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public String getIp() {
        return ip;
    }

    public boolean isPairingPinRequired() {
        return pinPairing;
    }

    public boolean isPinSubmitting() {
        return pinSubmitting;
    }

    public String getPinError() {
        return pinError;
    }

    public boolean submitPairingPin(String pin) {
        return client != null && client.submitPairingPin(pin);
    }

    public void cancelPairing() {
        if (client != null) {
            client.cancelPairing();
        }
    }

    public String displayName() {
        if (tvName != null && !tvName.isEmpty()) {
            return tvName;
        }
        return getString(R.string.device_unknown);
    }

    public String getTvModel() {
        return tvModel;
    }

    public LgTvClient.State getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isPointerReady() {
        return pointerReady;
    }

    public boolean isConnected() {
        return state == LgTvClient.State.READY;
    }

    public int getVolume() {
        return volume;
    }

    public boolean isMuted() {
        return muted;
    }

    public List<LgTvClient.TvApp> getApps() {
        return apps;
    }

    public List<LgTvClient.TvInput> getInputs() {
        return inputs;
    }

    public String getForegroundApp() {
        return foregroundApp;
    }

    public boolean isTvPowerKnown() {
        return tvPowerKnown;
    }

    /** Human-readable state for the header, e.g. On, Screen off, Standby or Unknown. */
    public String getTvPowerLabel() {
        if (!tvPowerKnown || tvPowerState == null || tvPowerState.isEmpty()) {
            return state == LgTvClient.State.READY ? "On" : "Unknown";
        }
        String normalized = tvPowerState.trim().toLowerCase(java.util.Locale.US);
        if (normalized.equals("active")) {
            return "On";
        }
        if (normalized.contains("screen") && normalized.contains("off")) {
            return "Screen off";
        }
        if (normalized.contains("standby")) {
            return "Standby";
        }
        if (normalized.contains("suspend")) {
            return "Suspended";
        }
        if (normalized.equals("off") || normalized.contains("power off")) {
            return "Off";
        }
        return tvPowerState;
    }

    /** True while the TV is reachable and not in standby or powered off. */
    public boolean isTvOn() {
        if (state != LgTvClient.State.READY) {
            return false;
        }
        return !tvPowerKnown || !isOffPowerState(tvPowerState);
    }

    public boolean isTvOff() {
        return tvPowerKnown && isOffPowerState(tvPowerState);
    }

    public boolean isScreenOff() {
        return tvPowerKnown && tvPowerState != null
                && tvPowerState.toLowerCase(java.util.Locale.US).contains("screen")
                && tvPowerState.toLowerCase(java.util.Locale.US).contains("off");
    }

    private static boolean isOffPowerState(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.US);
        return normalized.equals("off")
                || normalized.contains("power off")
                || normalized.contains("standby")
                || normalized.contains("suspend");
    }

    public boolean isTextFieldFocused() {
        return textFieldFocused;
    }

    public String getTextContentType() {
        return textContentType;
    }

    public boolean isTextHidden() {
        return textHidden;
    }

    public boolean isBoxTextFieldFocused() {
        return boxTextFocused;
    }

    public String getBoxTextFieldLabel() {
        return boxTextFieldLabel;
    }

    void setBoxTextFieldState(boolean focused, String label) {
        String safe = label == null ? "" : label;
        boolean changed = focused != boxTextFocused || !safe.equals(boxTextFieldLabel);
        boxTextFocused = focused;
        boxTextFieldLabel = safe;
        if (!changed) {
            return;
        }
        for (Listener listener : listeners) {
            listener.onBoxTextField(focused, safe);
        }
    }

    private void updateActiveControllers() {
        if (boxController != null) {
            boolean active = state == LgTvClient.State.READY && isBoxActive();
            boxController.setActive(active);
            if (active && boxMouse != null && !boxMouse.isConnected()) {
                boxMouse.autoConnect();
            }
        }
    }

    /** The controller for whatever is on screen: the box on its HDMI input, otherwise the TV. */
    public RemoteController activeRemote() {
        if (boxController != null && isBoxActive()) {
            return boxController;
        }
        return lgController;
    }

    public boolean isBoxPaired() {
        return AndroidTvRemote.isPaired(this);
    }

    /** Drops the live box session before re-pairing or forgetting the box identity. */
    public void stopBoxSession() {
        if (boxController != null) {
            boxController.setActive(false);
        }
    }

    LgTvClient getLgClient() {
        return client;
    }

    public boolean sendLgButton(String name) {
        return client != null && client.sendButton(name);
    }

    public boolean sendText(String text, LgTvClient.TextCallback callback) {
        return client != null && client.sendText(text, callback);
    }

    public boolean deleteCharacters(int count, LgTvClient.TextCallback callback) {
        return client != null && client.deleteCharacters(count, callback);
    }

    public boolean sendEnterKey(LgTvClient.TextCallback callback) {
        return client != null && client.sendEnterKey(callback);
    }

    /** Label of the HDMI input currently on screen, or null when a webOS app is in front. */
    public String getActiveInputLabel() {
        if (foregroundApp == null || foregroundApp.isEmpty()) {
            return null;
        }
        for (LgTvClient.TvInput input : inputs) {
            if (foregroundApp.equals(input.appId) || foregroundApp.equals(input.id)) {
                return input.label;
            }
        }
        return null;
    }

    /** The external input that is currently on screen, or null when a webOS app is in front. */
    public LgTvClient.TvInput getActiveInput() {
        if (foregroundApp == null || foregroundApp.isEmpty()) {
            return null;
        }
        for (LgTvClient.TvInput input : inputs) {
            if (foregroundApp.equals(input.appId) || foregroundApp.equals(input.id)) {
                return input;
            }
        }
        return null;
    }

    /** True while the HDMI input linked to the Android TV box is active. */
    public boolean isBoxActive() {
        LgTvClient.TvInput active = getActiveInput();
        if (active == null) {
            return false;
        }
        return Routing.isBoxInput(SecureStore.getBoxInputId(this), active.id, active.appId);
    }

    /** Stores which TV input belongs to the Android TV box. */
    public void linkBoxInput(String inputId) {
        LgTvClient.TvInput active = getActiveInput();
        String label = "";
        if (active != null && inputId != null && inputId.equals(active.id)) {
            label = active.label;
        } else {
            for (LgTvClient.TvInput input : inputs) {
                if (inputId != null && inputId.equals(input.id)) {
                    label = input.label;
                    break;
                }
            }
        }
        SecureStore.setBoxInput(this, inputId, label);
        for (Listener listener : listeners) {
            listener.onConnection(state, detail, displayName(), tvModel);
        }
        updateActiveControllers();
    }

    /** Links the box to the current external input if the user just paired it on screen. */
    public void linkBoxToActiveInput() {
        LgTvClient.TvInput active = getActiveInput();
        if (active != null) {
            SecureStore.setBoxInput(this, active.id, active.label);
        }
        updateActiveControllers();
    }

    public void openTvSettings() {
        if (client != null) {
            client.launchSettings();
        }
    }

    public void requestScreenshot(LgTvClient.ScreenshotCallback callback) {
        if (client != null) {
            client.requestScreenshot(callback);
        } else {
            callback.onResult(null, "Not connected");
        }
    }

    public void reconnect() {
        if (client.getIp() != null) {
            ensureForeground();
            client.reconnectNow();
        } else if (ip != null) {
            connectInternal(ip, tvName);
        }
    }

    public void requestApps() {
        client.requestApps();
    }

    public void requestInputs() {
        client.requestInputs();
    }

    public void launchApp(String appId) {
        client.launchApp(appId);
    }

    public void switchInput(String inputId) {
        client.switchInput(inputId);
    }

    public void powerOff() {
        setTvPowerState("Power Off", true);
        client.powerOff();
    }

    public void turnOnScreen() {
        client.turnOnScreen();
        setTvPowerState("Active", true);
    }

    public boolean sendButton(String name) {
        return client.sendButton(name);
    }

    public boolean sendClick() {
        if (isBoxActive()) {
            return boxMouse != null && boxMouse.sendClick();
        }
        return client.sendClick();
    }

    public boolean sendMove(int dx, int dy, boolean down) {
        if (isBoxActive()) {
            return boxMouse != null && boxMouse.sendMove(dx, dy, down);
        }
        return client.sendMove(dx, dy, down);
    }

    public boolean sendScroll(int dx, int dy) {
        if (isBoxActive()) {
            return boxMouse != null && boxMouse.sendScroll(dx, dy);
        }
        return client.sendScroll(dx, dy);
    }

    public BoxBluetoothMouse getBoxMouse() {
        return boxMouse;
    }

    public boolean isBoxMouseConnected() {
        return boxMouse != null && boxMouse.isConnected();
    }

    private void notifyBoxMouseStatus() {
        for (Listener listener : listeners) {
            listener.onBoxMouseStatus();
        }
    }

    public boolean wakeOnLan() {
        String mac = SecureStore.getMac(this);
        if (!WakeOnLan.isValidMac(mac)) {
            return false;
        }
        WakeOnLan.send(mac, ip);
        return true;
    }

    @Override
    public void onState(LgTvClient.State newState, String newDetail) {
        boolean lostPowerState = newState != LgTvClient.State.READY && tvPowerKnown;
        state = newState;
        detail = newDetail;
        if (newState != LgTvClient.State.PAIRING) {
            setPinPairing(false, false, null);
        }
        if (newState != LgTvClient.State.READY) {
            // Keep the last state for the power button, but do not present it as live.
            tvPowerKnown = false;
        }
        if (newState == LgTvClient.State.READY && client.getIp() != null
                && !client.getIp().equals(ip)) {
            ip = client.getIp();
        }
        updateNotification();
        for (Listener listener : listeners) {
            listener.onConnection(state, detail, displayName(), tvModel);
            if (lostPowerState) {
                listener.onTvPower(null);
            }
        }
        updateActiveControllers();
    }

    @Override
    public void onPairingPin(boolean required, boolean submitting, String error) {
        setPinPairing(required, submitting, error);
    }

    private void setPinPairing(boolean required, boolean submitting, String error) {
        if (!required) {
            submitting = false;
            error = null;
        }
        if (pinPairing == required && pinSubmitting == submitting
                && (pinError == null ? error == null : pinError.equals(error))) {
            return;
        }
        pinPairing = required;
        pinSubmitting = submitting;
        pinError = error;
        updateNotification();
        for (Listener listener : listeners) {
            listener.onPairingPin(required, submitting, error);
        }
    }

    @Override
    public void onTvPower(String powerState) {
        setTvPowerState(powerState, true);
    }

    private void setTvPowerState(String powerState, boolean known) {
        boolean changed = known != tvPowerKnown
                || (powerState == null ? tvPowerState != null
                : !powerState.equals(tvPowerState));
        tvPowerState = powerState == null ? "" : powerState;
        tvPowerKnown = known;
        if (!changed) {
            return;
        }
        updateNotification();
        for (Listener listener : listeners) {
            listener.onTvPower(known ? tvPowerState : null);
        }
    }

    @Override
    public void onTvInfo(String name, String model) {
        if (name != null && !name.isEmpty() && (tvName == null || tvName.isEmpty())) {
            tvName = name;
            SecureStore.setSavedName(this, name);
        }
        if (model != null && !model.isEmpty()) {
            tvModel = model;
            if (tvName == null || tvName.isEmpty()) {
                tvName = model;
                SecureStore.setSavedName(this, model);
            }
        }
        for (Listener listener : listeners) {
            listener.onConnection(state, detail, displayName(), tvModel);
        }
    }

    @Override
    public void onVolume(int newVolume, boolean newMuted) {
        volume = newVolume;
        muted = newMuted;
        for (Listener listener : listeners) {
            listener.onVolume(volume, muted);
        }
    }

    @Override
    public void onApps(List<LgTvClient.TvApp> newApps) {
        apps = newApps;
        for (Listener listener : listeners) {
            listener.onApps(apps);
        }
    }

    @Override
    public void onInputs(List<LgTvClient.TvInput> newInputs) {
        inputs = newInputs;
        for (Listener listener : listeners) {
            listener.onInputs(inputs);
        }
        updateActiveControllers();
    }

    @Override
    public void onForegroundApp(String appId) {
        foregroundApp = appId == null ? "" : appId;
        for (Listener listener : listeners) {
            listener.onForegroundApp(foregroundApp);
        }
        updateActiveControllers();
    }

    @Override
    public void onTextField(boolean focused, String contentType, boolean hidden) {
        textFieldFocused = focused;
        textContentType = contentType == null || contentType.isEmpty() ? "text" : contentType;
        textHidden = hidden;
        for (Listener listener : listeners) {
            listener.onTextField(focused, textContentType, textHidden);
        }
    }

    @Override
    public void onClientKey(String clientKey) {
        if (ip != null) {
            SecureStore.saveClientKey(this, ip, clientKey);
            Log.d(TAG, "client key saved for " + ip);
        }
    }

    @Override
    public void onMac(String mac) {
        if (WakeOnLan.isValidMac(mac)) {
            SecureStore.setMac(this, mac);
        }
    }

    @Override
    public void onPointer(boolean ready) {
        pointerReady = ready;
        for (Listener listener : listeners) {
            listener.onPointer(ready);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    private void updateNotification() {
        if (!foreground) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disconnect = new Intent(this, RemoteService.class).setAction(ACTION_DISCONNECT);
        PendingIntent disconnectIntent = PendingIntent.getService(this, 1, disconnect,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String name = displayName();
        String title;
        String text;
        switch (state) {
            case READY:
                title = getString(R.string.notification_connected, name);
                text = getString(R.string.notification_ready, ip == null ? "" : ip);
                break;
            case PAIRING:
                title = getString(pinPairing
                        ? R.string.notification_pairing_pin : R.string.notification_pairing);
                text = pinPairing
                        ? getString(R.string.notification_pairing_pin_detail)
                        : getString(R.string.notification_connecting_detail);
                break;
            case RECONNECTING:
                title = getString(R.string.notification_reconnecting, name);
                text = detail;
                break;
            case ERROR:
                title = getString(R.string.notification_offline);
                text = detail;
                break;
            case CONNECTING:
            default:
                title = getString(R.string.notification_connecting, name);
                text = ip == null ? "" : ip;
                break;
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(Color.parseColor("#C6F36B"))
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_power),
                        getString(R.string.disconnect), disconnectIntent).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private String readPairingManifest() {
        try (InputStream in = getResources().openRawResource(R.raw.pairing)) {
            byte[] data = new byte[64 * 1024];
            int length = in.read(data);
            return new String(data, 0, Math.max(0, length), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Could not read pairing manifest", e);
            return "{\"pairingType\":\"PROMPT\",\"manifest\":{\"manifestVersion\":1,\"permissions\":[\"CONTROL_AUDIO\",\"CONTROL_POWER\",\"LAUNCH\",\"READ_INPUT_DEVICE_LIST\",\"CONTROL_INPUT_TV\",\"CONTROL_MOUSE_AND_KEYBOARD\"]}}";
        }
    }

    public static void start(Context context, String ip, String name) {
        Intent intent = new Intent(context, RemoteService.class).setAction(ACTION_CONNECT);
        if (ip != null) {
            intent.putExtra(EXTRA_IP, ip);
        }
        if (name != null) {
            intent.putExtra(EXTRA_NAME, name);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, RemoteService.class).setAction(ACTION_DISCONNECT);
        context.startService(intent);
    }
}
