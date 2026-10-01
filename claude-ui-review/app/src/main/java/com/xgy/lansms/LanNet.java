package com.xgy.lansms;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.URL;
import java.net.URLConnection;

/** LAN helpers that deliberately prefer the physical Wi-Fi network over a VPN. */
public final class LanNet {
    private LanNet() {}

    public static Network wifi(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static URLConnection open(Context context, URL url) throws Exception {
        Network n = wifi(context);
        if (n == null) throw new IllegalStateException("No Wi-Fi LAN available");
        return n.openConnection(url);
    }

    public static void bindWifi(Context context, DatagramSocket socket) {
        try {
            Network n = wifi(context);
            if (n != null) n.bindSocket(socket);
        } catch (Exception ignored) {}
    }

    public static String wifiIpv4(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Network n = wifi(context);
            if (cm != null && n != null) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                            return la.getAddress().getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }
}
