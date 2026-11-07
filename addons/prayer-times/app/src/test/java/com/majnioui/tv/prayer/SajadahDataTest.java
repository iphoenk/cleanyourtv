package com.majnioui.tv.prayer;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;

public class SajadahDataTest {
    private static final SajadahData.Region BANDUNG =
            new SajadahData.Region("kota-bandung", "Kota Bandung", "Jawa Barat", "Asia/Jakarta");

    private static String fixture(boolean official, boolean wrongRegion, boolean noDhuhr) throws Exception {
        JSONObject o = new JSONObject();
        o.put("schema", 1).put("country", "ID").put("year", 2026).put("official", official);
        o.put("authority", new JSONObject().put("id", "kemenag"));
        o.put("region", new JSONObject().put("id", wrongRegion ? "kota-bogor" : "kota-bandung")
                .put("tz", "Asia/Jakarta"));
        JSONArray fields = new JSONArray();
        for (String f : new String[]{"date", "imsak", "fajr", "sunrise", "dhuha",
                "dhuhr", "asr", "maghrib", "isha"})
            if (!noDhuhr || !f.equals("dhuhr")) fields.put(f);
        o.put("fields", fields);
        JSONArray days = new JSONArray();
        Calendar date = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
        date.clear();
        date.set(2026, Calendar.JANUARY, 1);
        for (int i = 0; i < 365; i++) {
            JSONArray row = new JSONArray();
            row.put(PrayerClock.isoDay(date.getTime(), TimeZone.getTimeZone("Asia/Jakarta")));
            row.put("04:00").put("04:10").put("05:25").put("06:05");
            if (!noDhuhr) row.put("11:50");
            row.put("15:10").put("17:50").put("19:00");
            days.put(row);
            date.add(Calendar.DAY_OF_YEAR, 1);
        }
        return o.put("days", days).toString();
    }

    @Test public void readsExactlyFivePrayersAndSunriseByFieldName() throws Exception {
        SajadahData.Day day = SajadahData.parseDay(fixture(true, false, false),
                BANDUNG, "2026-11-07", false);
        assertEquals(6, day.times.length);
        assertArrayEquals(new String[]{"04:10","05:25","11:50","15:10","17:50","19:00"},day.times);
        assertEquals("Kota Bandung", day.regionName);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsNonOfficialTable() throws Exception {
        SajadahData.parseDay(fixture(false,false,false),BANDUNG,"2026-11-07", false);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsWrongRegion() throws Exception {
        SajadahData.parseDay(fixture(true,true,false),BANDUNG,"2026-11-07", false);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsMissingDhuhr() throws Exception {
        SajadahData.parseDay(fixture(true,false,true),BANDUNG,"2026-11-07", false);
    }

    @Test public void rejectsBadTime() throws Exception {
        try { SajadahData.timeToMinutes("25:00"); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Invalid")); }
    }

    @Test public void keepsOnlyIndonesianRegions() throws Exception {
        String json = "{\"schema\":1,\"regions\":["
                + "{\"country\":\"ID\",\"authority\":\"kemenag\",\"id\":\"kota-bandung\","
                + "\"name\":\"Kota Bandung\",\"province\":\"Jawa Barat\",\"tz\":\"Asia/Jakarta\"},"
                + "{\"country\":\"MY\",\"authority\":\"jakim\",\"id\":\"wly01\","
                + "\"name\":\"Kuala Lumpur\",\"province\":\"KL\",\"tz\":\"Asia/Kuala_Lumpur\"}"
                + "]}";
        assertEquals(1, SajadahData.parseRegions(json).size());
    }
}
