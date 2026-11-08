package com.nagad.npsb.service;

import com.nagad.npsb.Config;
import com.nagad.npsb.Py;
import com.nagad.npsb.PyJson;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.AppSetting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Email settings that admins change in the app (Email Configuration page).
 *
 * Each setting starts with its .env default (Config) and is stored in the app_settings table once an
 * admin saves it. Values are cached for a few seconds, so reading them is cheap.
 */
public final class Settings {
    private Settings() {}

    public static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();
    static {
        // Sent emails with no reply and nothing sent for this many days are "Follow-up due"
        DEFAULTS.put("followup_due_days", Config.FOLLOWUP_DUE_DAYS);
        // Send the follow-up of a due email automatically (no admin needed)
        DEFAULTS.put("auto_followup_enabled", Config.AUTO_FOLLOWUP_ENABLED);
        // Stop sending automatic follow-ups once an email has had this many follow-ups in total
        DEFAULTS.put("auto_followup_max", Config.AUTO_FOLLOWUP_MAX);
        // Copied on every new email
        DEFAULTS.put("cc_emails", Config.CC_EMAILS);
    }

    private static Map<String, Object> cache = new LinkedHashMap<>();
    private static long cacheAt = 0;
    private static final long CACHE_NANOS = 10_000_000_000L;

    private static Map<String, Object> load(Db db) {
        Map<String, Object> values = new LinkedHashMap<>(DEFAULTS);
        List<String> keys = new ArrayList<>(DEFAULTS.keySet());
        for (AppSetting row : db.all(AppSetting.class, "WHERE t.`key` IN " + Db.in(keys), keys.toArray())) {
            values.put(row.key, PyJson.loads(row.value));
        }
        return values;
    }

    /** All settings: the saved values, and the .env default for the others. */
    public static synchronized Map<String, Object> current(Db db) {
        if (!cache.isEmpty() && System.nanoTime() - cacheAt < CACHE_NANOS) return new LinkedHashMap<>(cache);
        if (db != null) {
            cache = load(db);
        } else {
            try (Db own = Db.open()) {
                cache = load(own);
            }
        }
        cacheAt = System.nanoTime();
        return new LinkedHashMap<>(cache);
    }

    public static Map<String, Object> current() {
        return current(null);
    }

    public static Object get(String name) {
        return current().get(name);
    }

    public static int getInt(String name) {
        return ((Number) get(name)).intValue();
    }

    public static boolean getBool(String name) {
        return Boolean.TRUE.equals(get(name));
    }

    @SuppressWarnings("unchecked")
    public static List<String> getList(String name) {
        return new ArrayList<>((List<String>) get(name));
    }

    /** {"by", "at"} of the newest change, or null. */
    public static Map<String, Object> lastChange(Db db) {
        List<String> keys = new ArrayList<>(DEFAULTS.keySet());
        AppSetting row = db.first(AppSetting.class, "WHERE t.`key` IN " + Db.in(keys) + " ORDER BY t.updated_at DESC", keys.toArray());
        return row == null ? null : Py.map("by", row.updatedBy, "at", Py.iso(row.updatedAt));
    }

    /** Store the given settings (only known keys) and return all of them. */
    public static Map<String, Object> save(Db db, Map<String, Object> values, String username) {
        var now = Py.now();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!DEFAULTS.containsKey(e.getKey())) continue;
            AppSetting row = db.get(AppSetting.class, e.getKey());
            if (row == null) {
                row = new AppSetting();
                row.key = e.getKey();
                db.add(row);
            }
            row.value = PyJson.dumps(e.getValue());
            row.updatedBy = username;
            row.updatedAt = now;
        }
        db.commit();
        synchronized (Settings.class) {
            cacheAt = 0;
            cache = new LinkedHashMap<>();
        }
        return current(db);
    }
}
