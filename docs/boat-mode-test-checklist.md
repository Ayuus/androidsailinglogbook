# Boat mode: field-test checklist

The boat mode (clock button in the toolbar) has been tested with the simulation. The real search, the real
round and the real publish have never run against a W2K-2. This checklist is for going through all of that in
one go. Everything the mode does is also in the log (`nmea2log.log`, visible in the app and available over
USB/adb), every line with a date and time. The log lines below are the English ones; with the phone set to
another language the text differs, the steps do not.

## Before leaving (at home, 5 minutes)

- [ ] Settings: W2K-2 user and password filled in, publishing (WordPress or SFTP) filled in.
- [ ] Boat mode settings checked: round interval, harbour (stationary and engine off; defaults 30 and 10
      minutes), left the boat (default 20 minutes), "Start automatically when the app is opened at the W2K-2
      (hotspot on)" as wanted.
- [ ] Phone charged, mobile data on (needed to publish; the phone's hotspot is the W2K-2's network).

## 1. Starting and searching

1. Turn the hotspot on and start the boat mode (clock button). The very first time a battery dialog appears:
   choose **Open settings** and set the app to **Not optimised**.
2. Expect in the log: `Boat mode: looking for the W2K-2...`. The notification is titled **My Sailing Logbook**.
3. As long as the W2K-2 is not on the hotspot: it looks again every 5 minutes, without an error message.

## 2. The first round

1. As soon as the W2K-2 is on the hotspot: `Boat mode: round started (download and build)...`
2. The notification follows the progress: `Downloading: x/y (...)`, then `Building logbook: x/y`, then
   `Building trips: x/4`.
3. Done: `Boat mode: round done, next round at HH:MM.` That time is one round interval later.
- [ ] Both work: download and build, with the notification updating.
- [ ] The screen may go off and the app may be closed (swiped from recent apps); the notification stays.

## 3. A round later, with the screen off

- [ ] At the stated time the next round starts by itself (`round started...`), with the screen off and the app closed.
- [ ] Tap **Round now** in the notification: a round (or a search) starts at once.
- [ ] Open the app while the mode runs: you see the mode's log, not the logbook, and no sync of its own starts.

## 4. In harbour

1. Moor, engine off. After at least 30 minutes stationary and at least 10 minutes engine off, at the next round:
   `Boat mode: the boat is in harbour, final round.`
2. Then `Boat mode: publishing...` and `Boat mode: published.` (and an `[ok]` line with the destination).
3. Then `Boat mode: waiting in port, next check at HH:MM.`
- [ ] The published logbook on the site is up to date.
- [ ] The same stay is not published again at the following checks.

Publishing fails (no internet): `Boat mode: publishing failed, trying again at HH:MM.` It is repeated until it works.

## 5. Leaving the boat

1. Leave the boat with the phone (the hotspot goes out of the W2K-2's range, or turn it off).
2. Every round: `Boat mode: W2K-2 not reachable, trying again at HH:MM.`
3. After 20 unbroken minutes without the W2K-2: `Boat mode: left the boat (W2K-2 gone), final round.` and a
   publish, or `Boat mode: left the boat, nothing new to publish.`
- [ ] A single missed round after a long pause does not count as "left the boat".

## 6. Recovery

- [ ] Force-stop the app during a round, open the app again: the notification comes back and the round is redone.
- [ ] Start a sync or build of your own during a round: `Boat mode is busy with a round or a publish; try again in a moment.`

## What to send back

Pull the log (`adb exec-out run-as com.ayuus.mysailinglogbook cat files/nmea2log.log`) and send it, together with
the time you moored / left the boat and what you saw that was wrong. Look especially for: alarms that do not
fire with the screen off (Doze), a round that stops halfway, and the time between mooring and the final round.
