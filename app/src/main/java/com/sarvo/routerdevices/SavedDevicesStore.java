package com.sarvo.routerdevices;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists router device records; devices not seen on later scans remain as inactive. */
final class SavedDevicesStore {
    private static final String PREFS = "saved_router_devices";
    private static final String DEVICES = "devices_json";

    static synchronized List<RouterDevice> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(DEVICES, "[]");
        List<RouterDevice> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                result.add(new RouterDevice(
                        item.optInt("oid", -1),
                        item.optString("hostname", ""),
                        item.optString("ip", ""),
                        item.optString("mac", ""),
                        item.optBoolean("active", false),
                        item.optString("interface", ""),
                        item.optBoolean("blocked", false)
                ));
            }
        } catch (Exception ignored) {
            // Corrupt or older local data should not prevent the app from opening.
        }
        sort(result);
        return result;
    }

    /**
     * Mark records inactive, then refresh every record returned by the router.
     * Existing devices not returned by a later router scan remain saved as inactive.
     */
    static synchronized List<RouterDevice> merge(Context context, List<RouterDevice> latest) {
        Map<String, RouterDevice> records = new LinkedHashMap<>();
        for (RouterDevice old : load(context)) {
            records.put(old.identityKey(), old.withActive(false));
        }
        if (latest != null) {
            for (RouterDevice device : latest) {
                records.put(device.identityKey(), device);
            }
        }

        List<RouterDevice> result = new ArrayList<>(records.values());
        sort(result);
        save(context, result);
        return result;
    }

    private static void save(Context context, List<RouterDevice> devices) {
        JSONArray array = new JSONArray();
        for (RouterDevice device : devices) {
            JSONObject item = new JSONObject();
            try {
                item.put("oid", device.oid);
                item.put("hostname", device.hostname);
                item.put("ip", device.ip);
                item.put("mac", device.mac);
                item.put("active", device.active);
                item.put("interface", device.interfaceType);
                item.put("blocked", device.blocked);
                array.put(item);
            } catch (Exception ignored) {
                // Ignore a malformed record but preserve the rest of the list.
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(DEVICES, array.toString()).apply();
    }

    /** Update router-confirmed access state even when the device is currently offline. */
    static synchronized List<RouterDevice> updateBlocked(Context context, String mac, boolean blocked) {
        String target = mac == null ? "" : mac.trim().replace('-', ':').toLowerCase(java.util.Locale.ROOT);
        List<RouterDevice> devices = load(context);
        for (int i = 0; i < devices.size(); i++) {
            RouterDevice device = devices.get(i);
            String existing = device.mac.trim().replace('-', ':').toLowerCase(java.util.Locale.ROOT);
            if (!target.isEmpty() && target.equals(existing)) {
                devices.set(i, device.withBlocked(blocked));
            }
        }
        sort(devices);
        save(context, devices);
        return devices;
    }

    private static void sort(List<RouterDevice> devices) {
        Collections.sort(devices, new Comparator<RouterDevice>() {
            @Override public int compare(RouterDevice a, RouterDevice b) {
                if (a.active != b.active) return a.active ? -1 : 1;
                return a.displayName().compareToIgnoreCase(b.displayName());
            }
        });
    }

    private SavedDevicesStore() {}
}
