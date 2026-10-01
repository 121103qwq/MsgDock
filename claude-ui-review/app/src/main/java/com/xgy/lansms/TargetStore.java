package com.xgy.lansms;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class TargetStore {
    private static final String PREFS = "xgy_lan_sms";
    private static final String KEY_TARGETS = "targets";
    private TargetStore() {}

    public static class Target {
        public String name;
        public String host;
        public int port;
        public String code;
        public Target(String name, String host, int port, String code) {
            this.name = name; this.host = host; this.port = port; this.code = code;
        }
        public String label() { return name + "  " + host + ":" + port; }
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Target> load(Context c) {
        List<Target> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_TARGETS, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Target(o.optString("name", "Device"), o.getString("host"),
                        o.optInt("port", 58123), o.optString("code", "")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(Context c, List<Target> targets) {
        JSONArray a = new JSONArray();
        try {
            for (Target t : targets) {
                JSONObject o = new JSONObject();
                o.put("name", t.name); o.put("host", t.host); o.put("port", t.port); o.put("code", t.code);
                a.put(o);
            }
        } catch (Exception ignored) {}
        prefs(c).edit().putString(KEY_TARGETS, a.toString()).apply();
    }

    public static String ensurePairCode(Context c) {
        SharedPreferences p = prefs(c);
        String code = p.getString("pair_code", null);
        if (code == null || code.length() != 6) {
            int n = 100000 + new java.security.SecureRandom().nextInt(900000);
            code = String.valueOf(n);
            p.edit().putString("pair_code", code).apply();
        }
        return code;
    }
}
