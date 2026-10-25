package com.nepalanurag.lgremote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Emulates a standard Bluetooth HID Mouse so the Android TV box gets a real, native cursor on
 * screen while its HDMI input is active.
 *
 * <p>Uses the Android 9+ (API 28) BluetoothHidDevice API. The phone registers as a wireless mouse
 * with standard 4-byte report descriptors (buttons, X delta, Y delta, scroll wheel). When paired
 * in the Android TV's "Remotes & Accessories" settings, the Android OS natively renders its circular
 * pointer on screen and delivers true mouse hover and click events to all apps.</p>
 */
public final class BoxBluetoothMouse {
    private static final String TAG = "LgRemote";
    public static final byte REPORT_ID_KEYBOARD = 1;
    public static final byte REPORT_ID_MOUSE = 2;
    public static final byte REPORT_ID_BATTERY = 32;

    private static final byte[] COMBO_REPORT_DESCRIPTOR = {
            // Keyboard
            (byte) 0x05, (byte) 0x01, // Usage page (Generic Desktop)
            (byte) 0x09, (byte) 0x06, // Usage (Keyboard)
            (byte) 0xA1, (byte) 0x01, // Collection (Application)
            (byte) 0x85, REPORT_ID_KEYBOARD, // Report ID (1)
            (byte) 0x05, (byte) 0x07, // Usage page (Key Codes)
            (byte) 0x19, (byte) 0xE0, // Usage minimum (224)
            (byte) 0x29, (byte) 0xE7, // Usage maximum (231)
            (byte) 0x15, (byte) 0x00, // Logical minimum (0)
            (byte) 0x25, (byte) 0x01, // Logical maximum (1)
            (byte) 0x75, (byte) 0x01, // Report size (1)
            (byte) 0x95, (byte) 0x08, // Report count (8)
            (byte) 0x81, (byte) 0x02, // Input (Data, Variable, Absolute)
            (byte) 0x75, (byte) 0x08, // Report size (8)
            (byte) 0x95, (byte) 0x01, // Report count (1)
            (byte) 0x81, (byte) 0x01, // Input (Constant)
            (byte) 0x75, (byte) 0x08, // Report size (8)
            (byte) 0x95, (byte) 0x06, // Report count (6)
            (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
            (byte) 0x25, (byte) 0x65, // Logical Maximum (101)
            (byte) 0x05, (byte) 0x07, // Usage page (Key Codes)
            (byte) 0x19, (byte) 0x00, // Usage Minimum (0)
            (byte) 0x29, (byte) 0x65, // Usage Maximum (101)
            (byte) 0x81, (byte) 0x00, // Input (Data, Array)
            (byte) 0xC0,              // End Collection

            // Mouse
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x02, // Usage (Mouse)
            (byte) 0xA1, (byte) 0x01, // Collection (Application)
            (byte) 0x85, REPORT_ID_MOUSE, // Report ID (2)
            (byte) 0x09, (byte) 0x01, // Usage (Pointer)
            (byte) 0xA1, (byte) 0x00, // Collection (Physical)
            (byte) 0x05, (byte) 0x09, // Usage Page (Buttons)
            (byte) 0x19, (byte) 0x01, // Usage minimum (1)
            (byte) 0x29, (byte) 0x03, // Usage maximum (3)
            (byte) 0x15, (byte) 0x00, // Logical minimum (0)
            (byte) 0x25, (byte) 0x01, // Logical maximum (1)
            (byte) 0x75, (byte) 0x01, // Report size (1)
            (byte) 0x95, (byte) 0x03, // Report count (3)
            (byte) 0x81, (byte) 0x02, // Input (Data, Variable, Absolute)
            (byte) 0x75, (byte) 0x05, // Report size (5)
            (byte) 0x95, (byte) 0x01, // Report count (1)
            (byte) 0x81, (byte) 0x01, // Input (constant)
            (byte) 0x05, (byte) 0x01, // Usage page (Generic Desktop)
            (byte) 0x09, (byte) 0x30, // Usage (X)
            (byte) 0x09, (byte) 0x31, // Usage (Y)
            (byte) 0x09, (byte) 0x38, // Usage (Wheel)
            (byte) 0x15, (byte) 0x81, // Logical minimum (-127)
            (byte) 0x25, (byte) 0x7F, // Logical maximum (127)
            (byte) 0x75, (byte) 0x08, // Report size (8)
            (byte) 0x95, (byte) 0x03, // Report count (3)
            (byte) 0x81, (byte) 0x06, // Input (Data, Variable, Relative)
            (byte) 0x05, (byte) 0x0C, // Usage page (Consumer)
            (byte) 0x0A, (byte) 0x38, (byte) 0x02, // Usage (AC Pan)
            (byte) 0x15, (byte) 0x81, // Logical minimum (-127)
            (byte) 0x25, (byte) 0x7F, // Logical maximum (127)
            (byte) 0x75, (byte) 0x08, // Report size (8)
            (byte) 0x95, (byte) 0x01, // Report count (1)
            (byte) 0x81, (byte) 0x06, // Input (Data, Variable, Relative)
            (byte) 0xC0,              // End Collection
            (byte) 0xC0               // End Collection
    };

    private static final BluetoothHidDeviceAppQosSettings QOS_OUT = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            ? new BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    800,
                    9,
                    0,
                    11250,
                    BluetoothHidDeviceAppQosSettings.MAX)
            : null;

    private final byte[] lastMouseReport = new byte[]{0, 0, 0, 0, 0};

    public interface Listener {
        void onBluetoothMouseStatusChanged();
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedDevice;
    private boolean registered;
    private Listener listener;

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                hidDevice = (BluetoothHidDevice) proxy;
                Log.d(TAG, "Bluetooth HID Device profile proxy connected");
                registerAppInternal();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile proxy disconnected");
                hidDevice = null;
                registered = false;
                connectedDevice = null;
                notifyListener();
            }
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            ? new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            Log.d(TAG, "Bluetooth HID app status changed: registered=" + registered);
            BoxBluetoothMouse.this.registered = registered;
            if (registered) {
                if (pluggedDevice != null) {
                    connectedDevice = pluggedDevice;
                    SecureStore.setBoxBtMac(context, pluggedDevice.getAddress());
                } else {
                    autoConnect();
                }
            }
            notifyListener();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            String deviceName = device != null ? device.getAddress() : "unknown";
            if (hasPermission() && device != null) {
                try {
                    deviceName = device.getName();
                } catch (SecurityException ignored) {
                }
            }
            Log.d(TAG, "Bluetooth HID connection state: device=" + deviceName + " state=" + state);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
                if (device != null) {
                    SecureStore.setBoxBtMac(context, device.getAddress());
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice != null && connectedDevice.equals(device)) {
                    connectedDevice = null;
                }
            }
            notifyListener();
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hidDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasPermission()) {
                try {
                    if (type != BluetoothHidDevice.REPORT_TYPE_INPUT) {
                        hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
                        return;
                    }
                    if (id == REPORT_ID_MOUSE) {
                        hidDevice.replyReport(device, type, id, lastMouseReport);
                    } else if (id == REPORT_ID_KEYBOARD) {
                        hidDevice.replyReport(device, type, id, new byte[8]);
                    } else {
                        hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                    }
                } catch (SecurityException ignored) {
                }
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (hidDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasPermission()) {
                try {
                    hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                } catch (SecurityException ignored) {
                }
            }
        }

        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
            Log.d(TAG, "Bluetooth HID onSetProtocol: device=" + device + " protocol=" + protocol);
        }
    } : null;

    public BoxBluetoothMouse(Context context) {
        this.context = context.getApplicationContext();
        if (isSupported()) {
            adapter = BluetoothAdapter.getDefaultAdapter();
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean isConnected() {
        return connectedDevice != null && hidDevice != null;
    }

    public String getConnectedDeviceName() {
        if (connectedDevice != null && hasPermission()) {
            try {
                return connectedDevice.getName();
            } catch (SecurityException ignored) {
            }
        }
        return null;
    }

    public void start() {
        if (!isSupported() || !isBluetoothEnabled() || !hasPermission()) {
            return;
        }
        if (hidDevice == null) {
            try {
                adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE);
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot get BluetoothHidDevice proxy: " + e.getMessage());
            }
        } else if (registered) {
            autoConnect();
        }
    }

    public boolean connect(BluetoothDevice device) {
        if (device == null || hidDevice == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }
        try {
            boolean initiated = hidDevice.connect(device);
            Log.d(TAG, "Bluetooth HID connect to " + device.getAddress() + " initiated: " + initiated);
            if (initiated) {
                SecureStore.setBoxBtMac(context, device.getAddress());
            }
            return initiated;
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot initiate Bluetooth HID connect: " + e.getMessage());
            return false;
        }
    }

    public boolean connect(String address) {
        if (address == null || address.isEmpty() || adapter == null) {
            return false;
        }
        try {
            return connect(adapter.getRemoteDevice(address));
        } catch (Exception e) {
            return false;
        }
    }

    public void autoConnect() {
        if (connectedDevice != null || hidDevice == null || !hasPermission() || adapter == null) {
            return;
        }
        if (!registered) {
            mainHandler.postDelayed(this::autoConnect, 350);
            return;
        }
        String savedMac = SecureStore.getBoxBtMac(context);
        Log.d(TAG, "Bluetooth HID autoConnect: registered=" + registered + " savedMac=" + savedMac);
        if (!savedMac.isEmpty()) {
            try {
                BluetoothDevice dev = adapter.getRemoteDevice(savedMac);
                if (dev != null) {
                    connect(dev);
                }
            } catch (Exception e) {
                Log.w(TAG, "Bluetooth HID autoConnect error: " + e.getMessage());
            }
        }
    }

    public void disconnect() {
        if (connectedDevice != null && hidDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasPermission()) {
            try {
                hidDevice.disconnect(connectedDevice);
            } catch (SecurityException ignored) {
            }
            connectedDevice = null;
            notifyListener();
        }
    }

    public java.util.Set<BluetoothDevice> getBondedDevices() {
        if (adapter == null || !hasPermission()) {
            return java.util.Collections.emptySet();
        }
        try {
            return adapter.getBondedDevices();
        } catch (SecurityException ignored) {
            return java.util.Collections.emptySet();
        }
    }

    public void stop() {
        if (hidDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (registered && hasPermission()) {
                try {
                    hidDevice.unregisterApp();
                } catch (SecurityException ignored) {
                }
            }
            if (adapter != null) {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
            }
            hidDevice = null;
            registered = false;
            connectedDevice = null;
            notifyListener();
        }
    }

    private void registerAppInternal() {
        if (hidDevice == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return;
        }
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "LG Remote",
                "Android TV Input",
                "LG Remote",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                COMBO_REPORT_DESCRIPTOR
        );
        try {
            hidDevice.registerApp(sdp, null, QOS_OUT, executor, hidCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot register Bluetooth HID app: " + e.getMessage());
        }
    }

    private final Object moveLock = new Object();
    private float accumX = 0f;
    private float accumY = 0f;
    private int pendingDx = 0;
    private int pendingDy = 0;
    private boolean pendingButton = false;
    private long lastDispatchTime = 0;
    private boolean flushScheduled = false;
    private float scrollAccumX = 0f;
    private float scrollAccumY = 0f;

    private static final long DISPATCH_INTERVAL_MS = 10;

    public boolean sendMove(int dx, int dy, boolean buttonDown) {
        if (!isConnected() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }

        // Kinetic acceleration curve tuned for large TV screens (slow movements are precise, flicks sweep across 4K)
        double dist = Math.hypot(dx, dy);
        double factor;
        if (dist < 2.5) {
            factor = 1.35;
        } else if (dist < 8.0) {
            factor = 1.35 + (dist - 2.5) * 0.28;
        } else {
            factor = 2.89 + Math.min(3.2, (dist - 8.0) * 0.20);
        }

        synchronized (moveLock) {
            accumX += (float) (dx * factor);
            accumY += (float) (dy * factor);
            int mx = (int) accumX;
            int my = (int) accumY;
            accumX -= mx;
            accumY -= my;

            pendingDx += mx;
            pendingDy += my;
            pendingButton = buttonDown;

            long now = SystemClock.uptimeMillis();
            long elapsed = now - lastDispatchTime;

            if (elapsed >= DISPATCH_INTERVAL_MS) {
                dispatchPendingMoveLocked(now);
            } else if (!flushScheduled) {
                flushScheduled = true;
                long delay = DISPATCH_INTERVAL_MS - elapsed;
                executor.schedule(this::flushPendingMove, delay, TimeUnit.MILLISECONDS);
            }
        }
        return true;
    }

    @SuppressWarnings({"NewApi", "MissingPermission"})
    private void dispatchPendingMoveLocked(long now) {
        if (pendingDx == 0 && pendingDy == 0 && !pendingButton && lastMouseReport[0] == 0) {
            lastDispatchTime = now;
            return;
        }
        if (hidDevice == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        byte rx = (byte) Math.max(-127, Math.min(127, pendingDx));
        byte ry = (byte) Math.max(-127, Math.min(127, pendingDy));
        pendingDx -= rx;
        pendingDy -= ry;

        byte buttons = (byte) (pendingButton ? 0x01 : 0x00);
        lastMouseReport[0] = buttons;
        lastMouseReport[1] = rx;
        lastMouseReport[2] = ry;
        lastMouseReport[3] = 0;
        lastMouseReport[4] = 0;
        lastDispatchTime = now;

        try {
            hidDevice.sendReport(connectedDevice, REPORT_ID_MOUSE, lastMouseReport);
        } catch (SecurityException ignored) {
        }
    }

    private void flushPendingMove() {
        synchronized (moveLock) {
            flushScheduled = false;
            if (isConnected() && hasPermission()) {
                long now = SystemClock.uptimeMillis();
                dispatchPendingMoveLocked(now);
                if ((pendingDx != 0 || pendingDy != 0) && !flushScheduled) {
                    flushScheduled = true;
                    executor.schedule(this::flushPendingMove, DISPATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    public boolean sendClick() {
        if (!isConnected() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }
        synchronized (moveLock) {
            pendingDx = 0;
            pendingDy = 0;
            accumX = 0f;
            accumY = 0f;
        }
        try {
            // Immediate button 1 (left click) down with zero latency
            lastMouseReport[0] = 0x01;
            lastMouseReport[1] = 0;
            lastMouseReport[2] = 0;
            lastMouseReport[3] = 0;
            lastMouseReport[4] = 0;
            boolean ok = hidDevice.sendReport(connectedDevice, REPORT_ID_MOUSE, new byte[]{0x01, 0, 0, 0, 0});
            // Button 1 up after 15ms for crisp, instantaneous click response
            executor.schedule(() -> {
                if (isConnected() && hasPermission()) {
                    try {
                        lastMouseReport[0] = 0x00;
                        hidDevice.sendReport(connectedDevice, REPORT_ID_MOUSE, new byte[]{0x00, 0, 0, 0, 0});
                    } catch (SecurityException ignored) {
                    }
                }
            }, 15, TimeUnit.MILLISECONDS);
            return ok;
        } catch (SecurityException e) {
            return false;
        }
    }

    public boolean sendRightClick() {
        if (!isConnected() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }
        synchronized (moveLock) {
            pendingDx = 0;
            pendingDy = 0;
            accumX = 0f;
            accumY = 0f;
        }
        try {
            // Immediate button 2 (right click) down
            lastMouseReport[0] = 0x02;
            lastMouseReport[1] = 0;
            lastMouseReport[2] = 0;
            lastMouseReport[3] = 0;
            lastMouseReport[4] = 0;
            boolean ok = hidDevice.sendReport(connectedDevice, REPORT_ID_MOUSE, new byte[]{0x02, 0, 0, 0, 0});
            // Button 2 up after 15ms
            executor.schedule(() -> {
                if (isConnected() && hasPermission()) {
                    try {
                        lastMouseReport[0] = 0x00;
                        hidDevice.sendReport(connectedDevice, REPORT_ID_MOUSE, new byte[]{0x00, 0, 0, 0, 0});
                    } catch (SecurityException ignored) {
                    }
                }
            }, 15, TimeUnit.MILLISECONDS);
            return ok;
        } catch (SecurityException e) {
            return false;
        }
    }

    public boolean sendScroll(int dx, int dy) {
        if (!isConnected() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }
        // Natural 2D scrolling: swipe up (dy > 0) scrolls down (-dy); swipe left (dx < 0) scrolls right (dx)
        scrollAccumX += dx * 0.5f;
        scrollAccumY += (-dy) * 0.5f;
        int wheelH = (int) scrollAccumX;
        int wheelV = (int) scrollAccumY;
        if (wheelH == 0 && wheelV == 0) {
            return true;
        }
        scrollAccumX -= wheelH;
        scrollAccumY -= wheelV;
        byte rxWheelH = (byte) Math.max(-127, Math.min(127, wheelH));
        byte rxWheelV = (byte) Math.max(-127, Math.min(127, wheelV));
        lastMouseReport[0] = 0;
        lastMouseReport[1] = 0;
        lastMouseReport[2] = 0;
        lastMouseReport[3] = rxWheelV;
        lastMouseReport[4] = rxWheelH;
        try {
            return hidDevice.sendReport(connectedDevice, REPORT_ID_MOUSE,
                    new byte[]{0, 0, 0, rxWheelV, rxWheelH});
        } catch (SecurityException e) {
            return false;
        }
    }

    @SuppressWarnings("MissingPermission")
    public static void requestDiscoverable(Activity activity) {
        try {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            activity.startActivity(discoverableIntent);
        } catch (SecurityException ignored) {
        }
    }

    private void notifyListener() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onBluetoothMouseStatusChanged();
            }
        });
    }
}
