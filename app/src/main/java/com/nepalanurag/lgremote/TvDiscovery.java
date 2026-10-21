package com.nepalanurag.lgremote;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Finds LG webOS TVs with SSDP, then reads their UPnP description for a friendly name. */
public final class TvDiscovery {
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final String LG_TARGET = "urn:lge-com:service:webos-second-screen:1";

    public static final class Tv {
        public final String ip;
        public final String name;
        public final String model;

        Tv(String ip, String name, String model) {
            this.ip = ip;
            this.name = name;
            this.model = model;
        }
    }

    public interface Callback {
        void onResults(List<Tv> tvs);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build();
    private volatile DatagramSocket socket;

    public TvDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start(Callback callback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            List<Tv> found;
            try {
                found = search();
            } catch (Exception e) {
                found = Collections.emptyList();
            }
            running.set(false);
            List<Tv> result = found;
            main.post(() -> callback.onResults(result));
        });
    }

    public void stop() {
        running.set(false);
        DatagramSocket current = socket;
        if (current != null) {
            current.close();
        }
    }

    private List<Tv> search() throws IOException {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wifi != null) {
            lock = wifi.createMulticastLock("lgremote-discovery");
            lock.setReferenceCounted(false);
            lock.acquire();
        }

        Map<String, String> locations = new LinkedHashMap<>();
        DatagramSocket ds = new DatagramSocket();
        socket = ds;
        try {
            ds.setBroadcast(true);
            ds.setSoTimeout(500);
            InetAddress group = InetAddress.getByName(GROUP);

            String[] searches = {
                    mSearch(LG_TARGET),
                    mSearch("ssdp:all")
            };
            for (int round = 0; round < 3; round++) {
                for (String search : searches) {
                    byte[] data = search.getBytes("UTF-8");
                    ds.send(new DatagramPacket(data, data.length, group, PORT));
                }
                Thread.sleep(220);
            }

            long deadline = System.currentTimeMillis() + 3500;
            byte[] buffer = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    ds.receive(packet);
                    String response = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    String location = header(response, "location");
                    if (location != null) {
                        String host = hostOf(location);
                        if (host != null) {
                            locations.put(host, location);
                        }
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // keep listening until the deadline
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                ds.close();
            } catch (Exception ignored) {
            }
            socket = null;
            if (lock != null) {
                try {
                    lock.release();
                } catch (Exception ignored) {
                }
            }
        }

        List<Tv> tvs = new ArrayList<>();
        for (Map.Entry<String, String> entry : locations.entrySet()) {
            String ip = entry.getKey();
            String name = "";
            String model = "";
            try {
                String xml = fetch(entry.getValue());
                String manufacturer = xmlField(xml, "manufacturer");
                String friendly = xmlField(xml, "friendlyName");
                String modelNumber = xmlField(xml, "modelNumber");
                if (friendly != null) {
                    name = friendly;
                }
                if (modelNumber != null) {
                    model = modelNumber;
                }
                boolean looksLg = (manufacturer != null && manufacturer.toLowerCase(Locale.US).contains("lg"))
                        || (friendly != null && friendly.toLowerCase(Locale.US).contains("lg"));
                if (!looksLg && xml.contains("webos-second-screen")) {
                    looksLg = true;
                }
                if (!looksLg) {
                    continue;
                }
            } catch (Exception ignored) {
                // SSDP answered but the description did not; still offer the address.
            }
            if (name.isEmpty()) {
                name = "LG webOS TV";
            }
            tvs.add(new Tv(ip, name, model));
        }
        tvs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return tvs;
    }

    private String fetch(String location) throws IOException {
        Request request = new Request.Builder().url(location).build();
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) {
                return "";
            }
            String body = response.body().string();
            return body.length() > 256 * 1024 ? body.substring(0, 256 * 1024) : body;
        }
    }

    private static String mSearch(String target) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + GROUP + ":" + PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + target + "\r\n\r\n";
    }

    private static String header(String response, String name) {
        for (String line : response.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String hostOf(String location) {
        try {
            java.net.URI uri = java.net.URI.create(location);
            if (uri.getHost() == null) {
                return null;
            }
            // The description port is the UPnP port, not the SSAP port; only the address is kept.
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String xmlField(String xml, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
