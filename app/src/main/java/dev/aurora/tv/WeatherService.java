package dev.aurora.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keyless Open-Meteo weather. All callbacks run on the main thread. */
public final class WeatherService implements AutoCloseable {
    public interface Callback { void onResult(Weather weather, String error); }
    public interface SearchCallback { void onResult(List<Location> locations, String error); }

    public static final class Location {
        public final String name, label;
        public final double latitude, longitude;

        public Location(String name, String label, double latitude, double longitude) {
            if (name == null || label == null || !Double.isFinite(latitude)
                    || !Double.isFinite(longitude) || Math.abs(latitude) > 90
                    || Math.abs(longitude) > 180) throw new IllegalArgumentException("Invalid location");
            this.name = name;
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("name", name).put("label", label)
                    .put("latitude", latitude).put("longitude", longitude);
        }

        static Location fromJson(JSONObject o) throws Exception {
            return new Location(o.getString("name"), o.getString("label"),
                    o.getDouble("latitude"), o.getDouble("longitude"));
        }

        @Override public String toString() { return label; }
    }

    public static final class Weather {
        public final String city, date, locationSource;
        public final double temperature, high, low;
        public final int code;
        public final long updatedAt;
        public final boolean offline;

        private Weather(String city, String date, double temperature, double high,
                        double low, int code, long updatedAt, boolean offline, String locationSource) {
            this.city = city;
            this.date = date;
            this.temperature = temperature;
            this.high = high;
            this.low = low;
            this.code = code;
            this.updatedAt = updatedAt;
            this.offline = offline;
            this.locationSource = locationSource;
        }

        public String description() { return describe(code); }
        public String temperatureText() { return Math.round(temperature) + "°"; }
        public String rangeText() { return "最高 " + Math.round(high) + "°  最低 " + Math.round(low) + "°"; }
        public String updatedText() {
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(updatedAt));
            return (offline ? "上次缓存 · " : "更新于 ") + time;
        }

        Weather asOffline() {
            return new Weather(city, date, temperature, high, low, code, updatedAt, true, locationSource);
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("city", city).put("date", date)
                    .put("temperature", temperature).put("high", high).put("low", low)
                    .put("code", code).put("updatedAt", updatedAt).put("locationSource", locationSource);
        }

        static Weather fromJson(JSONObject o) throws Exception {
            return new Weather(o.getString("city"), o.getString("date"),
                    o.getDouble("temperature"), o.getDouble("high"), o.getDouble("low"),
                    o.getInt("code"), o.getLong("updatedAt"), true, o.getString("locationSource"));
        }
    }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private volatile boolean closed;
    private int generation, searchGeneration;
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private static final long LOCATION_TTL_MS = 30 * 60 * 1000L;
    private final ConnectivityManager connectivity;
    private boolean automatic;
    private Network locatedNetwork;
    private long locatedAt;
    private Location location;
    private Weather cached;

    public WeatherService(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
        connectivity = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        // Earlier versions had no explicit mode: migrate their fixed city to automatic IP lookup.
        automatic = !"manual".equals(prefs.getString("mode", "automatic"));
        if (!automatic) {
            try { location = Location.fromJson(new JSONObject(prefs.getString("location", ""))); }
            catch (Exception ignored) { automatic = true; }
        }
        try { cached = Weather.fromJson(new JSONObject(prefs.getString(cacheKey(), ""))); }
        catch (Exception ignored) { /* Old fixed-city caches must not masquerade as IP results. */ }
    }

    public synchronized Location getLocation() { return location; }
    public synchronized Weather getCached() { return cached; }
    public synchronized boolean isAutomatic() { return automatic; }
    private String cacheKey() { return automatic ? "ip_cache" : "manual_cache"; }

    /** Changes mode explicitly; call refresh afterward. */
    public synchronized void useAutomaticLocation() {
        if (closed) return;
        automatic = true;
        location = null;
        locatedAt = 0;
        cached = null;
        generation++;
        prefs.edit().putString("mode", "automatic").remove("ip_cache").apply();
    }

    /** Saves a user-selected city; call refresh afterward. Old city's cache is removed. */
    public synchronized void setLocation(Location selected) {
        if (closed) return;
        if (selected == null) throw new IllegalArgumentException("Select a location");
        try {
            prefs.edit().putString("mode", "manual").putString("location", selected.json().toString())
                    .remove("manual_cache").apply();
            automatic = false;
            location = selected;
            cached = null;
            generation++;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid location", e); }
    }

    public synchronized void searchCity(String query, SearchCallback callback) {
        if (closed) return;
        final int searchId = ++searchGeneration;
        String term = query == null ? "" : query.trim();
        if (term.length() < 2) {
            deliverSearch(searchId, callback, Collections.emptyList(), "请输入至少两个字；也可用城市拼音");
            return;
        }
        io.execute(() -> {
            synchronized (this) { if (closed || searchGeneration != searchId) return; }
            try {
                JSONObject response = request("https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(term, "UTF-8") + "&count=8&language=zh&format=json");
                List<Location> matches = new ArrayList<>();
                JSONArray results = response.optJSONArray("results");
                if (results != null) for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.getJSONObject(i);
                    String name = o.getString("name");
                    String admin = o.optString("admin1", "");
                    String country = o.optString("country", "");
                    String label = name + (admin.isEmpty() || admin.equals(name) ? "" : " · " + admin)
                            + (country.isEmpty() ? "" : " · " + country);
                    matches.add(new Location(name, label, o.getDouble("latitude"), o.getDouble("longitude")));
                }
                deliverSearch(searchId, callback, Collections.unmodifiableList(matches),
                        matches.isEmpty() ? "未找到城市，请试试拼音或英文名" : null);
            } catch (Exception e) {
                deliverSearch(searchId, callback, Collections.emptyList(), "城市搜索暂不可用，请检查网络后重试");
            }
        });
    }

    public void refresh(Callback callback) { refresh(false, callback); }

    /** Force bypasses the 30-minute IP location cache, e.g. after a network change. */
    public synchronized void refresh(boolean force, Callback callback) {
        if (closed) return;
        final int requestGeneration = ++generation;
        final boolean byIp = automatic;
        final Network network = currentNetwork();
        final boolean locate = byIp && (force || location == null
                || SystemClock.elapsedRealtime() - locatedAt >= LOCATION_TTL_MS
                || !java.util.Objects.equals(network, locatedNetwork));
        final Location selected = location;
        io.execute(() -> {
            synchronized (this) { if (closed || generation != requestGeneration) return; }
            boolean locating = locate;
            try {
                Location target = locate ? locateIp() : selected;
                if (target == null) throw new java.io.IOException("Missing location");
                synchronized (this) {
                    if (closed || generation != requestGeneration) return;
                    if (locate) {
                        // After a cold start only the cached city is available. Keep its weather
                        // if IP lookup confirms that city, so forecast failures still fall back.
                        boolean samePlace = location == null
                                ? cached != null && cached.city.equals(target.name)
                                : location.name.equals(target.name)
                                    && location.latitude == target.latitude
                                    && location.longitude == target.longitude;
                        if (!samePlace) {
                            cached = null;
                            prefs.edit().remove("ip_cache").apply();
                        }
                        location = target;
                        locatedAt = SystemClock.elapsedRealtime();
                        locatedNetwork = network;
                    }
                }
                locating = false;
                JSONObject response = request("https://api.open-meteo.com/v1/forecast?latitude="
                        + target.latitude + "&longitude=" + target.longitude
                        + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1&temperature_unit=celsius");
                JSONObject current = response.getJSONObject("current");
                JSONObject daily = response.getJSONObject("daily");
                Weather weather = new Weather(target.name, daily.getJSONArray("time").getString(0),
                        current.getDouble("temperature_2m"),
                        daily.getJSONArray("temperature_2m_max").getDouble(0),
                        daily.getJSONArray("temperature_2m_min").getDouble(0),
                        current.getInt("weather_code"), System.currentTimeMillis(), false,
                        byIp ? "IP 近似定位" : "手动城市");
                synchronized (this) {
                    if (closed || generation != requestGeneration) return;
                    prefs.edit().putString(cacheKey(), weather.json().toString()).apply();
                    cached = weather;
                }
                deliver(requestGeneration, callback, weather, null);
            } catch (Exception e) {
                final Weather fallback;
                synchronized (this) {
                    if (closed || generation != requestGeneration) return;
                    // Do not reuse a location after a failed forced lookup.
                    if (locating) { locatedAt = 0; location = null; }
                    fallback = cached == null ? null : cached.asOffline();
                    cached = fallback;
                }
                deliver(requestGeneration, callback, fallback, locating
                        ? "IP 定位失败，请检查网络或手动选择城市" : "天气更新失败，请检查网络");
            }
        });
    }

    private Network currentNetwork() {
        try { return connectivity == null ? null : connectivity.getActiveNetwork(); }
        catch (SecurityException ignored) { return null; }
    }

    private Location locateIp() throws Exception {
        JSONObject result = request("https://ipwho.is/?lang=zh-CN&fields=success,city,region,latitude,longitude");
        String city = result.optString("city", "").trim();
        if (!result.optBoolean("success") || city.isEmpty())
            throw new java.io.IOException("IP location unavailable");
        return new Location(city, city + " · IP 近似定位",
                result.getDouble("latitude"), result.getDouble("longitude"));
    }

    private void deliver(int requestGeneration, Callback callback, Weather weather, String error) {
        post(() -> {
            synchronized (this) { if (generation != requestGeneration) return; }
            callback.onResult(weather, error);
        });
    }

    private void deliverSearch(int searchId, SearchCallback callback, List<Location> locations, String error) {
        post(() -> {
            synchronized (this) { if (searchGeneration != searchId) return; }
            callback.onResult(locations, error);
        });
    }

    private void post(Runnable action) {
        main.post(() -> { if (!closed) action.run(); });
    }

    private JSONObject request(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        synchronized (this) {
            if (closed || Thread.currentThread().isInterrupted()) {
                connection.disconnect();
                throw new java.io.InterruptedIOException();
            }
            connections.add(connection);
        }
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 512 * 1024) throw new java.io.IOException("Response too large");
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            synchronized (this) { connections.remove(connection); }
            connection.disconnect();
        }
    }

    public static String describe(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: return "大部晴朗";
            case 2: return "多云";
            case 3: return "阴";
            case 45: case 48: return "雾";
            case 51: case 53: case 55: return "毛毛雨";
            case 56: case 57: return "冻毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66: case 67: return "冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "米雪";
            case 80: case 81: case 82: return "阵雨";
            case 85: case 86: return "阵雪";
            case 95: return "雷雨";
            case 96: case 99: return "雷雨伴冰雹";
            default: return "天气未知";
        }
    }

    @Override public void close() {
        final List<HttpURLConnection> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            io.shutdownNow();
            active = new ArrayList<>(connections);
            connections.clear();
            main.removeCallbacksAndMessages(null);
        }
        // Thread interruption alone does not cancel HttpURLConnection reads.
        for (HttpURLConnection connection : active) connection.disconnect();
    }
}
