package com.khalief.karaoke.tv;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Cari alamat IPv4 lokal TV (untuk QR dan info). */
public class NetworkUtil {

    public static String getLocalIpAddress(Context ctx) {
        String ip = fromInterfaces();
        if (ip != null) return ip;
        return fromWifiManager(ctx);
    }

    private static String fromInterfaces() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    if (!addr.isSiteLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("192.168.")) return ip; // utamakan WiFi rumahan
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fromWifiManager(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int raw = wm.getConnectionInfo().getIpAddress();
            if (raw == 0) return null;
            return (raw & 0xff) + "." + ((raw >> 8) & 0xff) + "."
                    + ((raw >> 16) & 0xff) + "." + ((raw >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }
}
