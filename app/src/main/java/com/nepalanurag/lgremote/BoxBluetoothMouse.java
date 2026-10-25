package com.nepalanurag.lgremote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    private static final int REPORT_ID = 1;

    // Standard USB HID Mouse report descriptor: 1-byte buttons (left/right/middle), 1-byte X, 1-byte Y, 1-byte wheel.
    private static final byte[] MOUSE_REPORT_DESCRIPTOR = {
            (byte) 0x05, (byte) 0x01, // USAGE_PAGE (Generic Desktop)
            (byte) 0x09, (byte) 0x02, // USAGE (Mouse)
            (byte) 0xA1, (byte) 0x01, // COLLECTION (Application)
            (byte) 0x85, (byte) 0x01, //   REPORT_ID (1)
            (byte) 0x09, (byte) 0x01, //   USAGE (Pointer)
            (byte) 0xA1, (byte) 0x00, //   COLLECTION (Physical)
            (byte) 0x05, (byte) 0x09, //     USAGE_PAGE (Button)
            (byte) 0x19, (byte) 0x01, //     USAGE_MINIMUM (Button 1)
            (byte) 0x29, (byte) 0x03, //     USAGE_MAXIMUM (Button 3)
            (byte) 0x15, (byte) 0x00, //     LOGICAL_MINIMUM (0)
            (byte) 0x25, (byte) 0x01, //     LOGICAL_MAXIMUM (1)
            (byte) 0x95, (byte) 0x03, //     REPORT_COUNT (3)
            (byte) 0x75, (byte) 0x01, //     REPORT_SIZE (1)
            (byte) 0x81, (byte) 0x02, //     INPUT (Data,Var,Abs)
            (byte) 0x95, (byte) 0x01, //     REPORT_COUNT (1)
            (byte) 0x75, (byte) 0x05, //     REPORT_SIZE (5)
            (byte) 0x81, (byte) 0x03, //     INPUT (Cnst,Var,Abs)
            (byte) 0x05, (byte) 0x01, //     USAGE_PAGE (Generic Desktop)
            (byte) 0x09, (byte) 0x30, //     USAGE (X)
            (byte) 0x09, (byte) 0x31, //     USAGE (Y)
            (byte) 0x09, (byte) 0x38, //     USAGE (Wheel)
            (byte) 0x15, (byte) 0x81, //     LOGICAL_MINIMUM (-127)
            (byte) 0x25, (byte) 0x7F, //     LOGICAL_MAXIMUM (127)
            (byte) 0x75, (byte) 0x08, //     REPORT_SIZE (8)
            (byte) 0x95, (byte) 0x03, //     REPORT_COUNT (3)
            (byte) 0x81, (byte) 0x06, //     INPUT (Data,Var,Rel)
            (byte) 0xC0,             //   END_COLLECTION
            (byte) 0xC0              // END_COLLECTION
    };

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
            if (registered && pluggedDevice != null) {
                connectedDevice = pluggedDevice;
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
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice != null && connectedDevice.equals(device)) {
                    connectedDevice = null;
                }
            }
            notifyListener();
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hidDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (hasPermission()) {
                    try {
                        hidDevice.replyReport(device, type, id, new byte[]{0, 0, 0, 0});
                    } catch (SecurityException ignored) {
                    }
                }
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (hidDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (hasPermission()) {
                    try {
                        hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                    } catch (SecurityException ignored) {
                    }
                }
            }
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
                "LG Remote Mouse",
                "Android TV Bluetooth Mouse",
                "LG Remote",
                BluetoothHidDevice.SUBCLASS1_MOUSE,
                MOUSE_REPORT_DESCRIPTOR
        );
        try {
            hidDevice.registerApp(sdp, null, null, executor, hidCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot register Bluetooth HID app: " + e.getMessage());
        }
    }

    public boolean sendMove(int dx, int dy, boolean buttonDown) {
        if (!isConnected() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }
        byte buttons = (byte) (buttonDown ? 0x01 : 0x00);
        byte rx = (byte) Math.max(-127, Math.min(127, dx));
        byte ry = (byte) Math.max(-127, Math.min(127, dy));
        try {
            return hidDevice.sendReport(connectedDevice, REPORT_ID, new byte[]{buttons, rx, ry, 0});
        } catch (SecurityException e) {
            return false;
        }
    }

    public boolean sendClick() {
        if (!isConnected() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }
        try {
            // Button 1 down
            boolean ok = hidDevice.sendReport(connectedDevice, REPORT_ID, new byte[]{0x01, 0, 0, 0});
            // Button 1 up after 35ms
            executor.schedule(() -> {
                if (isConnected() && hasPermission()) {
                    try {
                        hidDevice.sendReport(connectedDevice, REPORT_ID, new byte[]{0x00, 0, 0, 0});
                    } catch (SecurityException ignored) {
                    }
                }
            }, 35, TimeUnit.MILLISECONDS);
            return ok;
        } catch (SecurityException e) {
            return false;
        }
    }

    public boolean sendScroll(int dx, int dy) {
        if (!isConnected() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) {
            return false;
        }
        byte wheel = (byte) Math.max(-127, Math.min(127, dy));
        try {
            return hidDevice.sendReport(connectedDevice, REPORT_ID, new byte[]{0, 0, 0, wheel});
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
