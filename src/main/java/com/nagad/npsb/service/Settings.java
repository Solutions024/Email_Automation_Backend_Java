package com.nagad.npsb.service;

import com.nagad.npsb.config.NpsbProperties;
import com.nagad.npsb.entity.AppSetting;
import com.nagad.npsb.repository.AppSettingRepository;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.util.PyJson;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Email settings that admins change in the app (Email Configuration page).
 *
 * Each setting starts with its .env default (NpsbProperties) and is stored in the app_settings table once an
 * admin saves it. Values are cached for a few seconds, so reading them is cheap.
 */
@Service
public class Settings {

    /** The .env defaults, by setting name (same keys as current()). */
    private final Map<String, Object> defaults;

    private static final long CACHE_NANOS = 10_000_000_000L;

    private final AppSettingRepository appSettings;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    // guarded by this
    private Map<String, Object> cache = new LinkedHashMap<>();
    private long cacheAt = 0;

    public Settings(NpsbProperties properties, AppSettingRepository appSettings, TransactionTemplate transactionTemplate,
                    EntityManager entityManager) {
        this.appSettings = appSettings;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        Map<String, Object> values = new LinkedHashMap<>();
        // Sent emails with no reply and nothing sent for this many days are "Follow-up due"
        values.put("followup_due_days", properties.getFollowupDueDays());
        // Send the follow-up of a due email automatically (no admin needed)
        values.put("auto_followup_enabled", properties.isAutoFollowupEnabled());
        // Stop sending automatic follow-ups once an email has had this many follow-ups in total
        values.put("auto_followup_max", properties.getAutoFollowupMax());
        // Copied on every new email
        values.put("cc_emails", Collections.unmodifiableList(properties.ccEmailList()));
        defaults = Collections.unmodifiableMap(values);
    }

    /** The .env defaults (Settings.DEFAULTS in the old code): followup_due_days, auto_followup_enabled, ... */
    public Map<String, Object> defaults() {
        return defaults;
    }

    private Map<String, Object> load() {
        Map<String, Object> values = new LinkedHashMap<>(defaults);
        List<String> keys = new ArrayList<>(defaults.keySet());
        for (AppSetting row : appSettings.findAllById(keys)) {
            values.put(row.getKey(), PyJson.loads(row.getValue()));
        }
        return values;
    }

    /** All settings: the saved values, and the .env default for the others. */
    public synchronized Map<String, Object> current() {
        if (!cache.isEmpty() && System.nanoTime() - cacheAt < CACHE_NANOS) return new LinkedHashMap<>(cache);
        cache = load();
        cacheAt = System.nanoTime();
        return new LinkedHashMap<>(cache);
    }

    public Object get(String name) {
        return current().get(name);
    }

    public int getInt(String name) {
        return ((Number) get(name)).intValue();
    }

    public boolean getBool(String name) {
        return Boolean.TRUE.equals(get(name));
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String name) {
        return new ArrayList<>((List<String>) get(name));
    }

    /** {"by", "at"} of the newest change, or null. */
    public Map<String, Object> lastChange() {
        // WHERE key IN (...) ORDER BY updated_at DESC LIMIT 1 (rows saved together have the same by / at)
        AppSetting newest = null;
        for (AppSetting row : appSettings.findAllById(new ArrayList<>(defaults.keySet()))) {
            if (newest == null || (row.getUpdatedAt() != null
                    && (newest.getUpdatedAt() == null || row.getUpdatedAt().isAfter(newest.getUpdatedAt())))) {
                newest = row;
            }
        }
        return newest == null ? null : Py.map("by", newest.getUpdatedBy(), "at", Py.iso(newest.getUpdatedAt()));
    }

    /** Store the given settings (only known keys) and return all of them. */
    public Map<String, Object> save(Map<String, Object> values, String username) {
        var now = Py.now();
        transactionTemplate.executeWithoutResult(status -> {
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (!defaults.containsKey(e.getKey())) continue;
                AppSetting row = appSettings.findById(e.getKey()).orElse(null);
                boolean added = row == null;
                if (added) {
                    row = new AppSetting();
                    row.setKey(e.getKey());
                }
                row.setValue(PyJson.dumps(e.getValue()));
                row.setUpdatedBy(username);
                row.setUpdatedAt(now);
                if (added) appSettings.save(row);
            }
            entityManager.flush();  // written (and committed, unless the caller has a transaction) before reading back
        });
        synchronized (this) {
            cacheAt = 0;
            cache = new LinkedHashMap<>();
        }
        return current();
    }
}
