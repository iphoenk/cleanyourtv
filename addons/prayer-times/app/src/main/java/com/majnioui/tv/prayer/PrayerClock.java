package com.majnioui.tv.prayer;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Resolve an optional timetable timezone, independent of the TV's own timezone.
 * Empty configuration preserves the original device-local behaviour.
 *
 * In particular, a TV in WIB can display a timetable configured for WITA or WIT
 * without requesting yesterday's dates or firing alarms at WIB wall-clock times.
 */
final class PrayerClock {
    private PrayerClock() {}

    static TimeZone resolve(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return TimeZone.getDefault();
        }
        String id = configured.trim();
        if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(id)) {
            // TimeZone.getTimeZone silently returns GMT for misspellings; that is
            // unsafe for prayer reminders. Reject instead of shifting the day.
            throw new IllegalArgumentException("Unknown time_zone: " + id);
        }
        return TimeZone.getTimeZone(id);
    }

    static String isoDay(Date instant, TimeZone zone) {
        return format(instant, zone, "yyyy-MM-dd");
    }

    static String apiDay(Date instant, TimeZone zone) {
        return format(instant, zone, "dd-MM-yyyy");
    }

    static Calendar calendar(Date instant, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTime(instant);
        return calendar;
    }

    private static String format(Date instant, TimeZone zone, String pattern) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.ROOT);
        formatter.setTimeZone(zone);
        return formatter.format(instant);
    }
}
