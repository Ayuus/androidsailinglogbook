# My Sailing Logbook

Android app that syncs voyage data from a boat's [Actisense W2K-2](https://actisense.com) NMEA
2000-to-WiFi gateway, builds the same HTML sailing logbook the desktop
[nmea2log](https://github.com/Ayuus/nmea2log) CLI produces, shows it in-app, and (optionally)
publishes it to a WordPress site or over SFTP. An optional "boat mode" can also do all of the
above on its own, on an interval, while the app stays closed -- see "Using the app" below.

It does **not** reimplement any of the NMEA 2000 decoding, trip-building, or HTML-generation
logic. It embeds the real `nmea2log` Python package from the `nmea2log` repo directly
(via [Chaquopy](https://chaquo.com/chaquopy/)) and drives it from Kotlin. A fix or feature added to
`nmea2log`'s Python code is picked up by this app automatically on the next build -- there is no
copy to keep in sync.

**Looking for testers**: so far this has only been run against one boat's NMEA2000 network (a
**motorboat**, one Actisense W2K-2) and two physical Android devices. Other boats/instrument mixes
and other Android versions/devices will likely surface issues this setup never hits. Sailboat
support in particular is on the wishlist but untested so far -- see the same note in the
[nmea2log README](https://github.com/Ayuus/nmea2log#readme) for why. Feedback is very welcome via
[GitHub issues](https://github.com/Ayuus/androidsailinglogbook/issues).

## Installation

**Play Store** (preferred): *pending review, link coming soon.* No install warnings, and updates
automatically.

**Or, the APK directly**: grab it from the
[latest release](https://github.com/Ayuus/androidsailinglogbook/releases/latest) instead. This is
signed but not distributed via the Play Store, so Android will warn you before installing it --
expected for any app installed outside a store.

1. Download `app-release.apk` from the release page (under "Assets").
2. Open the downloaded file. Android will ask for permission to install apps from this source
   (browser/file manager) the first time -- allow it.
3. Tap Install.

Requires Android 7.0 (API 24) or newer.

## Using the app

### First-time setup

Open Settings (the gear icon, top right of the toolbar) and fill in:

- **W2K-2 username/password** -- the same login the W2K-2's own web interface uses.
- **Boat name, MMSI, call sign** -- shown in the logbook's header, not sent anywhere by
  themselves.
- **Publishing** (optional) -- either a WordPress Application Password (for an account in the
  `logboek_editor` role) or SFTP host/user/password/remote path, if you want the built logbook
  sent to your own website. Leave both blank to keep everything on the phone.

The phone needs to be on the boat's own WiFi -- i.e. the phone runs its own hotspot and the
W2K-2 joins it as a client, the same setup the W2K-2's own app expects. There is no other way to
reach it; the app never talks to it over the internet.

### The toolbar

Left to right: **download** (fetch new data from the W2K-2 and build the logbook), **rebuild**
(build the logbook again from whatever's already on the phone, no W2K-2 needed -- useful to pick
up a settings change, or just to see the logbook without being near the boat), **publish** (send
the current logbook to the website configured in Settings), **view logbook** (show the
already-built logbook full-screen, toggles back to the log), **boat mode** (see below), and
**settings**.

A long-running action (download/rebuild/publish) shows a pulsing version of its own button --
tap it again to cancel. The notification shade shows the same thing while the app isn't on
screen, with a real progress bar.

### Boat mode

Turned on/off via the sailboat button (or the matching checkbox in Settings, which can also
start it automatically whenever the app opens with the boat's hotspot up). Once on, it keeps
running in the background -- the app doesn't need to stay open -- and:

1. **Searches** for the W2K-2 every few minutes (configurable in Settings, "Zoekinterval"),
   without downloading anything yet.
2. Once found, runs a **round**: downloads new data and rebuilds the logbook, then waits for the
   configured interval ("A round (download + build) every ...") before the next one.
3. Recognises being **in harbour** (stationary + engine off, both for a configurable number of
   minutes) and **having left the boat** (the W2K-2 stops answering for a configurable number of
   minutes) as two different "the voyage is over for now" signals, each independently switchable
   to trigger a **final round** (and, if publishing is configured, an actual publish) --
   see the checkboxes under "Boot-modus" in Settings.
4. Can optionally switch itself back off after that final round ("Switch boat mode off after the
   final round"), or keep running and simply start searching again.

Android's own battery optimisation can hold back a background app's timers while the phone lies
still (e.g. moored in a marina) -- boat mode asks, once, to be exempted from that the first time
it's turned on. Declining is safe; rounds just become less reliably on-time if the phone has been
idle for a long while.

See [docs/boat-mode.md](docs/boat-mode.md) for the full technical writeup (states, timers,
notification behaviour) if you want more detail than this section gives.

### Why place names show up in the logbook

Every trip's departure/arrival, and the boat's last known position, are reverse-geocoded into a
real place name (e.g. "Écluse du barrage d'Arzal") rather than shown as bare GPS coordinates.
This uses OpenStreetMap's [Overpass API](https://overpass-api.de/) to find the nearest named
landmark, falling back to [Nominatim](https://nominatim.org/) for a plain address when nothing
suitable is nearby -- the same two-step lookup the desktop `nmea2log` CLI does, see the
[nmea2log README](https://github.com/Ayuus/nmea2log#readme) for the full explanation. Both need a
working internet connection on the phone at build/publish time (not from the W2K-2 -- that part
never needs internet at all); a lookup that keeps failing gives up for the rest of that run and
falls back to coordinates instead of retrying forever.

(The sections below are for building this app from source instead -- not needed just to install
it.)

## Requirements

- **The [nmea2log](https://github.com/Ayuus/nmea2log) repo, checked out separately on the same
  machine.** This app's Gradle build points directly at that repo's `src/` directory (specifically
  the `nmea2log` package inside it) as a Chaquopy source set. It is not vendored or
  copied in here -- these two repos are only meant to be built together, side by side.
- Android Studio (or a standalone Gradle/JDK toolchain) with Android SDK **compileSdk/targetSdk
  37**, **minSdk 24**.
- A local Python **3.14** interpreter on the build machine. This is a Chaquopy build-time
  requirement only -- separate from the Python runtime Chaquopy bundles into the built APK -- and
  must match the version configured in `app/build.gradle.kts`'s `chaquopy { defaultConfig { version
  = "3.14" } }`.
- A real Actisense W2K-2 (or a network host that answers the same undocumented HTTP API -- see
  `w2k2_download.py` in the nmea2log repo) to actually test the sync flow against. There is no
  simulator/mock for it.

## Building

1. Clone this repo and `nmea2log` next to each other, e.g.:
   ```
   Github/
     NMEA/                    <- nmea2log
     androidsailinglogbook/   <- this repo
   ```
   (they don't have to be literal siblings -- any two paths work, see step 2 -- but that's the
   layout this project has been built and tested with).
2. Add the path to nmea2log's `src/` directory to this repo's `local.properties` (create the file
   if Android Studio hasn't already generated one with `sdk.dir` in it):
   ```properties
   nmea2log.src.dir=C\:\\Users\\you\\...\\NMEA\\src
   ```
   (Windows paths need doubled backslashes in a `.properties` file; a Linux/macOS path like
   `/home/you/.../NMEA/src` needs no escaping.) `local.properties` is git-ignored on both machines
   it's used on for a reason: this path is only ever correct on the one machine it names. The build
   fails with a clear error message if this property is missing.
3. Build and install:
   ```
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   or just open the project in Android Studio and run it.

There is no CI here and no automated test suite for the Kotlin code (see "Not yet built" below) --
the Python side's own extensive test suite lives in the nmea2log repo and covers everything this
app calls into.

## How it fits together

```
MainActivity (manual "sync now" + auto-start on launch)
  -> HotspotDetector           finds this phone's own hotspot subnet (NetworkInterface enumeration)
  -> android_entry.sync_from_w2k2()   [Chaquopy call into the real nmea2log package]
       -> w2k2_download.discover_w2k2()   scans that subnet for the W2K-2's HTTP API
       -> w2k2_download.download_file()   downloads new/changed .ebl files
       -> run_pipeline()                  decode -> build_trips -> write_html_logbook()
  -> WebView shows the resulting logbook.html
  -> SftpUploader (optional)      publishes logbook.html + backs up new .ebl files
```

- **`android_entry.py`** (in the nmea2log repo, not here) is the Chaquopy entry point. It mirrors
  what the desktop CLI's `_run()` does -- minus argument parsing and minus any SFTP upload, both of
  which are Kotlin's job on this platform -- and returns a plain `dict` a background thread can
  read, instead of relying on stderr text or an exit code the way the desktop CLI does.
- **`SettingsStore`** wraps `EncryptedSharedPreferences` for everything `nmea2log.ini` holds on
  desktop (W2K-2 login, boat identity, SFTP publish settings) -- there is no config file on
  Android, values are entered once via `SettingsActivity`.
- **`SyncController`** is the interface Kotlin implements and hands to
  `android_entry.sync_from_w2k2()` via Chaquopy, so Python can call back into it like a normal
  Python object: `report()` for per-file progress, `isCancelled()` to stop a sync cleanly when the
  app closes, `onLogLine()` to mirror the desktop CLI's own `[info]`/`[ok]`/`[skip]`/`[warning]`
  messages verbatim in the UI, and `onDownloadComplete()` (see below).

## Design choices worth knowing before changing this code

**Hotspot detection is about *this phone's own* hotspot, not an external network join.**
`HotspotDetector` looks for this phone's own AP-bridge network interface (`ap_br_swlan0` on the
test Samsung Galaxy S23; Android's docs mention `ap0`/`wlan1` as other common OEM names) having a
private IPv4 address up -- i.e. whether *this phone's* tethering/hotspot feature is switched on.
The boat setup is: the phone runs its own hotspot, and the W2K-2 joins it as a client. This matters
because it rules out `ConnectivityManager.NetworkCallback` (which observes networks *this device*
joins as a client, not its own AP state) as an event source for "is the hotspot back" -- see the
reconnect logic below.

**No SFTP anywhere was the original hard rule for the first milestone**, so that nothing on the
live site could break while the sync pipeline itself was still being built and tested against the
real device. SFTP publishing (password-authenticated, not key-based -- see below) was added once
that milestone was confirmed working, and is opt-in: the app never uploads anything unless the
"Publish to ayuus.com" settings are filled in.

**Reconnect handling is poll-based, not event-based, and deliberately checks the real device, not
just the local hotspot state.** When a sync fails, the user is offered "wait for connection" or
"close the app" instead of an immediate retry (which, out of range of the boat, would just fail
again immediately and show the same dialog again -- reads as nagging). "Wait for connection" polls
in the background (interval: the existing sync-interval setting, reused rather than adding a
second interval setting) and, on each tick, re-runs the real `discover_w2k2()` scan -- not just a
check of whether the phone's own hotspot toggle is on. The hotspot commonly stays on the whole time
while the W2K-2 itself drops off it (e.g. walking away from the boat), so hotspot-state alone
would never notice the actual problem. A clean, officially-supported "a client (re)joined my own
tethering hotspot" event does not exist on modern Android for a third-party app (the old
`WIFI_AP_STATE_CHANGED` broadcast is unofficial/undocumented and unreliable across OEMs/versions),
so polling the actual target is the honest option here, not a shortcut.

**The sync notification is stopped as soon as downloading finishes, before decode/build runs.**
`SyncController.onDownloadComplete()` fires once, right after the last file's download attempt and
before Python's decode/build/write pipeline starts. Kotlin uses it to stop the foreground
notification service at that point rather than keeping it running for the whole call: decode/build
is pure CPU, no network I/O, so there's no reason to keep paying for a "dataSync" foreground
service during it. This matters because Android 15+ (this app targets SDK 37) caps a `dataSync`
foreground service at **6 cumulative hours per rolling 24h period**; a long day of intermittent
connectivity could otherwise burn through that budget on wait time and idle CPU work rather than
actual network activity, and Android then simply refuses to start the service again until the
budget resets or the user brings the app to the foreground. `startSyncNotification()` also catches
that refusal gracefully wherever it's used -- a sync still completes without a visible notification
rather than crashing outright if the budget is ever actually exhausted.

**SFTP uses password auth via [sshj](https://github.com/hierynomus/sshj), not the desktop CLI's
key-based OpenSSH-CLI approach**, since there's no `sftp` binary to shell out to on Android and
importing/storing a private key adds real complexity this project didn't need once password auth
against the real server was confirmed to work. BouncyCastle is registered as a `Security` provider
at startup (`MainActivity.onCreate()`) because Android's built-in "BC" provider is a cut-down one
that's missing Ed25519/X25519 support, which sshj needs for the server's host key exchange even
with password auth. The server's host key fingerprint is pinned on first connect
(`SettingsStore.sftpHostKeyFingerprint`, trust-on-first-use) and checked on every later connection,
the same protection `StrictHostKeyChecking=accept-new` gives the desktop CLI.

**`.ebl` backup preserves each file's own `EBL000000/`-style subfolder on the remote server**,
unlike the desktop CLI's own `--backup-ebl`, which uploads everything into one flat remote
directory. Deliberately different: thousands of same-shaped filenames in one flat folder is much
harder to browse than the same structure the files already have locally. Backing up is entirely
optional -- it only happens if a remote backup folder is filled in in settings, checked by that
field being non-blank rather than a separate on/off toggle.

**The generated `logbook.html` renders server-side in Dutch by default, unchanged** -- but every
translatable label also carries a `data-i18n`/`data-i18n-tpl` attribute, and all four supported
languages (NL/EN/FR/DE) are embedded in the page as one JS object. A flag button in the page's own
header lets a viewer switch languages client-side without needing the file regenerated, since the
page can be opened by people who don't read Dutch. This lives in the nmea2log repo
(`html_writer.py`/`translations.py`), not here, but the Android app benefits from it automatically
since it calls the same `write_html_logbook()`.

**The page also shows the boat's single most recent GPS fix ("Laatste positie") under "Laatst
bijgewerkt".** A trip that's still underway when the downloaded data runs out has no arrival place
of its own to show in the trips table (`"Unknown (end outside log file)"`), which used to leave no
indication anywhere on the page of where the boat actually last was. Built from the single latest
GPS fix across the *whole* dataset, not the last completed trip's own arrival -- those can disagree
by hours or days if the boat has been anchored/idle (still logging position) since the last trip
closed. Reverse-geocoded into a place name the same way every trip's own depart/arrive place
already is; falls back to plain coordinates when geocoding is off, which Android always keeps off
(see below).

**Geocoding, weather, and marine (wave/current) lookups are enabled by default on Android too**,
same as the desktop CLI (see `android_entry.run_pipeline()`'s own doc comment) -- this changed
since an earlier version of this README said otherwise. All three ride the phone's own internet
connection, not the W2K-2's (which never needs internet at all); no settings toggle exists yet to
turn them off for someone who'd rather save cellular data than see place names/weather/sea state
in the logbook.

**The toolbar uses plain single-path vector drawables, not emoji-as-button-text** (an earlier
version of this app used 🔄/☁️/⚙️ etc.; replaced once icon *behaviour* -- a pulsing "busy" state,
matching on/off icon pairs for boat mode -- needed more control than a font glyph gives).
Borderless (no background box/shadow, `selectableItemBackgroundBorderless`, zero minimum touch
target) so they still read as plain icons rather than boxed buttons; tint applied at runtime by
`iconButton()`, so each drawable's own `fillColor` is just a placeholder.

## Not yet built

- **Local `.ebl` cleanup** after a file is confirmed both decoded and backed up remotely -- the
  desktop CLI deliberately keeps every `.ebl` forever (its whole project directory is already
  backed up via OneDrive), but that reasoning doesn't hold on a phone's own storage.
- **A cellular-data toggle** for geocoding/weather/marine lookups, for someone who'd rather spend
  the data than see `NoGeocoder()`'s bare coordinates.
- **Any automated Kotlin test suite.** The Python side this app calls into is covered by
  nmea2log's own extensive pytest suite; the Kotlin/Android-specific code (notification handling,
  settings storage, the SFTP client, the reconnect flow) currently has none of its own.
