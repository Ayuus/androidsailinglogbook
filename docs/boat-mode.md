# Boat mode: how it works

Boat mode is the clock button in the toolbar. Turned on, it downloads and builds the logbook by
itself at intervals while the boat is out, and publishes it once the boat is back in harbour or the
phone has left the boat -- without anyone tapping anything. This document describes exactly what it
does and why; `boat-mode-test-checklist.md` in this same folder is the field-test checklist that goes
with it.

## The decisions: a state machine in Python

All of boat mode's logic -- what to do next, and when -- lives in one place:
`nmea2log/bootmode.py` in the NMEA repo (`BootModeMachine`), tested on its own with `pytest`
independently of Android. The Android app only carries out what it says (`step()`, called through
Chaquopy) and supplies the timer, the network work and the notification around it. Time is always
passed in with the event, never read from a clock inside the machine, so its whole behaviour is
deterministic and testable.

### Phases

```
OFF --Start--> SEARCHING --W2K-2 found--> ABOARD --harbour / left the boat--> IDLE --W2K-2 found,
 ^                                           |                                       new files--+
 |                                           | (round interval)                                 |
 +-------------------- Stop -----------------+<--------------------------------------------------+
```

- **OFF** -- the mode is off. Nothing happens.
- **SEARCHING** -- looking for the W2K-2 on the phone's own hotspot, every `search_interval_minutes`
  (default 5). As soon as it answers, the first round starts right away.
- **ABOARD** -- a round (download + build) every `round_interval_minutes` (default 60). After each
  round the boat's state at the end of the data (`BoatState`, from the trip-building pipeline: under
  way or not, stationary since when, engine off since when) decides what happens next.
- **IDLE** -- in harbour, or the W2K-2 has not answered for a while: waiting, checking every
  `port_poll_minutes` (default 15) whether the W2K-2 is back with new files, in which case a new
  ABOARD stay starts.

### What ends a stay (the final round)

A round's result is checked against two independent triggers, either of which starts the *final*
round of a stay -- one last download + build, then a publish if there is anywhere to publish to:

- **Reached harbour** (`final_on_harbour`, default on): the boat has been stationary for at least
  `harbour_stationary_minutes` (default 30) and the engine off for at least
  `harbour_engine_off_minutes` (default 10) -- both measured from the pipeline's own `BoatState`, the
  same figures the logbook itself uses. A boat with no engine data at all only needs the stationary
  time. The *same* stay (identified by its start time) is never published twice: mooring, staying
  put, and having the W2K-2 log a few more rounds in port does not repeat the final round.
- **Left the boat** (`final_on_left_boat`, default on): the W2K-2 has not answered for
  `left_boat_minutes` (default 20) in a row. Measured from the first miss of an unbroken run, not
  from the last success -- one missed round after a long gap does not by itself count as leaving.

Publishing on the final round only happens if a destination (WordPress or SFTP) is filled in in
Instellingen. If nothing changed since the last publish and the trigger was "left the boat" (not
"reached harbour", which always just rebuilt fresh data), nothing is sent and the mode says so
instead. A failed publish is retried every `publish_retry_minutes` (default 15) until it works.

`publish_every_round` (default off) publishes after every round, not just the final one.
`stop_after_final` (default off) turns the mode off by itself right after the final round's publish
(or its report, if there was nothing to publish) -- for a one-trip-then-done use instead of leaving
it running for the whole season.

### Settings

All in Instellingen, under "Boot-modus":

| Setting | Default |
| --- | --- |
| Round interval | 60 minutes |
| Publish after every round | off |
| Final round (publish) once the boat is in harbour | on |
| Harbour: stationary for | 30 minutes |
| Harbour: engine off for | 10 minutes |
| Final round when I have left the boat | on |
| Left the boat: W2K-2 unreachable for | 20 minutes |
| Switch boat mode off after the final round | off |
| Start automatically when the app is opened at the W2K-2 | off |

## What a round actually does

A round is the exact same work a manual download does -- find the W2K-2, download new `.ebl` files,
decode and build the logbook -- through the same `nmea2log.android_entry.sync_from_w2k2()` call and
the same log lines, notification progress text (`Downloading: x/y (...)`, `Building logbook: x/y`,
`Building trips: x/4`) and string resources a manual download uses (see `W2kBootExecutor.kt`). It is
not a separate, differently-worded implementation. What *is* boat-mode-specific is the framing around
it -- when a round starts, how long until the next one, and the harbour/left-the-boat/publish
decisions above -- which the Python machine reports as its own status lines (`Boot-modus: ronde
gestart...`, `...ronde klaar, volgende ronde om HH:MM`, `...de boot ligt in de haven, laatste
ronde.`, etc., see `BootStatusText.kt`).

## Notifications and logging

Two separate notifications exist, and they behave differently on purpose:

- **Download/build/publish** (`SyncNotificationService`, channel "Download"): tied to one run.
  Appears when the run starts, updates as it goes, then either turns into a dismissible "Klaar: ..."
  notification (with "Bekijk live site" if it published) or disappears, depending on how the run
  ended. Closing the app while a download or build is running cancels it (it resumes cleanly next
  time, over HTTP Range for the download and from the sample cache for decode/build) and posts
  "Onderbroken door sluiten" instead -- except an upload in progress, which is left to finish first
  (not safely resumable mid-request) and only then reports the close.
- **Boat mode** (`BootModeService`, channel "Boot-modus"): tied to the whole time the mode is on, not
  to one round. It appears the moment the mode starts and stays up continuously -- through searching,
  every round, harbour, publish, waiting in port -- with "Nu" (run a round right now) and "Stop"
  actions, until the mode is switched off (by hand, by "Stop", or by `stop_after_final`). **Closing
  the app never affects it** -- deliberately: while sailing there is no one watching, so a manual
  download's "closing means you left, don't bother continuing" reasoning does not apply, and a phone
  swiped from Recents (routine housekeeping, sometimes done by the OS itself) must not silently turn
  off unattended monitoring. This was a deliberate design decision (see below for why it needs a
  foreground service to do that at all), not an oversight -- if it should instead behave exactly like
  a download's notification (appear only while a round/publish is actively running, gone in between),
  that is a real, considered option for later, not something ruled out technically.

A `[info]` line "Boot-modus staat aan (gestart via het klokje of Instellingen)." is logged every time
the app is freshly opened while the mode is on, so it is never in doubt from the log alone whether it
is still running.

## Why it needs a foreground service

With the screen off for a while, Android's Doze mode stops a plain background process from doing much
of anything -- no reliable timers, no network. A foreground service (one with an ongoing notification)
is exempt from that. A manual download already relies on exactly this (`SyncNotificationService` is a
foreground service too, for the duration of that one run) -- boat mode needs the same exemption, just
for as long as it is switched on rather than for one run, which is why its own service stays foreground
continuously instead of only during an active round.

Concretely, in `BootModeService.kt`:

- **`AlarmManager.setAndAllowWhileIdle()`** schedules the next tick (round, publish retry, search
  probe, port poll) at a real wall-clock time -- exempt from Doze's alarm batching, so it fires close
  to on time even with the screen off for hours.
- **A partial wake lock** is held around every probe, round and publish (released the moment the
  reply comes back, 30 minutes as a safety-net maximum), so the CPU does not sleep mid-download.
- **`FOREGROUND_SERVICE_CONNECTED_DEVICE`** (not `dataSync`, which `SyncNotificationService` uses) is
  the service type: Android 15+ caps a `dataSync` foreground service at a few cumulative hours per
  24h, which boat mode -- meant to run for a whole day's sailing -- would hit; `connectedDevice`
  (the W2K-2) has no such budget. It requires one of a handful of permissions alongside it, satisfied
  here by `CHANGE_WIFI_STATE` (declared for that reason alone -- the app does not itself change any
  wifi state).
- **The state (`BootModeStateStore`, plain `SharedPreferences`)** -- phase, timestamps, which stay was
  last published, the on/off flag itself -- persists across the app closing, the service being killed,
  or the phone rebooting into a fresh process. `START_STICKY` gets a killed service restarted by
  Android; a fresh process (`fresh` in `onStartCommand()`) resumes from that persisted state
  (`ctl.resume()`) rather than starting over, redoing whatever round or publish was interrupted.
  Force-stopping the app (Instellingen → Apps, not just closing it) does kill the service and its
  alarm, and Android does not restart it on its own -- opening the app again sends it an explicit
  resume action, so the mode picks back up then, rather than silently doing nothing forever.
- The **simulation** (`FakeBootModeExecutor`, a developer-only `SharedPreferences` flag, off by
  default and not reachable from the UI) runs the same state machine at 30x speed against fake
  results, for exercising the whole flow -- including the harbour/publish/retry logic -- without a
  real W2K-2 or a multi-hour wait.

## What has not been field-tested

The state machine itself (phases, thresholds, harbour/left-the-boat detection, publish retry) is
covered by its own Python test suite (`tests/test_bootmode.py`) and has been run for hours against the
simulation on a real phone. The real path -- probing, downloading and publishing against an actual
W2K-2 while genuinely out on the water, with the screen off for real stretches of time -- has not yet
been tried; see `boat-mode-test-checklist.md` for what to go through the first time it is.
