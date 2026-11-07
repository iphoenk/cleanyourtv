# Prayer Times

Today's five obligatory prayer times and sunrise for one city, full screen on the TV,
with the next prayer highlighted and a countdown. The calculated Aladhan mode
shows Gregorian and Hijri dates; Kemenag-through-Sajadah mode shows only the
Gregorian date to avoid implying an official Indonesian Hijri calendar.
Each prayer has its localized and Arabic name. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so once you answer
**Show card** its tile reads "Prayer Times" with a green glow; on any other Android TV home it is an ordinary app.

![The prayer times page](https://raw.githubusercontent.com/majnioui/cleanyourtv-contrib/pr-assets/addon-prayer-times/prayer-times.png)

- **Data** comes from [Aladhan](https://aladhan.com/) by default. Indonesian
  users can opt in on the TV to Kemenag timetables relayed by
  [Sajadah Open Data](https://sajadah.org/open-data), without an API key.
  This is not a direct Kemenag integration.
- **The city and the calculation method** are set before you build, in `app/src/main/res/values/config.xml`:
  `latitude`, `longitude`, `place` (the name shown) and `method`, Aladhan's
  [method number](https://aladhan.com/calculation-methods) (2 ISNA, 3 Muslim World League, 4 Umm al-Qura,
  5 Egyptian, 13 Turkey, 21 Morocco...). The default is Casablanca with the Morocco method.
- **The optional `time_zone`** is an IANA timezone for the configured city. Leave it empty to use the TV's
  timezone as before. This matters when the TV and prayer timetable use different zones: the day sent to
  Aladhan, countdown, and reminder alarms must agree. An invalid value displays a configuration error and
  prevents reminders; it does not silently fall back to GMT.
- **The Asr school** is `school`, Aladhan's juristic school for Asr: `0` Shafi (the default) or `1` Hanafi.
  Hanafi users see the later Asr time; leave it at `0` and nothing changes.
- **Reminders switch**: In `app/src/main/res/values/config.xml`, `reminders` controls whether to schedule
  on-screen alert cards and notifications when a prayer time arrives. It is `false` (off) by default. When
  enabled (`true`), an unobtrusive, frameless compact card appears at the top-right corner of the screen
  displaying the localized prayer name, Arabic script, time, and city, accompanied by a single notification chime.
  It dismisses automatically after 10 seconds or immediately on any remote button press.
- It reads Aladhan when the page opens and again after midnight. The countdown updates every minute.
  Offline, the last times stay on screen.
- 24-hour times, localized prayer names and strings for English, Indonesian (`values-in`), and Arabic (`values-ar`).

## Indonesia (WIB, WITA and WIT)

The existing Aladhan API has calculation method **20**, named "Kementerian Agama
Indonesia" by Aladhan. It is **a calculation method**, not a feed of actual published
timetables from the Indonesian Ministry of Religious Affairs (Kemenag). Do not label
its output as verified official Kemenag timetable data.

Set `latitude`, `longitude`, `place`, `method` and the new optional `time_zone`
together in `app/src/main/res/values/config.xml`. Example city-center coordinates
(approximate; choose the correct locality before building):

| City | Latitude | Longitude | Method | `time_zone` |
|---|---:|---:|---:|---|
| Jakarta | -6.2088 | 106.8456 | 20 | `Asia/Jakarta` (WIB) |
| Denpasar | -8.6500 | 115.2167 | 20 | `Asia/Makassar` (WITA) |
| Jayapura | -2.5337 | 140.7181 | 20 | `Asia/Jayapura` (WIT) |

Only the five obligatory prayers (Fajr/Subuh, Dhuhr/Zuhur, Asr/Asar,
Maghrib/Magrib, and Isha/Isya) are used for prayer countdowns and reminders.
Sunrise/Syuruq remains informational as in the existing add-on. **Imsak and Dhuha
are deliberately excluded from display, reminders and feature scope**, even if
returned by an Indonesian timetable provider.

The timezone affects the requested local **date**, the time displayed, countdown,
and scheduled alerts, not just the timezone label. For example, an instant late in
the evening in WIB may already be tomorrow in WITA and WIT.

The [Bimas Islam jadwal sholat portal](https://bimasislam.kemenag.go.id/jadwal-sholat)
is the reference for published schedules. This add-on does not request tokens, scrape
the portal, or claim to be connected to Kemenag's restricted API. Compare results
against official local schedules before relying on alerts. Do not assume the Hijri
calendar returned by Aladhan reflects Indonesia's final sidang-isbat decisions.

## Kemenag timetables through Sajadah (optional)

On the TV, focus the city label at the top and press **OK**. Choose **Kemenag RI via
Sajadah**, then a province and regency/city. The public region index is loaded from
`https://sajadah.org/api/v1/regions.json` and a selected year's timetable from
`https://sajadah.org/api/v1/times/id/{region}/{year}.json`.
No GPS, API token, additional permission, or backend is needed. The existing
default (calculated Aladhan times) is unchanged until the user explicitly opts in.

This is **Kemenag RI data supplied via Sajadah**, not a direct authenticated
connection to Kemenag SIHAT. The relay marks its tables as official and says
they reproduce Kemenag schedules without changing times. This add-on validates
the schema and rows, but does not independently authenticate every value or
cryptographically verify the dataset's provenance.

The region index is cached on disk. Complete validated yearly tables are stored
atomically in app-private storage, refreshed on demand after 24 hours, and reused
when offline for the matching date/year/region. A new year is requested on first
use in that year. The app rejects a missing date, wrong region, incorrect timezone,
incomplete year, missing required fields, non-increasing times, or an unexpected
authority. No old region's times, previous day's times, or unlabelled Aladhan
substitution are allowed in official-table mode. If the next date cannot be loaded,
reminders are cancelled and, if enabled, automatically retried after an hour.

Only **Subuh, Syuruq, Zuhur, Asar, Magrib, Isya** are consumed from the relay.
Syuruq remains informational and does not trigger a prayer alarm; **Imsak and
Dhuha are neither displayed nor scheduled** even if present in the JSON.
The app uses the selected region's IANA timezone for date, countdown and alarms,
regardless of the TV's own timezone. Unlike the calculated Aladhan display,
the official-table mode shows only the Gregorian date: Aladhan Hijri dates
should not be presented as Indonesia's final sidang-isbat decisions.

This is a **pre-PR development branch**. The relay's documented endpoints were
not accessible to the agent environment for a full live response check. Verify
actual HTTP JSON, TV region-picker navigation, reminder scheduling, offline
cache and rollover behavior before upstream submission. A sample of dates
should also be checked independently at
[Bimas Islam Kemenag](https://bimasislam.kemenag.go.id/jadwal-sholat).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Aladhan: date, configured coordinates and calculation settings; Sajadah: Indonesian region index and selected region/year timetable. |
| `SCHEDULE_EXACT_ALARM` | Exact alarm scheduling for prayer time reminders. |
| `RECEIVE_BOOT_COMPLETED` | Reschedules prayer alarms automatically when the TV boots. |
| `WAKE_LOCK` | Briefly wakes the screen to deliver the on-screen alert card. |
| `SYSTEM_ALERT_WINDOW` | Displays the unobtrusive top-right alert card over other apps and video playback on Android 10+. |

That is all. In Aladhan mode, requests include the date, configured latitude/longitude,
method and Asr school. In Sajadah mode, the client downloads a public region index and
the selected region's timetable for the relevant year (region ID and year in the URL).
The server receives normal network metadata such as the requester's IP address.
No account, GPS/location permission, app telemetry, ads or background service is
required. Android app backups are disabled.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/prayer-times && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

If `reminders` is set to `true`, grant the overlay permission so the on-screen alert card can display over playing video and other apps:

```bash
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'appops set com.majnioui.tv.prayer SYSTEM_ALERT_WINDOW allow'
```

On Android 14+ (API 34), exact alarms are off by default. The add-on safely falls back to `setAndAllowWhileIdle`, or exact timing can be explicitly granted via:

```bash
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'appops set com.majnioui.tv.prayer SCHEDULE_EXACT_ALARM allow'
```

> **Note:** Force-stopping the app (`am force-stop com.majnioui.tv.prayer`) removes all pending alarms scheduled in Android's `AlarmManager`. When testing real alarms, do not force-stop the app.

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own builds
with your own key if you hand them out; the launcher pins its Show answer to the signing key.

## Credits

Prayer icons: [Lucide](https://lucide.dev) (ISC) and [Feather](https://feathericons.com) (MIT). Font: Outfit
(SIL Open Font License, `FONT-LICENSE-OFL.txt`).

## Tested on

- Xiaomi TV (MiTV-MOOR2), Google TV 11, RTM5.220609.003: the page, the countdown, the card prompt and the card shown in the launcher's dock.
- TCL 43A50 (Smart TV Pro), Android 11 (build RP1A.200622.001): background prayer alarms, top-right unobtrusive alert card over active apps and home screen, and auto-dismiss after 10 seconds.

## Maintainer

[@majnioui](https://github.com/majnioui)
