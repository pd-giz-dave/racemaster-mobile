# Racemaster Mobile — Implementation TODO

## Re-jig race details

- [ ] remove the course, first bib and number of runners fields from race details, a race id becomes 
      just its name and date
- [ ] add the ability to pull a race name and bib allocations from the server, or a mule, if neither 
      is available bib number range checking is disabled, only duplicate detection remains,
      (see racemaster web app ToDo.MD for the races folder mechanism)
- [ ] the race name field history should include races pulled from the server (as above), selecting 
      one pulls the bib numbers from the server and number validity checking, per course, becomes
      enabled using those, users can enter a name as now if no server list available (with the 
      current manual history unchanged, just mixed and in distinguishable via name format)
- [ ] the above requires that the race details form has access to the setup server form
- [ ] if the first bib number is <100 warn user that they must be entered with leading 0's in bibs 
      mode and CP mode to make them up to 3 digits

## Re-jig time mode

- [ ] on the initial "Start" screen, split the "START" button into 2 -
      one "Start Juniors" the other "Start Seniors",
      selecting either starts the clock but tags the start line as "Juniors" or "Seniors" as 
      appropriate, ditto in the history
- [ ] when time mode is stopped the split button becomes a disabled "Stopped",
      change that so it becomes two buttons "Start Juniors" and "Start Seniors",
      and when one is selected it starts a new race with a new sequence (starting at s0) but it is 
      appended to the history, so there can be multiple start,splits,stop sessions in the history 
      for the race

Time mode just logs time events, the significance of those events is not known by time mode,
the time events only become meaningful when they are matched up to the corresponding bibs mode 
entries.

## Re-jig bibs mode

- [ ] on the initial "Start" screen, split the "START" button into 2 - 
      one "Start Juniors" the other "Start Seniors",
      selecting either starts the clock but tags the start line as "Juniors" or "Seniors" as 
      appropriate, ditto in the history
- [ ] when entering a bib that is invalid for the course, instead of saying "Cannot log that" and 
      disallowing it, allow it but tag it as an error in a similar way to entering a duplicate,
      show the error as "not in legal range 1 to N" where N is the max bib number pulled from the 
      server for the course
- [ ] when bibs mode is stopped the finish and event buttons become disabled, change them so they 
      become "Start Juniors" and "Start Seniors" (make sure they fit the button, shrink the font if 
      necessary) and when either is selected it starts a new race with a new sequence (starting at 
      s0) but it is appended to the history, so there can be multiple clock,finish,stop sessions in 
      the history for the race
- [x] instead of type bib# then say what it is then save it (4 keystrokes for a finish, 
      6 for something else) do this: type bib#, every 3 digits are assumed to be a bib number 
      and auto saved as a finish record, if it is not a finish then change the event type 
      afterwards, the finish button disappears and the event button applies to the last entry, so 
      for normal finishes its 3 keystrokes, for other events its 5, this is relying on bib numbers 
      being usually 101..999 which is the case for all Mercia races, the last entry stays in the 
      enter bib field until the next bib entry starts, undo last also brings the top entry back into
      the enter bib field, this gives the person instant feedback/confirmation of the last number 
      accepted without having to look at the small number in the list below

## Re-jig Cp mode

- [ ] on the initial "Start" screen, split the "START" button into 2 - 
      one "Start Juniors" the other "Start Seniors",
      selecting either starts the mode and adds a line tagged as either "Juniors" or "Seniors" as 
      appropriate, ditto in the history
- [ ] the location for CP mode must contains a single number somewhere in its name,
      eg "polebank 1", "2 hadden", "cp3", etc, tell user of that constraint in the UI
- [ ] retirees seen at a CP or the finish need to be propagated to all other devices so they can 
      update their bib expectations
- [x] do the same auto save (as a pass) on 3 digits entry as bibs mode, pass button is redundant and
      removed the retire button applies to the last entry, so a pass action is 3 keystrokes and 
      retire is 4
- [ ] CP mode needs a count of passes and retires so the marshal can report to the sweep team how 
      many people they have seen, the sweep team will know what they should have seen (==passes from
      previous CP)

CP mode is just a safety thing to record where on a course a runner is.
It also provides a rough time for recording split times.
CP Mode records time-of-day when a runner passed a CP.
Time mode also records time-of-day for all its actions, this means an appropriate elapsed time can
be calculated.

## Re-jig mule mode

- [x] do not propagate history that is more than 2 days old
      (done: PeripheralSyncService's own relay manifest — what this device offers other Mules
      to pull via BLE — now excludes any source whose most recent pull is older than the same
      raceStaleAfterDays setting below, recomputed fresh against the wall clock on every
      DEVICE_INFO read/manifest fetch rather than baked in once, since staleness itself has
      nothing to do with the underlying data actually changing.)
- [x] on the mule mode screen give feedback that the server and a BT sink is visible and working,
      in particular give persistent feedback that a BT sink is polling us,
      (done: Mule Mode shows plain "last X at" timestamps rather than a separate
      connected/stopped state machine per signal — the operator judges staleness by eye the
      same way they already read Nearby devices' red/green colouring. Last push to server
      (MuleRepository.lastPushAttemptAtMillis); web app last seen/last pushed to
      (BluetoothStateRepository.lastWebAppSeenAtMillis/lastWebAppPushedAtMillis — "seen" bumps
      on any contact, "pushed to" only when this device's own data actually reached it, since
      the web app's own sendSinkAck never writes an empty ack); and, per row in Nearby devices,
      last seen/last pulled from that device (DiscoveredDevice.lastReachableAtMillis/
      lastPulledAtMillis), the same seen-vs-data distinction in the pulling direction. Options
      moved to the top bar.)
- [x] mule mode needs a stale race time limit just like the server push, that limit should be a 
      general option and not specific to setup server
      (done: SettingsRepository.raceStaleAfterDays generalizes the old serverSyncMaxAgeDays —
      same DataStore key, unchanged default of 2 days — into the one cutoff both
      MuleRepository.pushToServer's server-sync reconciliation and PeripheralSyncService's BLE
      relay-manifest serving now share (see isRaceStale). Moved off the Setup Server form onto
      the general Options screen (SetupOptionsScreen), with its own explicit Save since that
      screen otherwise has no submit step.)

## All modes

- [x] provide feedback about online/offline status (in the app title?)
      (done: a "Server: Online/Offline/Invalid server/Paused (seen HH:MM)" line on every mode's
      own screen (Time/Bibs/CP as another header line, Mule Mode directly above its own "Last
      push to server" line) — see ui/components/ServerStatusLine.kt. lastOnlineAtMillis rides
      the existing /api/ping health check (MuleSyncClient.ping — no new ping mechanism needed,
      it already existed but wasn't surfacing a timestamp); see
      ServerStatusRepository.lastOnlineAtMillis. First tried appending this to the always-visible
      green app banner instead — reverted after it pushed the banner's title into wrapping onto
      two lines on a real device; the banner's own dot-only indicator is unchanged.)
- [x] in all non mule modes provide feedback that we are being polled via BT (so user can see if BT 
      has failed - which seems to be common) the objective is to let a user know bt polling has 
      stopped and suggest a bt on/off cycle, can that be automated
      (done: a "Last polled by BT: HH:MM" line on Time/Bibs/CP's own header (same slot
      ServerStatusLine already occupies) — see ui/components/BtPollingStatusLine.kt.
      BluetoothStateRepository.lastPolledAtMillis is bumped unconditionally on every
      DEVICE_INFO read in PeripheralSyncService, regardless of which central reads it (an
      ordinary Mule or the web app), unlike the existing web-app-scoped lastWebAppSeenAtMillis
      — a leaf device previously had zero visibility into being polled by an ordinary Mule at
      all. Shows BluetoothStateRepository.advertisingWarning instead when that's set (a
      definite, already-detected wedged-advertiser failure), otherwise the plain timestamp,
      same "operator judges staleness by eye" approach as every other "last X" line in this
      app, deliberately not a computed live "stopped" state (would need its own ticker).
      Automating a real BT on/off cycle turned out not to be possible from app code at all on
      modern Android (no public API for a non-privileged app to toggle the system radio) —
      left undone; advertisingWarning's own message already tells the operator to try that
      manually, or restart the phone, in system Settings.)
            
## Reliability issue

When the Sony phone is used as a mule it reliably reports to the web-app, discovers nearby phones 
and the nearby bibs (Mi 9 phone), cp (Fx_tec phone) and and time (Cubot) relaibly report they are 
being polled, but the Sony phone claims they are unreachable - why? It implies the phones are 
responding but the mule is not seeing it, is this a timing issue? Should mode phones delay their
response by a random jitter? The racemaster app (/home/dave/racemaster) has loads of delays and 
timeouts for the web app side, developed as a result of field experience.