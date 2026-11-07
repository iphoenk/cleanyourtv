package com.majnioui.tv.prayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Today's prayer times for one city from Aladhan (free, no account or key; only the coordinates and
 * the date are sent), with the calculation method in res/values/config.xml. The next prayer is highlighted
 * with a countdown.
 */
public class MainActivity extends Activity {
    private static final String TAG = "PrayerTimes";
    private static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final int[] NAME_RES = {
            R.string.prayer_fajr,
            R.string.prayer_sunrise,
            R.string.prayer_dhuhr,
            R.string.prayer_asr,
            R.string.prayer_maghrib,
            R.string.prayer_isha
    };
    private static final String[] ARABIC = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] ICONS = {R.drawable.ic_fajr, R.drawable.ic_sunrise, R.drawable.ic_dhuhr,
            R.drawable.ic_asr, R.drawable.ic_maghrib, R.drawable.ic_isha};
    private static final long MINUTE_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface font;
    private int accent;
    private TextView dates, next, status;
    private LinearLayout rows;
    private TextView regionLabel;
    private boolean sajadahCache;
    private int selectionGeneration;
    /** Today's times as minutes after midnight, in NAMES order; null until loaded. */
    private int[] minutes;
    private String[] times;
    /** The calculation method's name as Aladhan gives it, for the status line. */
    private String methodName = "";
    /** The day the times are for (yyyy-MM-dd), to reload after midnight. */
    private String loadedFor;
    private boolean loading;
    private TimeZone cityZone;
    private boolean invalidCityZone;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!today().equals(loadedFor)) load();
            else render();
            main.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            SajadahData.Region chosen = SajadahData.selected(this);
            cityZone = PrayerClock.resolve(chosen == null ? getString(R.string.time_zone) : chosen.zone);
        } catch (IllegalArgumentException e) {
            invalidCityZone = true;
            Log.e(TAG, "Invalid prayer timezone configuration", e);
        }
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(accent & 0x00FFFFFF) | 0x55000000, 0xFF050607, 0xFF050607}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(48), dp(80), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        regionLabel = text(18, 400, 0xB3FFFFFF);
        regionLabel.setLetterSpacing(0.1f);
        regionLabel.setFocusable(true);
        regionLabel.setClickable(true);
        regionLabel.setOnClickListener(v -> showSourceMenu());
        updateRegionLabel();
        col.addView(regionLabel);

        next = text(52, 300, 0xFFFFFFFF);
        next.setPadding(0, dp(10), 0, dp(4));
        col.addView(next);
        dates = text(20, 400, 0xB3FFFFFF);
        col.addView(dates);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rlp.setMargins(0, dp(36), 0, 0);
        col.addView(rows, rlp);

        status = text(16, 400, 0x80FFFFFF);
        status.setPadding(0, dp(18), 0, 0);
        status.setText(getString(R.string.loading));
        col.addView(status);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (invalidCityZone) {
            status.setText(getString(R.string.invalid_time_zone, getString(R.string.time_zone)));
            return;
        }
        load();
        main.postDelayed(tick, MINUTE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private String today() {
        return PrayerClock.isoDay(new Date(), cityZone);
    }

    private void load() {
        if (loading) return;
        loading = true;
        if (SajadahData.selected(this) != null) {
            loadSajadah();
            return;
        }
        sajadahCache = false;
        final Date now = new Date();
        final String day = PrayerClock.isoDay(now, cityZone);
        final int generation = selectionGeneration;
        String url = "https://api.aladhan.com/v1/timings/"
                + PrayerClock.apiDay(now, cityZone)
                + "?latitude=" + getString(R.string.latitude) + "&longitude=" + getString(R.string.longitude)
                + "&method=" + getString(R.string.method) + "&school=" + getString(R.string.school);
        new Thread(() -> {
            JSONObject json = null;
            try {
                json = new JSONObject(fetch(url));
            } catch (Exception e) {
                Log.w(TAG, "fetch: " + e);
            }
            final JSONObject result = json;
            main.post(() -> {
                if (generation != selectionGeneration || isFinishing() || isDestroyed()) return;
                loading = false;
                if (result == null) {
                    status.setText(getString(R.string.offline));
                    return;
                }
                try {
                    parse(result.getJSONObject("data"));
                    loadedFor = day;
                    if (getResources().getBoolean(R.bool.reminders)) {
                        PrayerScheduler.saveTimingsAndSchedule(MainActivity.this, day, NAMES, times, minutes, getString(R.string.place));
                    } else {
                        PrayerScheduler.cancel(MainActivity.this);
                    }
                    render();
                } catch (Exception e) {
                    Log.w(TAG, "parse: " + e);
                    status.setText(getString(R.string.unexpected_error));
                }
            });
        }).start();
    }

    private void updateRegionLabel() {
        SajadahData.Region region = SajadahData.selected(this);
        String value = region == null
                ? getString(R.string.place) + " · " + getString(R.string.pick_region)
                : region.name + " (" + region.province + ") · " + getString(R.string.change_region);
        regionLabel.setText(value.toUpperCase(Locale.getDefault()));
    }

    private void showSourceMenu() {
        new AlertDialog.Builder(this).setTitle(R.string.choose_source)
                .setItems(new String[]{getString(R.string.aladhan_source),
                                getString(R.string.sajadah_source)},
                        (dialog, which) -> {
                            if (which == 0) {
                                SajadahData.clear(this);
                                PrayerScheduler.cancel(this);
                                resetAfterRegionChange();
                            } else {
                                chooseSajadahRegion();
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseSajadahRegion() {
        status.setText(getString(R.string.loading_regions));
        new Thread(() -> {
            List<SajadahData.Region> regions = null;
            try {
                regions = SajadahData.regions(getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "Region list unavailable", e);
            }
            final List<SajadahData.Region> results = regions;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (results == null || results.isEmpty()) {
                    status.setText(getString(R.string.regions_unavailable));
                    return;
                }
                Set<String> set = new LinkedHashSet<>();
                for (SajadahData.Region region : results) set.add(region.province);
                List<String> provinces = new ArrayList<>(set);
                new AlertDialog.Builder(this).setTitle(R.string.choose_province)
                        .setItems(provinces.toArray(new String[0]), (d, which) ->
                                chooseCity(results, provinces.get(which)))
                        .setNegativeButton(android.R.string.cancel, null).show();
            });
        }).start();
    }

    private void chooseCity(List<SajadahData.Region> regions, String province) {
        List<SajadahData.Region> cities = new ArrayList<>();
        for (SajadahData.Region region : regions) {
            if (province.equals(region.province)) cities.add(region);
        }
        String[] names = new String[cities.size()];
        for (int i = 0; i < cities.size(); i++) names[i] = cities.get(i).name;
        new AlertDialog.Builder(this).setTitle(province)
                .setItems(names, (dialog, which) -> {
                    SajadahData.select(this, cities.get(which));
                    PrayerScheduler.cancel(this);
                    resetAfterRegionChange();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void resetAfterRegionChange() {
        selectionGeneration++;
        loading = false;
        try {
            SajadahData.Region chosen = SajadahData.selected(this);
            cityZone = PrayerClock.resolve(chosen == null ?
                    getString(R.string.time_zone) : chosen.zone);
            invalidCityZone = false;
        } catch (IllegalArgumentException e) {
            invalidCityZone = true;
            status.setText(getString(R.string.invalid_time_zone, getString(R.string.time_zone)));
            return;
        }
        loadedFor = null;
        minutes = null;
        times = null;
        rows.removeAllViews();
        next.setText(getString(R.string.loading));
        status.setText(getString(R.string.loading));
        updateRegionLabel();
        load();
    }

    private void loadSajadah() {
        final int generation = selectionGeneration;
        new Thread(() -> {
            SajadahData.Day day = null;
            try {
                day = SajadahData.today(getApplicationContext(), new Date());
            } catch (Exception e) {
                Log.w(TAG, "Kemenag timetable unavailable", e);
            }
            final SajadahData.Day result = day;
            main.post(() -> {
                if (generation != selectionGeneration || isFinishing() || isDestroyed()) return;
                loading = false;
                if (result == null) {
                    minutes = null;
                    times = null;
                    loadedFor = null;
                    rows.removeAllViews();
                    next.setText(getString(R.string.schedule_unavailable));
                    status.setText(getString(R.string.regions_offline));
                    if (getResources().getBoolean(R.bool.reminders))
                        PrayerScheduler.fetchTimingsAndSchedule(this);
                    else
                        PrayerScheduler.cancel(this);
                    return;
                }
                cityZone = result.zone;
                minutes = result.minutes;
                times = result.times;
                loadedFor = result.isoDay;
                sajadahCache = result.cached;
                SimpleDateFormat label =
                        new SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault());
                label.setTimeZone(cityZone);
                dates.setText(label.format(new Date()));
                if (getResources().getBoolean(R.bool.reminders)) {
                    PrayerScheduler.saveTimingsAndSchedule(this, result.isoDay,
                            NAMES, times, minutes, result.regionName);
                } else {
                    PrayerScheduler.cancel(this);
                }
                render();
            });
        }).start();
    }

    private void parse(JSONObject data) throws Exception {
        JSONObject t = data.getJSONObject("timings");
        int[] m = new int[NAMES.length];
        String[] s = new String[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            String v = t.getString(NAMES[i]).trim();
            int sp = v.indexOf(' ');
            if (sp > 0) v = v.substring(0, sp);   // "05:12 (+01)" style suffixes
            s[i] = v;
            m[i] = Integer.parseInt(v.substring(0, 2)) * 60 + Integer.parseInt(v.substring(3, 5));
        }
        minutes = m;
        times = s;
        JSONObject meta = data.optJSONObject("meta");
        JSONObject method = meta != null ? meta.optJSONObject("method") : null;
        methodName = method != null ? method.optString("name", "") : "";
        JSONObject date = data.getJSONObject("date");
        JSONObject hijri = date.getJSONObject("hijri");
        SimpleDateFormat localDate = new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH);
        localDate.setTimeZone(cityZone);
        dates.setText(localDate.format(new Date()) + "  ·  "
                + hijri.getString("day") + " " + hijri.getJSONObject("month").getString("en") + " "
                + hijri.getString("year") + " AH");
    }

    private void render() {
        if (minutes == null) return;
        Calendar c = Calendar.getInstance(cityZone);
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int nextIdx = -1;
        for (int i = 0; i < NAMES.length; i++) {
            if (i == 1) continue;   // sunrise is shown but is not a prayer
            if (minutes[i] > now) { nextIdx = i; break; }
        }
        if (nextIdx >= 0) {
            int left = minutes[nextIdx] - now;
            String timeStr = (left >= 60 ? left / 60 + " h " : "") + left % 60 + " min";
            next.setText(getString(R.string.prayer_in, getString(NAME_RES[nextIdx]), timeStr));
        } else {
            next.setText(getString(R.string.prayer_tomorrow, getString(NAME_RES[0])));
        }

        rows.removeAllViews();
        for (int i = 0; i < NAMES.length; i++) {
            boolean hl = i == nextIdx;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(6), dp(20), dp(6), dp(20));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(hl ? accent : 0x14FFFFFF);
            card.setBackground(bg);
            int dim = i == 1 ? 0x80FFFFFF : 0xFFFFFFFF;
            int fg = hl ? 0xFFFFFFFF : dim;
            ImageView icon = new ImageView(this);
            icon.setImageResource(ICONS[i]);
            icon.setImageTintList(ColorStateList.valueOf(hl ? 0xFFFFFFFF : (i == 1 ? 0x80FFFFFF : accent)));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
            ilp.bottomMargin = dp(22);
            card.addView(icon, ilp);
            TextView ar = centered(text(26, 400, fg));
            ar.setText(ARABIC[i]);
            card.addView(ar);
            TextView name = centered(text(20, 500, hl ? 0xE6FFFFFF : (i == 1 ? 0x80FFFFFF : 0xB3FFFFFF)));
            name.setText(getString(NAME_RES[i]));
            name.setPadding(0, dp(8), 0, dp(20));
            card.addView(name);
            TextView time = centered(text(42, hl ? 500 : 300, fg));
            time.setText(times[i]);
            // Shrink rather than cut "05:10" when the card is narrow.
            time.setMaxLines(1);
            time.setAutoSizeTextTypeUniformWithConfiguration(20, 42, 1, TypedValue.COMPLEX_UNIT_SP);
            card.addView(time, new LinearLayout.LayoutParams(-1, dp(52)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, i == NAMES.length - 1 ? 0 : dp(8), 0);
            rows.addView(card, lp);
        }
        boolean reminders = getResources().getBoolean(R.bool.reminders);
        SimpleDateFormat localTime = new SimpleDateFormat("HH:mm", Locale.ROOT);
        localTime.setTimeZone(cityZone);
        String updated = getString(R.string.updated_at, localTime.format(new Date()));
        // An offline-cache read is not a fresh publisher update.
        String statusStr = SajadahData.selected(this) != null
                ? getString(R.string.sajadah_source) + (sajadahCache ?
                    " · " + getString(R.string.cached_data) : "")
                : "Aladhan" + (methodName.isEmpty() ? "" : " · " + methodName) + " · " + updated;
        if (reminders) {
            statusStr += " · " + getString(R.string.reminders_active);
        }
        status.setText(statusStr);
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Centred on its own line, whatever the script direction. */
    private static TextView centered(TextView t) {
        t.setGravity(Gravity.CENTER);
        t.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        return t;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
