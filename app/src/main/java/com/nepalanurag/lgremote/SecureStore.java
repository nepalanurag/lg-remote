package com.nepalanurag.lgremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small preference store. The LG client key is encrypted with an AES-GCM key that never leaves
 * the Android Keystore; the MAC and UI preferences are plain (they are not secrets).
 */
public final class SecureStore {
    private static final String PREFS = "lgremote";
    private static final String ALIAS = "lgremote-client-key";
    private static final String PREFIX_KEY = "clientkey_";
    private static final String PREFIX_SECRET = "secret_";
    private static final String PREF_TV_IP = "tv_ip";
    private static final String PREF_TV_NAME = "tv_name";
    private static final String PREF_MAC = "tv_mac";
    private static final String PREF_POINTER_SPEED = "pointer_speed";
    private static final String PREF_HAPTICS = "haptics";
    private static final String PREF_AUTO_CONNECT = "auto_connect";
    private static final String PREF_BOX_HOST = "box_host";
    private static final String PREF_BOX_PAIRED = "box_paired";
    private static final String PREF_BOX_NAME = "box_name";
    private static final String PREF_BOX_INPUT_ID = "box_input_id";
    private static final String PREF_BOX_INPUT_LABEL = "box_input_label";
    private static final String PREF_BOX_MAC = "box_mac";
    private static final String PREF_BOX_BT_MAC = "box_bt_mac";

    // Older builds stored the same Android TV pairing under "den" keys. Read them once so an
    // existing pairing keeps working after the app became device-name agnostic.
    private static final String LEGACY_BOX_HOST = "den_host";
    private static final String LEGACY_BOX_PAIRED = "den_paired";
    private static final String LEGACY_BOX_CERT = "den_cert";
    private static final String LEGACY_BOX_KEY = "den_key";

    private SecureStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String slot(String ip) {
        return PREFIX_KEY + ip.replaceAll("[^A-Za-z0-9]", "_");
    }

    public static synchronized void saveClientKey(Context context, String ip, String key) {
        if (ip == null || key == null || key.isEmpty()) {
            return;
        }
        try {
            prefs(context).edit().putString(slot(ip), encrypt(key)).apply();
        } catch (Exception ignored) {
            // Pairing still works for this session; the TV will prompt again next launch.
        }
    }

    public static synchronized String getClientKey(Context context, String ip) {
        if (ip == null) {
            return null;
        }
        String encoded = prefs(context).getString(slot(ip), null);
        if (encoded == null) {
            return null;
        }
        try {
            return decrypt(encoded);
        } catch (Exception ignored) {
            // Keystore key was invalidated or data is corrupt: forget it and pair again.
            forgetClientKey(context, ip);
            return null;
        }
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[packed.length - 12];
        System.arraycopy(packed, 0, iv, 0, 12);
        System.arraycopy(packed, 12, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    public static boolean hasClientKey(Context context, String ip) {
        return ip != null && prefs(context).contains(slot(ip));
    }

    public static void forgetClientKey(Context context, String ip) {
        if (ip != null) {
            prefs(context).edit().remove(slot(ip)).apply();
        }
    }

    private static String secretSlot(String name) {
        return PREFIX_SECRET + name;
    }

    private static void putSecret(Context context, String name, byte[] value) {
        if (value == null) {
            return;
        }
        try {
            prefs(context).edit()
                    .putString(secretSlot(name),
                            encrypt(Base64.encodeToString(value, Base64.NO_WRAP)))
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static byte[] getSecret(Context context, String name) {
        String encoded = prefs(context).getString(secretSlot(name), null);
        if (encoded == null) {
            return null;
        }
        try {
            return Base64.decode(decrypt(encoded), Base64.NO_WRAP);
        } catch (Exception ignored) {
            removeSecret(context, name);
            return null;
        }
    }

    private static void removeSecret(Context context, String name) {
        prefs(context).edit().remove(secretSlot(name)).apply();
    }

    private static boolean hasSecret(Context context, String primary, String legacy) {
        return prefs(context).contains(secretSlot(primary))
                || prefs(context).contains(secretSlot(legacy));
    }

    private static byte[] getSecretOrLegacy(Context context, String primary, String legacy) {
        byte[] value = getSecret(context, primary);
        return value != null ? value : getSecret(context, legacy);
    }

    public static void saveBoxIdentity(Context context, byte[] certDer, byte[] privateKeyPkcs8) {
        putSecret(context, "box_cert", certDer);
        putSecret(context, "box_key", privateKeyPkcs8);
        removeSecret(context, LEGACY_BOX_CERT);
        removeSecret(context, LEGACY_BOX_KEY);
    }

    public static byte[] getBoxCert(Context context) {
        return getSecretOrLegacy(context, "box_cert", LEGACY_BOX_CERT);
    }

    public static byte[] getBoxKey(Context context) {
        return getSecretOrLegacy(context, "box_key", LEGACY_BOX_KEY);
    }

    public static boolean hasBoxIdentity(Context context) {
        return hasSecret(context, "box_cert", LEGACY_BOX_CERT)
                && hasSecret(context, "box_key", LEGACY_BOX_KEY);
    }

    public static boolean isBoxPaired(Context context) {
        boolean paired = prefs(context).getBoolean(PREF_BOX_PAIRED, false)
                || prefs(context).getBoolean(LEGACY_BOX_PAIRED, false);
        return hasBoxIdentity(context) && paired;
    }

    public static void setBoxPaired(Context context, boolean paired) {
        prefs(context).edit()
                .putBoolean(PREF_BOX_PAIRED, paired)
                .remove(LEGACY_BOX_PAIRED)
                .apply();
    }

    public static String getBoxHost(Context context) {
        String host = prefs(context).getString(PREF_BOX_HOST, null);
        if (host == null || host.isEmpty()) {
            host = prefs(context).getString(LEGACY_BOX_HOST, null);
        }
        return host;
    }

    public static void setBoxHost(Context context, String host) {
        prefs(context).edit()
                .putString(PREF_BOX_HOST, host == null ? "" : host)
                .remove(LEGACY_BOX_HOST)
                .apply();
    }

    public static String getBoxName(Context context) {
        return prefs(context).getString(PREF_BOX_NAME, "");
    }

    public static void setBoxName(Context context, String name) {
        prefs(context).edit().putString(PREF_BOX_NAME,
                name == null ? "" : name.trim()).apply();
    }

    public static String getBoxInputId(Context context) {
        return prefs(context).getString(PREF_BOX_INPUT_ID, "");
    }

    public static void setBoxInput(Context context, String id, String label) {
        prefs(context).edit()
                .putString(PREF_BOX_INPUT_ID, id == null ? "" : id)
                .putString(PREF_BOX_INPUT_LABEL, label == null ? "" : label)
                .apply();
    }

    public static String getBoxInputLabel(Context context) {
        return prefs(context).getString(PREF_BOX_INPUT_LABEL, "");
    }

    /**
     * Forgets only the box certificate and paired flag. The address, display name and HDMI link
     * stay so a re-pair can go straight to the same box and keep the input association.
     */
    public static void resetBoxIdentity(Context context) {
        removeSecret(context, "box_cert");
        removeSecret(context, "box_key");
        removeSecret(context, LEGACY_BOX_CERT);
        removeSecret(context, LEGACY_BOX_KEY);
        prefs(context).edit()
                .remove(PREF_BOX_PAIRED)
                .remove(LEGACY_BOX_PAIRED)
                .apply();
    }

    public static void forgetBox(Context context) {
        String host = getBoxHost(context);
        String name = getBoxName(context);
        String inputId = getBoxInputId(context);
        String inputLabel = getBoxInputLabel(context);
        resetBoxIdentity(context);
        // Keep host, name and input link. They are not credentials and make re-pairing reliable.
        prefs(context).edit()
                .putString(PREF_BOX_HOST, host == null ? "" : host)
                .putString(PREF_BOX_NAME, name == null ? "" : name)
                .putString(PREF_BOX_INPUT_ID, inputId == null ? "" : inputId)
                .putString(PREF_BOX_INPUT_LABEL, inputLabel == null ? "" : inputLabel)
                .remove(LEGACY_BOX_HOST)
                .apply();
    }

    private static SecretKey aesKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return (SecretKey) keyStore.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    public static String getSavedIp(Context context) {
        return prefs(context).getString(PREF_TV_IP, null);
    }

    public static String getSavedName(Context context) {
        return prefs(context).getString(PREF_TV_NAME, "");
    }

    public static void setSavedTv(Context context, String ip, String name) {
        prefs(context).edit()
                .putString(PREF_TV_IP, ip)
                .putString(PREF_TV_NAME, name == null ? "" : name)
                .apply();
    }

    public static void setSavedName(Context context, String name) {
        if (name != null && !name.isEmpty()) {
            prefs(context).edit().putString(PREF_TV_NAME, name).apply();
        }
    }

    public static String getMac(Context context) {
        return prefs(context).getString(PREF_MAC, "");
    }

    public static void setMac(Context context, String mac) {
        prefs(context).edit().putString(PREF_MAC, mac == null ? "" : mac).apply();
    }

    public static String getBoxMac(Context context) {
        String saved = prefs(context).getString(PREF_BOX_MAC, "");
        if (saved.isEmpty()) {
            String host = getBoxHost(context);
            if ("192.168.50.254".equals(host)) {
                return "A4:E8:8D:F1:81:22";
            }
        }
        return saved;
    }

    public static void setBoxMac(Context context, String mac) {
        prefs(context).edit().putString(PREF_BOX_MAC, mac == null ? "" : mac).apply();
    }

    public static String getBoxBtMac(Context context) {
        return prefs(context).getString(PREF_BOX_BT_MAC, "");
    }

    public static void setBoxBtMac(Context context, String mac) {
        prefs(context).edit().putString(PREF_BOX_BT_MAC, mac == null ? "" : mac).apply();
    }

    public static float getPointerSpeed(Context context) {
        return Math.max(0.5f, Math.min(3f, prefs(context).getFloat(PREF_POINTER_SPEED, 1.6f)));
    }

    public static void setPointerSpeed(Context context, float speed) {
        prefs(context).edit().putFloat(PREF_POINTER_SPEED,
                Math.max(0.5f, Math.min(3f, speed))).apply();
    }

    public static boolean getHaptics(Context context) {
        return prefs(context).getBoolean(PREF_HAPTICS, true);
    }

    public static void setHaptics(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_HAPTICS, enabled).apply();
    }

    public static boolean getAutoConnect(Context context) {
        return prefs(context).getBoolean(PREF_AUTO_CONNECT, true);
    }

    public static void setAutoConnect(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_AUTO_CONNECT, enabled).apply();
    }

    /** Fresh random key material for tests of the wire format (never stored). */
    static String randomClientKeyForTest() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
