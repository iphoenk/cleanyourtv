package com.majnioui.tv.prayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import org.junit.Test;

public class PrayerClockTest {
    private static final Date MOMENT = Date.from(Instant.parse("2026-11-07T16:30:00Z"));

    @Test public void threeIndonesianZonesUseLocalCalendarDates() {
        assertEquals("2026-11-07", PrayerClock.isoDay(MOMENT, PrayerClock.resolve("Asia/Jakarta")));
        assertEquals("2026-11-08", PrayerClock.isoDay(MOMENT, PrayerClock.resolve("Asia/Makassar")));
        assertEquals("2026-11-08", PrayerClock.isoDay(MOMENT, PrayerClock.resolve("Asia/Jayapura")));
        assertEquals("07-11-2026", PrayerClock.apiDay(MOMENT, PrayerClock.resolve("Asia/Jakarta")));
        assertEquals("08-11-2026", PrayerClock.apiDay(MOMENT, PrayerClock.resolve("Asia/Makassar")));
    }

    @Test public void regionCalendarUsesRegionWallClock() {
        Calendar wib = PrayerClock.calendar(MOMENT, PrayerClock.resolve("Asia/Jakarta"));
        Calendar wita = PrayerClock.calendar(MOMENT, PrayerClock.resolve("Asia/Makassar"));
        Calendar wit = PrayerClock.calendar(MOMENT, PrayerClock.resolve("Asia/Jayapura"));
        assertEquals(23, wib.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, wita.get(Calendar.HOUR_OF_DAY));
        assertEquals(1, wit.get(Calendar.HOUR_OF_DAY));
        assertEquals(8, wit.get(Calendar.DAY_OF_MONTH));
    }

    @Test public void emptyTimezoneKeepsLegacyBehaviour() {
        assertSame(TimeZone.getDefault(), PrayerClock.resolve(""));
        assertSame(TimeZone.getDefault(), PrayerClock.resolve(null));
        assertNotNull(PrayerClock.resolve("Asia/Jakarta"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void misspelledZoneCannotSilentlyBecomeGmt() {
        PrayerClock.resolve("Asia/Jakarta-WIB");
    }
}
