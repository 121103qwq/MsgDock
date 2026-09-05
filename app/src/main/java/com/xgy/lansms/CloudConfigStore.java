package com.xgy.lansms;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Durable cloud pairing credentials. Private keys remain local to this device. */
public final class CloudConfigStore {
    private static final Object LOCK = new Object();
    private static final String KEY_RELAY_URL = "cloud_relay_url";
    private static final String KEY_LINKS = "cloud_links";
    private static final String KEY_ROOM_ID = "cloud_room_id";
    private static final String KEY_DEVICE_ID = "cloud_device_id";
    private static final String KEY_TOKEN = "cloud_token";
    private static final String KEY_PEER_DEVICE_ID = "cloud_peer_device_id";
    private static final String KEY_PEER_PUBLIC_KEY = "cloud_peer_public_key";
    private static final String KEY_PRIVATE_KEY = "cloud_private_key";

    private CloudConfigStore() {}

    public static Config load(Context context) {
        synchronized (LOCK) {
        Context app = context.getApplicationContext();
        return new Config(TargetStore.prefs(app).getString(KEY_RELAY_URL, CloudRelay.DEFAULT_RELAY_URL), loadLinks(app));
        }
    }

    public static List<CloudLink> loadLinks(Context context) {
        synchronized (LOCK) {
        SharedPreferences p = TargetStore.prefs(context.getApplicationContext());
        List<CloudLink> links = parseLinks(p.getString(KEY_LINKS, ""));
        if (!links.isEmpty()) return links;
        // Migrate the 0.4.0 single-link format; keep legacy values for rollback.
        String room = p.getString(KEY_ROOM_ID, ""), device = p.getString(KEY_DEVICE_ID, ""), token = p.getString(KEY_TOKEN, "");
        String peer = p.getString(KEY_PEER_DEVICE_ID, ""), peerKey = p.getString(KEY_PEER_PUBLIC_KEY, ""), privateKey = p.getString(KEY_PRIVATE_KEY, "");
        if (nonEmpty(room) && nonEmpty(device) && nonEmpty(token) && nonEmpty(peer) && nonEmpty(peerKey) && nonEmpty(privateKey)) {
            links.add(new CloudLink(p.getString(KEY_RELAY_URL, CloudRelay.DEFAULT_RELAY_URL), room, device, token,
                    peer, peerKey, privateKey, CloudLink.ROLE_SENDER));
        }
        return links;
        }
    }

    public static boolean save(Context context, Config config) {
        synchronized (LOCK) {
        if (config == null) return false;
        boolean saved = saveLinks(context, config.links);
        if (isSecureRelayUrl(config.relayUrl)) {
            TargetStore.prefs(context.getApplicationContext()).edit().putString(KEY_RELAY_URL, config.relayUrl).commit();
        }
        return saved;
        }
    }

    public static boolean saveLink(Context context, CloudLink link) {
        synchronized (LOCK) {
        if (link == null) return false;
        Context app = context.getApplicationContext();
        List<CloudLink> links = loadLinks(app);
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).sameEndpoint(link)) { links.set(i, link); return saveLinks(app, links); }
        }
        links.add(link);
        return saveLinks(app, links);
        }
    }

    public static boolean saveLinks(Context context, List<CloudLink> links) {
        synchronized (LOCK) {
        JSONArray array = new JSONArray();
        if (links != null) for (CloudLink link : links) if (link != null) array.put(link.toJson());
        return TargetStore.prefs(context.getApplicationContext()).edit().putString(KEY_LINKS, array.toString()).commit();
        }
    }

    /** Replaces the complete link set in one atomic SharedPreferences commit. */
    public static boolean replaceLinks(Context context, List<CloudLink> links) {
        return saveLinks(context, links);
    }

    /** Atomically clears the current and legacy single-link formats. */
    public static boolean clearLinks(Context context) {
        synchronized (LOCK) {
            return TargetStore.prefs(context.getApplicationContext()).edit()
                    .putString(KEY_LINKS, "[]")
                    .remove(KEY_ROOM_ID).remove(KEY_DEVICE_ID).remove(KEY_TOKEN)
                    .remove(KEY_PEER_DEVICE_ID).remove(KEY_PEER_PUBLIC_KEY).remove(KEY_PRIVATE_KEY)
                    .commit();
        }
    }

    /** Removes one cloud endpoint while preserving all other links. */
    public static boolean removeLink(Context context, CloudLink target) {
        synchronized (LOCK) {
            if (target == null) return false;
            List<CloudLink> links = loadLinks(context);
            boolean removed = false;
            java.util.Iterator<CloudLink> iterator = links.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().sameEndpoint(target)) { iterator.remove(); removed = true; }
            }
            if (!removed) return false;
            return links.isEmpty() ? clearLinks(context) : saveLinks(context, links);
        }
    }

    public static List<CloudLink> senderLinks(Context context) {
        List<CloudLink> out = new ArrayList<>();
        for (CloudLink link : loadLinks(context)) if (link.isSender()) out.add(link);
        return out;
    }

    public static List<CloudLink> receiverLinks(Context context) {
        List<CloudLink> out = new ArrayList<>();
        for (CloudLink link : loadLinks(context)) if (link.isReceiver()) out.add(link);
        return out;
    }

    public static void saveRelayUrl(Context context, String relayUrl) {
        synchronized (LOCK) {
        String value = relayUrl == null ? "" : relayUrl.trim();
        if (!isSecureRelayUrl(value)) throw new IllegalArgumentException("Relay URL 必须使用 HTTPS，且不能包含用户名或密码");
        TargetStore.prefs(context.getApplicationContext()).edit().putString(KEY_RELAY_URL, value).apply();
        }
    }

    public static boolean isConfigured(Config c) {
        if (c == null) return false;
        for (CloudLink link : c.links) if (isConfigured(link)) return true;
        return false;
    }

    public static boolean isConfigured(CloudLink link) {
        return link != null && isSecureRelayUrl(link.relayUrl) && nonEmpty(link.roomId) && nonEmpty(link.deviceId)
                && nonEmpty(link.token) && nonEmpty(link.peerDeviceId) && nonEmpty(link.peerPublicKey)
                && nonEmpty(link.privateKey) && (link.isSender() || link.isReceiver());
    }

    public static boolean isSecureRelayUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            URI uri = new URI(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception ignored) { return false; }
    }

    private static List<CloudLink> parseLinks(String raw) {
        List<CloudLink> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                CloudLink link = CloudLink.fromJson(array.optJSONObject(i));
                if (link != null) out.add(link);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static boolean nonEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    public static final class CloudLink {
        public static final String ROLE_SENDER = "sender";
        public static final String ROLE_RECEIVER = "receiver";
        public final String relayUrl, roomId, deviceId, token, peerDeviceId, peerPublicKey, privateKey, role, peerName;

        public CloudLink(String relayUrl, String roomId, String deviceId, String token, String peerDeviceId,
                         String peerPublicKey, String privateKey, String role) {
            this(relayUrl, roomId, deviceId, token, peerDeviceId, peerPublicKey, privateKey, role, "");
        }

        public CloudLink(String relayUrl, String roomId, String deviceId, String token, String peerDeviceId,
                         String peerPublicKey, String privateKey, String role, String peerName) {
            this.relayUrl = relayUrl == null ? "" : relayUrl.trim(); this.roomId = roomId == null ? "" : roomId;
            this.deviceId = deviceId == null ? "" : deviceId; this.token = token == null ? "" : token;
            this.peerDeviceId = peerDeviceId == null ? "" : peerDeviceId; this.peerPublicKey = peerPublicKey == null ? "" : peerPublicKey;
            this.privateKey = privateKey == null ? "" : privateKey; this.role = ROLE_RECEIVER.equals(role) ? ROLE_RECEIVER : ROLE_SENDER;
            this.peerName = peerName == null ? "" : peerName.trim();
        }
        public boolean isSender() { return ROLE_SENDER.equals(role); }
        public boolean isReceiver() { return ROLE_RECEIVER.equals(role); }
        public boolean sameEndpoint(CloudLink other) { return other != null && role.equals(other.role) && roomId.equals(other.roomId) && deviceId.equals(other.deviceId); }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try { o.put("relayUrl", relayUrl); o.put("roomId", roomId); o.put("deviceId", deviceId); o.put("token", token); o.put("peerDeviceId", peerDeviceId); o.put("peerPublicKey", peerPublicKey); o.put("privateKey", privateKey); o.put("role", role); o.put("peerName", peerName); } catch (Exception ignored) { }
            return o;
        }
        public static CloudLink fromJson(JSONObject o) {
            if (o == null) return null;
            return new CloudLink(o.optString("relayUrl", ""), o.optString("roomId", ""), o.optString("deviceId", ""),
                    o.optString("token", ""), o.optString("peerDeviceId", ""), o.optString("peerPublicKey", ""),
                    o.optString("privateKey", ""), o.optString("role", ROLE_SENDER), o.optString("peerName", ""));
        }
    }

    /** Compatibility facade for code compiled against the 0.4.0 single Config. */
    public static final class Config {
        public final String relayUrl;
        public final List<CloudLink> links;
        public final String roomId, deviceId, token, peerDeviceId, peerPublicKey, privateKey;
        public Config(String relayUrl, List<CloudLink> links) {
            this.relayUrl = relayUrl == null ? "" : relayUrl.trim();
            this.links = Collections.unmodifiableList(new ArrayList<>(links == null ? Collections.emptyList() : links));
            CloudLink first = this.links.isEmpty() ? null : this.links.get(0);
            this.roomId = first == null ? "" : first.roomId; this.deviceId = first == null ? "" : first.deviceId;
            this.token = first == null ? "" : first.token; this.peerDeviceId = first == null ? "" : first.peerDeviceId;
            this.peerPublicKey = first == null ? "" : first.peerPublicKey; this.privateKey = first == null ? "" : first.privateKey;
        }
        public Config(String relayUrl, String roomId, String deviceId, String token, String peerDeviceId, String peerPublicKey, String privateKey) {
            this(relayUrl, Collections.singletonList(new CloudLink(relayUrl, roomId, deviceId, token, peerDeviceId, peerPublicKey, privateKey, CloudLink.ROLE_SENDER)));
        }
    }
}
