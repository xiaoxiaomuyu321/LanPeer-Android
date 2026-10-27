package cn.lanpeer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Stores device identity and address only. Rotating connection codes are never persisted. */
public final class KnownDevices {
    private static final String KEY = "devices";
    private final SharedPreferences preferences;

    public static final class Device {
        public final String host;
        public final int port;
        public final String fingerprint;
        public final long lastConnected;
        public final String name;

        Device(String host, int port, String fingerprint, long lastConnected, String name) {
            this.host = host; this.port = port; this.fingerprint = fingerprint;
            this.lastConnected = lastConnected; this.name = name;
        }

        public String address() { return host + ":" + port; }
        public String identityLabel() {
            return fingerprint.length() == 64 ? "已记录完整设备指纹" : "已记录手动设备校验码";
        }
    }

    public KnownDevices(Context context) {
        preferences = context.getSharedPreferences("known_devices", Context.MODE_PRIVATE);
    }

    public List<Device> list() {
        ArrayList<Device> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < array.length() && result.size() < 20; i++) {
                JSONObject item = array.getJSONObject(i);
                try {
                    Pairing checked = new Pairing(item.getString("host"), item.getInt("port"),
                            "00000000", item.getString("fingerprint"));
                    result.add(new Device(checked.host, checked.port, checked.fingerprint,
                            item.optLong("lastConnected"), item.optString("name", "")));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return result;
    }

    public void remember(Pairing pairing) {
        ArrayList<Device> devices = new ArrayList<>();
        String name = "";
        for (Device device : list()) if (device.host.equals(pairing.host) && device.port == pairing.port
                && device.fingerprint.equals(pairing.fingerprint)) name = device.name;
        devices.add(new Device(pairing.host, pairing.port, pairing.fingerprint, System.currentTimeMillis(), name));
        for (Device device : list()) {
            if (devices.size() >= 20) break;
            if (!(device.host.equals(pairing.host) && device.port == pairing.port
                    && device.fingerprint.equals(pairing.fingerprint))) devices.add(device);
        }
        write(devices);
    }

    public void forget(Device target) {
        ArrayList<Device> devices = new ArrayList<>();
        for (Device device : list()) {
            if (!(device.host.equals(target.host) && device.port == target.port
                    && device.fingerprint.equals(target.fingerprint))) devices.add(device);
        }
        write(devices);
    }

    public void rename(Device target, String name) {
        String safe = name.trim();
        if (safe.length() > 32) safe = safe.substring(0, 32);
        ArrayList<Device> devices = new ArrayList<>();
        for (Device device : list()) {
            if (device.host.equals(target.host) && device.port == target.port
                    && device.fingerprint.equals(target.fingerprint))
                devices.add(new Device(device.host, device.port, device.fingerprint, device.lastConnected, safe));
            else devices.add(device);
        }
        write(devices);
    }

    private void write(List<Device> devices) {
        JSONArray array = new JSONArray();
        for (Device device : devices) {
            JSONObject item = new JSONObject();
            try {
                item.put("host", device.host);
                item.put("port", device.port);
                item.put("fingerprint", device.fingerprint);
                item.put("lastConnected", device.lastConnected);
                item.put("name", device.name);
                array.put(item);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString(KEY, array.toString()).apply();
    }
}
