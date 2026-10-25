package com.nepalanurag.lgremote;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Wake-on-LAN magic packet, sent to the broadcast address and directly to the TV. */
public final class WakeOnLan {
    private WakeOnLan() {
    }

    public static boolean isValidMac(String mac) {
        return normalize(mac) != null;
    }

    public static String normalize(String mac) {
        if (mac == null) {
            return null;
        }
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) {
            return null;
        }
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString().toUpperCase(java.util.Locale.US);
    }

    public static void send(String mac, String host) {
        String normalized = normalize(mac);
        if (normalized == null) {
            return;
        }
        byte[] macBytes = new byte[6];
        String hex = normalized.replace(":", "");
        for (int i = 0; i < 6; i++) {
            macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int i = 0; i < 16; i++) {
            System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6);
        }

        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                sendRepeated(socket, packet, InetAddress.getByName("255.255.255.255"), 9);
                sendRepeated(socket, packet, InetAddress.getByName("255.255.255.255"), 7);
                if (host != null && !host.isEmpty()) {
                    try {
                        InetAddress addr = InetAddress.getByName(host);
                        sendRepeated(socket, packet, addr, 9);
                        sendRepeated(socket, packet, addr, 7);
                        byte[] ipBytes = addr.getAddress();
                        if (ipBytes.length == 4) {
                            ipBytes[3] = (byte) 255;
                            sendRepeated(socket, packet, InetAddress.getByAddress(ipBytes), 9);
                            sendRepeated(socket, packet, InetAddress.getByAddress(ipBytes), 7);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
                // Best effort: power-on only works with Wake-on-LAN enabled on the target device.
            }
        }, "wake-on-lan").start();
    }

    private static void sendRepeated(DatagramSocket socket, byte[] packet, InetAddress target, int port) {
        try {
            DatagramPacket datagram = new DatagramPacket(packet, packet.length, target, port);
            for (int i = 0; i < 3; i++) {
                socket.send(datagram);
                Thread.sleep(40);
            }
        } catch (Exception ignored) {
        }
    }
}
