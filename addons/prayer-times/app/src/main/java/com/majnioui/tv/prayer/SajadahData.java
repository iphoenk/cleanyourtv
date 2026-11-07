package com.majnioui.tv.prayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.Calendar;

/**
 * Read-only Indonesian official timetable relay. No API key, GPS or scraping.
 * This is Kemenag data served by sajadah.org, not a direct Kemenag integration.
 * Only Fajr, Sunrise, Dhuhr, Asr, Maghrib and Isha are consumed.
 */
final class SajadahData {
    private static final String ROOT = "https://sajadah.org/api/v1/";
    private static final String PREF = "sajadah_region";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_PROVINCE = "province";
    private static final String KEY_ZONE = "zone";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long CACHE_AGE_MS = 24L * 60 * 60 * 1000;
    static final String[] PRAYERS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};

    private SajadahData() {}

    static final class Region {
        final String id, name, province, zone;
        Region(String id, String name, String province, String zone) {
            this.id = id;
            this.name = name;
            this.province = province;
            this.zone = zone;
        }
    }

    static final class Day {
        final String isoDay, regionName;
        final TimeZone zone;
        final int[] minutes;
        final String[] times;
        final boolean cached;
        Day(String isoDay, String regionName, TimeZone zone,
            int[] minutes, String[] times, boolean cached) {
            this.isoDay = isoDay;
            this.regionName = regionName;
            this.zone = zone;
            this.minutes = minutes;
            this.times = times;
            this.cached = cached;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static Region selected(Context context) {
        SharedPreferences p = preferences(context);
        String id = p.getString(KEY_ID, "");
        if (!validId(id)) return null;
        String name = p.getString(KEY_NAME, "");
        String province = p.getString(KEY_PROVINCE, "");
        String zone = p.getString(KEY_ZONE, "");
        if (name.isEmpty() || !validZone(zone)) return null;
        return new Region(id, name, province, zone);
    }


    /**
     * Compare both region and civil timezone. A background fetch must not
     * schedule or cancel alarms for a selection made after it began.
     */
    static boolean sameRegion(Region expected, Region current) {
        return expected != null && current != null &&
                expected.id.equals(current.id) &&
                expected.zone.equals(current.zone);
    }

    static void select(Context context, Region region) {
        if (!validId(region.id) || !validZone(region.zone))
            throw new IllegalArgumentException("Invalid region");
        // Commit synchronously so BOOT_COMPLETED never reads a half-updated selection.
        preferences(context).edit()
                .putString(KEY_ID, region.id)
                .putString(KEY_NAME, region.name)
                .putString(KEY_PROVINCE, region.province)
                .putString(KEY_ZONE, region.zone)
                .commit();
    }

    static void clear(Context context) {
        preferences(context).edit().clear().commit();
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    private static boolean validZone(String id) {
        if (id == null || id.isEmpty()) return false;
        try {
            PrayerClock.resolve(id);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static List<Region> regions(Context context) throws Exception {
        File cache = new File(context.getFilesDir(), "sajadah-regions.json");
        String json;
        try {
            json = fetch(ROOT + "regions.json");
            List<Region> regions = parseRegions(json);
            if (regions.isEmpty()) throw new IOException("Empty Indonesian region index");
            store(cache, json);
            return regions;
        } catch (Exception network) {
            if (!cache.isFile()) throw network;
            return parseRegions(read(cache));
        }
    }

    static List<Region> parseRegions(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.getInt("schema") != 1) throw new IOException("Unsupported region schema");
        JSONArray entries = root.getJSONArray("regions");
        List<Region> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject r = entries.getJSONObject(i);
            if (!"ID".equals(r.optString("country")) ||
                !"kemenag".equals(r.optString("authority"))) continue;
            Region region = new Region(r.getString("id"), r.getString("name"),
                    r.getString("province"), r.getString("tz"));
            if (!validId(region.id) || !validZone(region.zone) ||
                region.name.trim().isEmpty() || region.province.trim().isEmpty() ||
                !ids.add(region.id)) throw new IOException("Invalid or duplicate region");
            list.add(region);
        }
        Collections.sort(list, Comparator.comparing((Region r) -> r.province)
                .thenComparing(r -> r.name));
        return list;
    }

    static Day today(Context context, Date now) throws Exception {
        Region region = selected(context);
        if (region == null) throw new IOException("Select a Kemenag region first");
        TimeZone zone = PrayerClock.resolve(region.zone);
        String date = PrayerClock.isoDay(now, zone);
        String yearText = date.substring(0, 4);
        int year = Integer.parseInt(yearText);
        File cache = new File(context.getFilesDir(),
                "sajadah-id-" + region.id + "-" + yearText + ".json");

        Day cached = null;
        if (cache.isFile()) {
            try {
                cached = parseDay(read(cache), region, date, true);
            } catch (Exception invalidCache) {
                // Never use damaged or mismatched offline schedules.
                cached = null;
            }
        }
        if (cached != null && System.currentTimeMillis() - cache.lastModified() < CACHE_AGE_MS)
            return cached;
        try {
            String json = fetch(ROOT + "times/id/" + region.id + "/" + yearText + ".json");
            Day day = parseDay(json, region, date, false);
            store(cache, json); // Cache only fully validated yearly tables.
            return day;
        } catch (Exception network) {
            if (cached != null) return cached;
            throw network;
        }
    }

    /**
     * Validate whole-year tables before using any row, including cached data.
     * Field positions are read from the JSON schema, not hard-coded.
     */
    static Day parseDay(String raw, Region selected, String requestedDate, boolean cached)
            throws Exception {
        JSONObject root = new JSONObject(raw);
        if (root.getInt("schema") != 1 || !"ID".equals(root.getString("country")) ||
                !root.getBoolean("official")) throw new IOException("Not an official ID table");
        JSONObject authority = root.getJSONObject("authority");
        if (!"kemenag".equals(authority.getString("id")))
            throw new IOException("Non-Kemenag authority");
        JSONObject region = root.getJSONObject("region");
        if (!selected.id.equals(region.getString("id")) ||
                !selected.zone.equals(region.getString("tz")))
            throw new IOException("Region or timezone does not match selection");

        int year = Integer.parseInt(requestedDate.substring(0, 4));
        if (root.getInt("year") != year) throw new IOException("Wrong timetable year");
        TimeZone zone = PrayerClock.resolve(selected.zone);
        JSONArray fields = root.getJSONArray("fields");
        int dateIndex = column(fields, "date");
        int[] columns = new int[PRAYERS.length];
        for (int i = 0; i < PRAYERS.length; i++) columns[i] = column(fields, PRAYERS[i]);

        JSONArray rows = root.getJSONArray("days");
        Calendar cursor = Calendar.getInstance(zone);
        cursor.clear();
        cursor.set(year, Calendar.JANUARY, 1);
        int expectedDays = cursor.getActualMaximum(Calendar.DAY_OF_YEAR);
        if (rows.length() != expectedDays) throw new IOException("Incomplete year timetable");
        Day selectedDay = null;
        for (int d = 0; d < rows.length(); d++) {
            JSONArray row = rows.getJSONArray(d);
            if (row.length() != fields.length()) throw new IOException("Field count mismatch");
            String expectedDate = PrayerClock.isoDay(cursor.getTime(), zone);
            String actualDate = row.getString(dateIndex);
            if (!expectedDate.equals(actualDate)) throw new IOException("Missing or out-of-order date");
            int[] minutes = new int[PRAYERS.length];
            String[] times = new String[PRAYERS.length];
            int previous = -1;
            for (int i = 0; i < PRAYERS.length; i++) {
                String time = row.getString(columns[i]);
                int minute = timeToMinutes(time);
                if (minute <= previous) throw new IOException("Non-increasing times");
                previous = minute;
                minutes[i] = minute;
                times[i] = time;
            }
            if (actualDate.equals(requestedDate))
                selectedDay = new Day(actualDate, selected.name, zone, minutes, times, cached);
            cursor.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (selectedDay == null) throw new IOException("Requested day is unavailable");
        return selectedDay;
    }

    private static int column(JSONArray fields, String name) throws Exception {
        for (int i = 0; i < fields.length(); i++) {
            if (name.equals(fields.getString(i))) return i;
        }
        throw new IOException("Required column missing: " + name);
    }

    static int timeToMinutes(String time) throws IOException {
        if (time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new IOException("Invalid time: " + time);
        return Integer.parseInt(time.substring(0, 2)) * 60 +
                Integer.parseInt(time.substring(3, 5));
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(b)) != -1;) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("Timetable too large");
                    output.write(b, 0, n);
                }
                return output.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(File file) throws IOException {
        if (file.length() > MAX_BYTES) throw new IOException("Cached timetable too large");
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int total = 0;
            for (int n; (n = in.read(b)) != -1;) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("Cached timetable too large");
                out.write(b, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static void store(File file, String json) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (IOException exception) {
            atomic.failWrite(out);
            throw exception;
        }
    }
}
