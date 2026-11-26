# Done

## Setup Race screen

- [x] the race name field as entered or fetched or adopted should persist and should always
      reflect what the device is actually recording
- [x] the race name field should follow the same style as location, specifically:
      prompt should be in the box not the field, also the field should default to 'unknown'
      as a hint to the web-app that it has not been adopted
- [x] the location field entered/selected should also persist
- [x] add a prompt for the mode: time, bibs, cp - also persisted
- [x] location validation against mode should be performed here,
- [x] both mode and location can be changed via the 'Relocate' option, a relocate change is
      as now - add a LOCATION record (as of 20/9/26 edits not documented here) but also add a
      ModeStart record 
- [x] with the mode now being defined here the 'setup' special record is no longer required
      the ModeStart record will suffice, so drop the 'setup' record
- [x] for a new race a LOCATION marker should be inserted so all location segments are identically
      structured including the first one, so a segment is LOCATION + any number of ModeStart
      records, each followed by any number of bib/split records, in BNF:
        records ::= {segment}
        segment ::= {modes}
        modes   ::= Location ModeStart {bib | split}
      in this scheme the ModeStart record does not need the location, location is purely defined
      by the location record
- [x] given the above, in the ModeStart record, instead of implying the mode from the bibNumber 
      and splitTime field, explicitly state it in the note field and leave bibNumber and splitNumber
      fields as null
- [x] once a race is setup the Location and ModeStart records should be written and the device 
      should start advertising itself (and not wait for a 'start')
- [x] change the "Create" button name to "Save" with a hint under it that it also makes the
      device visible to the web-app

## Mode picker screen

- [x] mode is now setup in race setup form, so Time Mode/Bibs Mode/CP Mode buttons collapse to
      "Start <mode>" - where <mode> is as setup race, disabled before any mode selected
- [x] "Start <mode>" now goes straight to the mode screen, no intervening start screen and no
      writing of a ModeStart record (that is now done by Setup Race or Relocate)
- [x] the setup feedback that was on the start screens now appears on the picker screen in place
      of the current "Select device mode" text

## Mode screens

- [x] rename 'Mode' to 'Back'

## All screens

- [x] change the "Cancel" link to "Back" and move it to the right of the screen title
- [x] make the same change to all screens that have a "Cancel"

## Re-work history files?

Instead of a separate file for each race+date have just one per race.
Drop the date suffix both here and in the mobile files stored on the server.
The data there is only relevant on race day, so when the same race is re-run next year it can
just overwrite it. Users can choose to add a date suffix if they wish on the race name as they
now already do for the course (e.g lmv-2026, brieddens-seniors-2026, brieddens-juniors-2026).
The web-app can extract the date from the timestamps in the history file and echo that as an
indication to the web-app operator that they are looking at a current file and not a historical
one.

- [x] The above is irrelevant, the mobile app inherits whatever the web-app chooses to call the race.
- [x] do not suffix todays date on the race name entered in setup race

## What does reset mean?

- [x] It means invalidate the current segment (location+mode).
      Do it again to walk up the history segment by segment.
      So add a refLineNumber for reset pointing at the Location record for the segment(s) being reset.
      This means an Undo across segment boundaries is not necessary, just reset the segment.
- [x] Resetting the race's own very first-ever segment is no different from resetting any other —
      it does NOT revert the phone to "no race setup" any more (that used to clear the persisted
      race setup fields and go quiet, but confirmed in the field as confusing once a mode screen
      can show multiple still-open locations: the operator just wants that segment re-startable,
      not kicked back to Setup Race). race.location and activeRaceId both stay put; the mode
      screen's own Start button keeps working (see RaceRepository.ensureOpenSegment), re-opening
      a fresh segment at the same location with the counter back at 1.

## Tweaks from observations of using the app

- [x] races history is showing multiple (self) lists for the same race
- [x] races history is showing multiple progress lists for the same race
      (maybe due to coming via different sources?)
- [x] if a self history file is deleted, delete it on the web too and tell any connected mules
      to dump it as well (ie. ensure it gets flushed from everywhere, mule should also tell the
      web-app its gone and remove it from the server, else it keeps coming back,
      the way to do this is via the New Race system added recently - if a user deletes a self 
      history file on the phone, first empty it and add a NewRace record so the file is now
      just a New Race record, then propagate that like any history file, once its been ack'd
      then delete it, before that mark it in the history list a pending delete
- [x] drop the STOP notion altogether, its redundant, replace the dual mode STOP/RESET button
      in the mode screens with just a RESET (to mean as above, the confirm dialog to explain this)
- [x] need to allow for relocating back to some previous location, which case it must pick up
      where it left off, typical scenario - finish time mode records a split for the race start
      then relocates to CPn, then back to the finish before the first finisher arrives and starts
      recording finish splits, when returning to a previous location the mode screen should look
      like it was when they left
- [x] add a heartbeat mechanism so the web app user can tell the phone is alive and well, either
      via the server (file timestamp) or via BT - do it via a PING record in the history every
      N seconds (N is another setup option - default 60) when there is no other activity, 
      shows in history files but is ignored, but does not show in the mode screen lists
- [x] undo last in time mode includes the initial start, in bibs/cp mode it does not, make them
      consistent - make bibs/cp like time

## More bugs

- [x] options screen needs a vertical scroll so all options fit on small screens
- [x] heartbeat should start as soon as a race is setup irrespective of the selected mode starting
- [x] cannot relocate from time mode to cp mode - the legacy 'reset' guard is still in place
- [x] time mode ping is showing a split time
- [x] bibs mode not being syn'c to server until a ping comes along (the phone showing this is
      also a mule and also logged in to the server) - couldn't find a mechanism in the push
      code that ties bibs sync to ping specifically; the leading theory is this described the
      old pre-heartbeat gap (a quiet bibs station had nothing to push until something wrote a
      new line), which the heartbeat itself + the "start as soon as setup" fix above should
      already close - please re-test and reopen with more detail if it's still happening
- [x] when a reset walk goes right back to the beginning it should keep sync'ing until any
      pending lines (including the final reset) are sync'd (so the rest of the world knows its
      gone)
- [x] suppress location lines in the mode screen lists, it adds no information (its already echoed
      in the summary block) and just confuses the phone operator
- [x] time mode's location/reset/new race records are still getting a split time, they should be
      null like bibs/cp's own records for these (fixed on the wire and in Race History's own      local display; the web-app's Time/Bibs classification, which used to lean on splitTime
      nullness to tell these shared-action rows apart, now resolves each via its own structural
      neighbour instead)
- [x] progress records are not getting propagated from the server/web-app BT to the phones
- [x] history line formats on the phones are not consistent with modern system, specifically
      null times are being shown as 00:00:00 and null bibs are being shown as n/a
- [x] NewRace marker is being processed on every push to the server, resulting in the file
      being dumped and fully re-loaded on every push
- [x] progress files should be labelled "race-name" on line 1 (same as history files) then on
      line 2 "progress as at <time>, # entries", remove the icon
- [x] races history line 2 should read "# entries from ..." not just "from..."
- [x] show last sync'd on line 3 for a history file (echoing what is shown in the file)

- [x] i did this sequence on the cubot: setup a race of unknwon-26-09-23 in time mode at the finish, 
      i started time mode, added a few split entries then reset it,
      those entries got sync'd to the server,
      i then setup the same race again in time mode at the finish as before,
      i started it and made some split entries,
      the latter race is not being sync'd,
      the phones history shows two identically named races,
      one marked active (but not being sync'd), the other not

## Concept gap

Progress is not getting to the phones from the web-app when its only connected via http to a
a server. The reason, I think, is because the phone are looking in the wrong place. They are
looking in the folder for the arbitrary initial name they gave the race, and its not there. 

- [x] once a device is 'adopted' in the web-app, that adoption needs to be fed back to phones
      so they pick up the progress, initially the phones have an arbitrary race name but once
      adopted the current race on the phones needs to update to the actual race name, that
      can happen via BT or via the server, the BT case is currently handled (but check) but
      the server case is not, the phones will be looking for a progress.json under their
      initial arbitrary name, the web-app knows what that arbitrary name is, so I suggest this:
  - the web-app for each adopted device writes a file to the folder the device is looking at
    with the name 'adopted-to-<race-name>.json', where <race-name> is the true name of the
    race
  - the web-continues to write progress.json as now in the true race folder
  - the mobile-app detects these 'adopted-to...' files and changes the race name of itself
    accordingly (ie. actually adopts the race)
  - (as built: one `adoptions.json` per folder, keyed by device name — many phones share the
    default `unknown-<date>` folder; the phone reads it via
    GET /api/mobile/<label>/adoption/<deviceName>)
        
- [x] a mule should be involved too, for this scenario: the mule is connected to the server
      over HTTP but not the web-app, other phones are not connected to the server but they
      are connected to the mule over bt.
  - in this scenario the mule (knowing the device name and race name of every phone it can see)
    can read the adopted-to... files on behalf of the device and relay it back to the phones
    (using the existing BT mechanism for doing that)

## Usability tweak to help with Robin's handicap race

I would like a proposal that allows a bibs mode phone to have a setting that specifies what the
event type is when 3 digits are entered, default is 'Finish'. That setting must be visually
obvious in the bibs mode screen and its options are start, finish, retire. A single press of
something must be all that is required to change the default, the option should not make the
screen bigger.
 - [x] done

## A rationalisation

Merge CP mode and Bibs mode and make the location have meaning. Currently, location is just an
arbitrary name, instead give it meaning, there are 3 locations: Start, Finish, CPn. When the
location is start the bibs mode sets the default action to start, when the location is finish
the bibs mode sets the default action as finish, when the location is CPn bibs mode sets the
default action as pass (a new action default between start and finish). CP mode is then
redundant and can be removed. So there are only 2 modes: bibs (location aware) and time (as now).
Treat any location that is not 'Start' or 'Finish' as a CP location and enforce its rules. Setting
a location is compulsory in race setup, its not allowed to be blank.
 - [x] done

## Another idea

Extend this app to recognise bluetooth beacons worn by runners.
Such trackers can be had for £3 each for 1 yr use (before battery dies), 
or £7 each for a 6 year battery life that is replaceable.
See Alibaba or examples.

### Implementation notes

Full design: [docs/beacon-detection-plan.md](docs/beacon-detection-plan.md) (on the
`beacon-tracking` branch) — includes architecture, code sketches, phased build-out, test plan,
and a "How to resume this later" section. The hardware question is resolved (DX-CP27 beacons,
programmed via Setup Beacons — see "Programming the DX-CP27 Bluetooth Beacon" below), and
phase 1 (parsing + distance estimation + the Identify Fob registration-desk screen) is now
implemented — see the plan doc's own 2026-11-05 updates. Phase 2 (`BeaconScanEngine`'s actual
auto-record-on-pass detection) is not yet built.

- [x] detection timed at peak signal strength (closest approach), not first/last-seen — the fob
      doubles as a timing device (photo-finish resolution), not just bib ID
- [x] use measured-power to estimate distance; enforce a max-range threshold, tighter at
      registration than during race detection
- [x] bib-beacon pairing happens at registration in the Racemaster web-app (not on the phone),
      broadcast to phones piggy-backed on the existing bib/name propagation system; each fob has
      a short-code known to the web-app, similar to the dibber number allocation scheme (see
      ~/racemaster)

## Use dedicated cheap phones rather than asking marshalls to use their own.

Can this app run on Samsumg XCover 4?
They can be had from ebay for ~£25 and have a replaceable battery.
Answer (via Claude) is yes.

Before buying a batch of these second-hand, run this checklist on one example unit. The main
risk is that `minSdk`/Android version doesn't guarantee BLE *peripheral* (advertising/GATT
server) support, which Mule Mode's hosting role (`PeripheralSyncService.kt`) needs - that's a
chipset capability, not an OS one.

- [x] confirm BLE peripheral/advertising hardware support (quick, no app needed):
      `adb shell dumpsys bluetooth_manager | grep -iE "state|address|le_audio|advertis|multiple"`
      and look for any "LE Advertising not supported" line (not fully reliable on its own, see next)
- [x] battery health: `adb shell dumpsys battery`, check `level`/`temperature`/`health` (want
      `GOOD`); also do a real check - charge to 100%, screen off, see how much it drops in an
      hour (check every unit received, not just this one - used 2017 batteries vary)
- [x] real end-to-end test with the actual app: `./gradlew installDebug` on the XCover 4 and a
      second device, then run one real Mule sync in each direction - XCover 4 as host/peripheral
      vs puller/central, and swap. Watch with:
      `adb logcat | grep -iE "mule|peripheral|gatt|advertis"`

## More tweaks

- [x] the 'back' mechanism is awkward, implement a means to directly move between screens
- [x] do the XCover 4 checks mentioned in lines 243-256 above
- [x] the XCover 4 is not keeping the screen on while the app is running
      and battery optimisation is staying on
- [x] the help system needs re-doing, nobody will read the current help essays,
      a more discoverable, hand-holding system is required
- [x] add a what's new section for every version to the mode picker help, outer heading is
      "What's New", that opens a list with a heading per version, newest first
- [x] when a bib number is recorded, the speak the number as 3 separate digits in a loud male or female voice,
      eg. for "123" speak: one two three, this needs to be a persistent option (it may not be
      practical on the finish line but will be at an on-course checkpoint)
- [x] shorten the app name in the title bar from 'RaceMaster Mobile' to 'RM Mobile'
- [x] add a start-up option to set the phone in kiosk mode if not in it, or to exit kiosk mode
      if it is, the objective is to lock things down so only this app can send/recieve data
      either as mobile, wifi or bt - dropped the idea, too risky on bricking the phone

## Programming the DX-CP27 Bluetooth Beacon

See the Python script and the research leading up to it in ~/dx-cp27-probing.

The real protocol is now confirmed (reverse-engineered from the DX-SMART app's own BLE traffic,
then validated against real hardware via `~/dx-cp27-probing/dxbeacon.py`) — service `0xffe0`,
a framed `"NO"+cmd+len+data+checksum` command protocol over `0xffe2`, notified replies on
`0xffe1`, password auth on `0xffe3`. Ported into the app at
`app/src/main/java/mobile/racemaster/data/beacon/DxCp27Protocol.kt`/`DxCp27Client.kt`, with
`DxCp27ProtocolTest.kt` asserting the port against the actual captured bytes. Options > Program
Beacons implements the list below; checkboxes get ticked once each is verified against real
hardware through that screen, not just implemented.

### The programming script in this app needs to be able setup the devices such that:

- [x] the operator needs to be shown a list of visible beacons and be able to select one
  - [x] the scan to provide a list of current frames the beacon is transmitting
  - [x] the scan should also estimate the transmit interval (interval estimate is shown per 
        frame type)
- [x] the password needs to be changed away from the default 'dx1234' if a probe discovers it's
      still set — to 'mercia'
- [x] the current configuration of the selected one needs to be shown, including its battery status
- [x] all slots except slot 1 need to be set to empty
- [x] slot 1 needs to be set as:
  - [x] BLE Name to be RM.nnn-pp where nnn is an operator entered number in range 0..999, 
        with leading 0's so its always 3 digits and pp is an RSSI@1m the sign being + or 1,
        the pp to be determined via a calibration facility
  - [x] the advertising interval needs to be set to 2 (200mS) — DX-SMART's own interval field is
        100-1500ms in 100ms steps (raw = ms / 100); dropped from the original 300mS (raw 3) on
        2026-11-05 once real-hardware testing showed Android's own BLE scan scheduling, not this
        beacon's transmit timing, bounds real-world detection latency — see
        docs/beacon-detection-plan.md's "2026-11-05 update"
  - [x] the tx-power needs to be set to -1dBm (raw index 4) — DX-SMART's own TX-power field is 8
        fixed, unevenly-spaced values (raw index -> dBm): 0=-19.5, 1=-13.5, 2=-7, 3=-3.5, 4=-1,
        5=+1, 6=+1.5, 7=+2.5; raised from the original -7dBm (raw 2) alongside the interval change
        above, same reasoning
  - [x] trigger to be set off

### Race-day usage

Reached from the Mode Picker's "Beacons" button, which now opens a small hub
(`ui/beaconops/BeaconOperationsScreen.kt`) with three buttons — Check, Close, Program — rather
than going straight to one screen; see docs/beacon-detection-plan.md's 2026-11-05 "Beacon
Operations hub" section.

- [x] during registration this app needs to be set in a 'check' mode that can verify that a
      beacon is switched on and that its id is that expected, when multiple devices are visible 
      a list should be shown in the order of highest power first (i.e. closest first),
      the operator needs to check the id matches the label on the beacon so the screen should
      ensure the id is shown as big as possible within the screen constraints of the phone
      — **done**: Beacons > Check (`IdentifyFobScreen`/`IdentifyFobViewModel`), field-verified
      against two real DX-CP27s side by side.
- [x] after the race is over this app needs to be in a 'finish' mode that shows the battery
      status and powers off the beacon and also logs what has been returned so we know who to
      chase that have not given their beacon back, this finish mode should be able to detect
      connect to, read the battery, power-off and record the battery level and date and
      send that to the racemaster app so it can record beacon usage in its beacon log, that
      log should record beacon id, last read battery level,
      there also needs to be finished/retired/disqualified flags, default finished, that the 
      user enters for each beacon scanned - this can be implied by the lack of a finish scan
      — **done**: Close is now a full Setup Race mode (`CloseModeScreen`/`CloseModeViewModel`,
      backed by `CloseModeRepository`), reached from Setup Race or the Beacons hub's own standing
      "Close" shortcut. Each close is an ordinary `CLOSE` history row (beacon + battery in its
      note) riding the existing device-sync pipeline, not a bespoke endpoint — picked up by
      `~racemaster`'s own device list/Update Progress like any other row. Still open: the
      finished/retired/disqualified flag idea above — unrelated to this, not yet started.
- [x] during the race when the app is in bibs mode it needs to detect a passing beacon as
      described elsewhere, BUT... to do that reliably the phone must NOT also be in mule mode
      (see the mitigations implemented for beacon programming around scanning)

## Bluetooth Beacon range estimation

A facility is required to get a handle on how the RSSI drops off with range with a view to
calculating more accurate distances. There are several contexts: 
  -  during registration inside a building with lots of people milling about and lots of 
        beacons in range.
  - at a CP out in thw wild, few people, no buildings, only a few beacons in range
  - at the finish line, lots of people, no buildings, lots of nearby beacons

This facility should be accessible as part of the programming system.

— **done**: Beacons > Range Calibration (`ui/beaconops/RangeCalibrationScreen.kt`) logs
(context, measured distance, beacon, RSSI stats, phone model) samples to a shareable CSV for
offline analysis — see docs/beacon-detection-plan.md's "Range calibration data collection"
section for the research behind it and candidate next steps. The three checkboxes above are
field-data-collection sessions, not code, and stay unchecked until someone's actually gone and
run one in each context.

— **done**: `BeaconScanDecoder.estimateDistanceMeters` swapped from the fixed-`n=2` formula to
AltBeacon's own empirical curve fit as an interim improvement — see docs/beacon-detection-plan.md's
"Interim formula swap" section. Still borrowed constants (not this app's own hardware), so the
three field sessions above remain the real fix.

## Update beacon plan

The beacon-detection-plan.md has got into a bit of a confusing mess, it needs rationalizing.
In outline the 'beacon plan' consists of:

- [x] ~racemaster web-app sends beacon numbers as well as bib numbers to the mobile app
- [x] mobile app programs the beacons
- [x] mobile app detects and records beacons passing checkpoints (time not critical)
      (bibs mode at a CP location)
- [x] at a checkpoint the detection algorithm is 'first seen'

## Close mode

- [x] a new mode is required to 'close' a beacon - **done**: Close is now a full Setup Race mode
      (`CloseModeScreen`/`CloseModeViewModel`), not just the beacons hub's own placeholder button
      — that button is kept as a standing shortcut to the same screen. A Close-mode race's own
      location is fixed to "Close" (same reasoning Time's is fixed to "Finish") so a dedicated
      close-station phone is identifiable in `~racemaster`'s own device list, since it never logs
      a Bib/Time entry of its own.
- [x] in this mode each beacon visible is shown in nearest first order, when selected
      after confirmation, the beacons battery level is read and then its turned off,
      the battery level and turn off time is logged for later transfer to ~racemaster for
      analysis (the transfer via bt or the server and analysis is already implemented there)
      — **done**: logged as an ordinary `CLOSE` history row (`CloseModeRepository`), synced
      to the server by the existing device-sync pipeline like any other row, not a bespoke
      endpoint. `~racemaster`'s own `progress.json` handling for these rows is still open
      (see docs/beacon-detection-plan.md's own Part E).
- [x] this mode needs to be aware of the issued beacons and show a list of outstanding ones
      in bib number order
- [x] need to be able to see the peer lines pulled from nearby devices in the history
      (as a diagnostic aid)

## Count mode

- [x] another new mode is required to count beacons before the start, race day practice is to
      count runners into a pen before the start to ensure they are all present, the objective
      of this mode is to note the bib number as the runners pass one or more of these phones
      as they walk into the pen, they will do this many at a time, the phones need to show a count
      of unique beacons seen and a list of bib numbers yet to be seen, only close range beacons
      should be counted — **done**: Count is a full Setup Race mode
      (`CountModeScreen`/`CountModeViewModel`), passive/no-tap, deduped by beacon number (not
      resolved bib — a beacon can be counted before its bib pairing is known), close-range-gated
      the same way Bibs' own checkpoint detection is. Its own location is fixed to "Pen" (same
      reasoning Close's is fixed to "Close") so a dedicated headcount phone is identifiable in
      `~racemaster`'s own device list. The Beacons hub also got its own standing "Count" shortcut
      to the same screen, mirroring Close's. Both Count and Close gained a top-bar "Stop" action
      in place of Relocate (clearing the race's own mode back to null via
      `RaceRepository.stopMode`) — the only way to ever leave either mode once set up, since
      neither has a Reset; it's also what unblocks Race History's delete action for that race,
      which was otherwise permanently guarded against deletion while `race.mode` stayed Count or
      Close (see `isRaceActive`'s own doc).
- [x] a means to eject one or all seen beacons is required to facilitate runners going away from
      the start and returning later (e.g they forgot their compulsory kit) or to re-start the count
      due to errors/confusion
- [x] when ejected the beacon must not be re-counted while it remains in range — **done**, revised
      per the "Ejection bugs/tweaks" section below: an ejected beacon stays excluded from the
      count indefinitely (no cooldown) until the operator explicitly un-ejects it via its own
      link in the 'to be seen' list.
- [x] allow for multiple count mode phones at the pen and sync between them, in particular if
      a beacon is ejected it must be flagged as such on all phones counting beacons, do this
      via bluetooth so its instant — **done**: builds on the existing same-mode peer-sync engine
      (`PeerSyncEngine`/`PeerSyncRepository`) — a beacon's own eject/uneject status is now merged
      across this device's own rows and a same-group peer's own reported rows
      (`CountModeRepository.mergedStatusByBeacon`, ordered by wall-clock timestamp since each
      device's own `lineNumber` sequence starts over at 1 and isn't comparable across devices),
      so an eject on one phone shows as EJECTED on every other same-race/same-mode phone within
      one `PeerSyncEngine` pull tick, over Bluetooth, with no server round-trip.
- [x] a bib must be ejectable no matter what phone did the initial detection — **done**:
      `CountModeViewModel.ejectBeacon`/`unejectBeacon` now gate on the peer-merged union/status,
      not just this device's own local rows, and `CountModeRepository.recordEject`/`recordUneject`
      take an `allowWithoutLocalRecord` flag so a beacon only ever counted by a peer can still be
      ejected (and a peer's own eject un-ejected) from this phone, writing a plain
      non-fold-targeting row with the race-wide beacon→bib mapping standing in for the bib number
      a local row would otherwise have supplied.
- [x] detect and rectify two count phones going out of sync with each other about beacons
      seen/ejected (eg. after one wanders out of BLE range and back), keeping the normal
      inter-change delta based — **done**: `DeviceInfo.countStatusDigest` carries a cheap content
      fingerprint of a device's own whole-race Count Mode history
      (`CountModeRepository.countStatusDigest`/`countStatusDigestForPeerRows`, a hash over every
      COUNT_SEEN/COUNT_EJECTED/COUNT_UNEJECTED row's own lineNumber/action/note); `PeerSyncEngine`
      compares a peer's self-reported digest against this device's own already-merged mirror of
      it on every pull tick, even once the ordinary `since`/`lastMergedLineNumber` delta cursor
      has already caught all the way up to that peer's `lastLineNumber` — a mismatch at that
      point means real drift, not "haven't caught up yet". On a mismatch it calls the
      already-existing `PeerSyncRepository.forgetOrigin` (previously unused by anything) to wipe
      this device's mirror of that one peer and re-pulls everything from scratch, safe and
      idempotent per that function's own doc — the everyday path is untouched, still a plain
      delta pull.
- [x] count mode lines need to follow the red->orange->green colour scheme to reflect not sync'd
      picked up by a mule, sync'd to the server/sink — done: the "Seen" list's own local rows
      (SeenBeaconUi.syncState) now use the same lineSyncState/rowColorFor Bibs/Time already use;
      a peer-sourced row has no local sync lifecycle of its own, so it stays uncoloured
- [x] add an "Ejected" heading above the ejected beacons list
- [x] add a "Not Seen" heading below the "n unique beacons seen" line and above the
      "None - every issued beacon has been seen" line — done: the old combined outstanding list
      is now two sections (Ejected — only shown when non-empty — then Not Seen, always shown)
- [x] the wording of "0 of 5 yet to be seen" is confusing, say something more specific when all
      have been seen — done: "All N beacons have been seen" once outstanding is empty
- [x] CountUnejected lines should contain the bib number and ref line to the ejection (if it
      is local) — done: CountModeRepository.recordUneject now looks up the most recent local
      COUNT_EJECTED row for the beacon (if any) and carries its bibNumber/lineNumber onto the
      COUNT_UNEJECTED row as bibNumber/refLineNumber; Race History already renders refLineNumber
      generically as "Edited from L{n}", no further UI wiring needed
- [x] move the peer activity view from the count mode screen to the history screen, and add a
      delete facility (so forces a re-sync), but leave a feedback line on the count mode screen
      on the number of lines received — done, then revised: rather than an embedded section on
      RaceHistoryDetailScreen, each same-group peer this device has ever merged records for is
      now its own "Peers" list item on the Races page (one per device, full race name in its
      title, same red trash-can delete as every other item there — see
      PeerSyncRepository.deleteGroupOrigin, which clears just that one origin's rows and resets
      its own merge cursor), tapping through to a small read-only detail screen
      (PeerActivityDetailScreen) for the raw lines; CountModeScreen keeps its one-line "Peer
      activity: N lines received" count, now shown directly under the "Nearby: ..." line in the
      same style (see RaceProgressSummary)
- [x] count mode lines need to follow the red->orange->green colour scheme to reflect not sync'd
      picked up by a mule, sync'd to the server/sink: extended to the "Ejected"/"Not Seen" list
      too, which previously coloured an ejected beacon flat red while visible (now the same
      sync-status colour the "Seen" list uses — see OutstandingBeaconUi.syncState — with the
      "currently visible, ready to un-eject" cue kept as bold text instead of colour)
- [x] label Race History's list items as Race/Progress/Peers with a small hand-drawn icon (not
      from the Material icons library — see ui/components/ItemTypeBadge.kt) and echo each item's
      full race name/label in its title, including the Progress detail screen's own title
- [x] the Progress detail screen's own per-bib rows should match the rest of the app's history
      rows: one compact line per bib instead of a stacked multi-line ListItem card — see
      ProgressDetailScreen.kt's ProgressEntryRow, now a single Row reusing
      ui/components/HistoryRowStyle.kt's historyRowModifier

# Tweaks/Bugs

- [x] use location 'Pen' for count mode — **done**: `RaceRepository.recordModeStart`'s
      `effectiveLocation` for `AppMode.COUNT` is now `"Pen"`, not `"Start"`.
- [x] in the history view collapse consecutive ping lines in the same state (eg. all sync'd) into 
      one with "Lnnn - Lmmm" in the line number column where nnn is the first line and mmm is the 
      last and "hh:ss - hh:ss" in the time column to show the time span of the collapse — **done**:
      `collapseConsecutivePings` (`ui/components/HistoryLineRow.kt`), applied inside `historyLines`
      so both Race History's own detail screen and Mule source detail get it automatically. A run
      of two-or-more consecutive Ping rows sharing the same sync-state colour collapses into one
      row; any other row, or a state change, breaks the run. Used the existing time column's own
      "HH:mm" format for both ends of the span rather than "hh:ss" literally (no seconds are
      otherwise shown anywhere in this view).
- [x] attempting to delete a history file when the race is active in close mode, it offers to
      reset the mode, but selecting it does not do it — **done**: `RaceRepository
      .forceResetActiveModes` only ever checked `timeModeStartedAtMillis`/`bibsModeStartedAtMillis`
      — Count/Close have neither (see `isRaceActive`'s own doc), so the dialog's "Reset" button
      silently no-opped for either. Now also calls `stopMode` when `race.mode` is Count or Close.
- [x] count mode should not do anything if there are no beacon expectations — **done**:
      `CountModeViewModel.handleBeacon` now returns immediately when `bibByBeaconNumber` is empty
      (progress.json hasn't synced down an issued-beacon list for this race yet, or the course
      isn't using Beacons at all), instead of debounce-tracking and recording every nearby beacon
      as a `COUNT_SEEN` row against a total of 0 with a null bib.
- [x] the count screen needs a reset option, so it can be restarted, a reset does a stop first
      and the screen must exit (so as to not immediately start counting again), this must be
      treated like any other reset by the web-app — **done**: `CountModeViewModel.resetCountMode`
      does what Stop does (writing `COUNT_STOPPED` first if beacons are still outstanding, then
      clearing the race's mode), then additionally calls the same generic
      `RaceRepository.closeCurrentSegment` every other mode's own Reset already writes a `RESET`
      row through — the web app's `buildSegmentView()`/`currentSegment()` already treats any
      mode's `Reset` row this way, so no server-side change was needed. `CountModeRepository
      .observeCounts`/`recordCount` are now scoped to the current (not-yet-reset) segment via
      `HistoryFold.currentSegmentRows`, so a beacon seen before a Reset is counted again once
      Count is restarted. `CountModeScreen` gained a body-level Reset button (the shared
      `ResetButton` composable) that calls `onHome()` right after, so the still-running scan loop
      can't immediately re-record the beacons just reset.
- [x] the shortcut from the beacon hub to the count screen should be replaced by a dialog that
      directs the user to setup count mode from the race setup screen, leave the close shortcut
      as is (this may be required outside a race context) — **done**: `BeaconOperationsScreen`'s
      "Count" button now shows an explanatory `AlertDialog` ("Count only makes sense for a
      race's own issued-beacon list...") with a "Go to Setup Race" action instead of navigating
      straight to `CountModeScreen`; "Close" is untouched, still a direct shortcut
      (`Routes.CLOSE_BEACONS`). Removed the now-unreachable `Routes.COUNT_BEACONS` route/
      composable and the `onCount` nav callback, replacing it with a plain `onSetupRace`
      callback the dialog's action button uses.
- [x] when speaking bib numbers that arrive on mass later ones truncate earlier ones, can they be
      queued so all are fully spoken (racemaster's own ToDo.MD) — **done**: `BibSpeaker.speakNow`
      called `tts.speak()` with `QUEUE_FLUSH`, which cuts off whatever's currently being read the
      moment the next bib commits — both `BibsModeViewModel`'s own rapid manual entry and
      `BeaconScanEngine`'s own automatic checkpoint detections (several runners passing close
      together) can genuinely out-pace one utterance's reading time. Switched to `QUEUE_ADD` so
      every bib is read in full, in arrival order, instead of truncating whatever was still playing.
- [x] when relocating in bibs mode from cp1 to cp2 the "start @cp2" screen was shown but beacons
      already visible there where announced before start was selected, that is actually correct,
      but what is not is the start screen being shown, it should go straight into bibs mode
      screen, this only applies when relocating, the start screen should still be shown for the
      initial location — **done**: `BibsModeViewModel`'s own location-change hydration now
      auto-writes the real Clock marker (`startBibsMode`) the moment a segment opens with its own
      fresh LOCATION row and no Clock yet, but only once this race's Bibs mode has had more than
      one visit ever (`EntryLogModeEngine.observeHasMultipleVisitsEver`, keyed off LOCATION-row
      count rather than `bibsModeStartedAtMillis`, which Reset clears back to null and so can't
      tell "never started" apart from "started before, now reset" on its own) — so a genuine
      Relocate goes straight into the keypad, while the race's first-ever Bibs setup still shows
      the manual Start button.

## Ejection bugs/tweaks

- [x] the seen list is too sparse vertically, tighten it up — **done**: the Count Mode list's own
      `verticalArrangement` dropped from 8dp to 2dp; section headers ("Seen") carry their own
      explicit top padding instead so sections still read as separated.
- [x] use the refLineNumber system when ejecting in a similar manner to Undo Last in bibs mode —
      **done**: `CountModeRepository.recordEject` writes `HistoryAction.COUNT_EJECTED` with
      `refLineNumber` pointing at the beacon's own `COUNT_SEEN` row, the exact shape
      `EntryLogModeEngine.undoMostRecent` already uses; `currentSegment`'s own fold now treats
      `COUNT_EJECTED` as an undo-marker alongside `UNDO`, so the targeted row's whole group drops
      out of the live counted set automatically (`HistoryFold.foldLatestVisible`), replacing the
      previous bespoke "latest row by lineNumber" comparison.
- [x] use a labelled link on the seen list 'Eject' rather than the ^ icon — **done**.
- [x] selecting eject shows a confusing dialog - i would expect just an eject yes/no confirm —
      **done**: a plain "Eject beacon NNN? Yes/No", no extra explanatory text.
- [x] a beacon ejected should appear in the 'to be seen' list with an 'ejected' link — **done**:
      tapping it calls `CountModeViewModel.unejectBeacon`.
- [x] an ejected beacon that is visible should be highlighted in the 'to be seen' list — **done**:
      a separate, purely cosmetic live-presence signal (`lastDetectedAtMillis`, a periodic ticker,
      nothing to do with detection/counting gating) bolds and colors the row while true.
- [x] selecting the ejected link should un-eject it so it becomes a normal to-be-seen
      beacon but should remain tagged as 'was-ejected' until its re-seen — **done**: writes a new
      `HistoryAction.COUNT_UNEJECTED` status marker (not another refLineNumber/undo row — see its
      own doc); `CountModeRepository.statusByBeacon` reads each beacon's own most recent
      SEEN/EJECTED/UNEJECTED row (from an unfolded, current-segment-scoped read —
      `observeStatusByBeacon`/`currentSegmentRaw` — since the live counted-set fold above
      deliberately hides the eject/uneject rows themselves) to decide EJECTED vs WAS_EJECTED vs
      (absent) plain outstanding.
- [x] the above replaces the cooldown idea, drop it and its constant — **done**: `commitDetection`
      now just checks the beacon's own status is not EJECTED; `RECOUNT_COOLDOWN_MILLIS`,
      `PROMPT_SNOOZE_MILLIS`, the recount confirm/deny dialog, and all their supporting state are
      gone.
- [x] drop the Eject All link, its redundant now there is a RESET option — **done**.

## Factor out all the BLe stuff so there is a single source of truth

- [x] there should be a single thread responsible to bt tx and another for rx, with a queueing
      system to drive them, so then not possible to get advert race conditions and the threads
      can easily serialise operations with appropriate time gaps
- [x] refreshing the advertised payload (lastLineNumber etc.) currently tears the whole
      advertising set down and brings up a new one (stopAdvertising + startAdvertising), which
      gets issued a brand new random BLE address immediately rather than waiting for the normal
      ~10+ minute rotation — confirmed live via raw Bluetooth stack logs. The coalescing fix
      only reduces how often this happens; it doesn't stop a device's address from still
      changing on every restart. BluetoothLeAdvertiser.startAdvertisingSet/AdvertisingSet.
      setAdvertisingData (Android 8+) can update the broadcast payload without a full teardown,
      which would avoid the address churn at the source — bigger change (new API surface,
      minSdk 24 compatibility to keep working on older devices), worth doing separately —
      done: PeripheralSyncService now starts advertising via startAdvertisingSet (legacyMode)
      on API 26+ (every real device this app ships to) and updates its payload in place via
      AdvertisingSet.setAdvertisingData/setScanResponseData instead of stop+restart, falling
      back to the old legacy stop+restart path on API 24/25 or if a chipset's BLE controller
      ever reports ADVERTISE_FAILED_FEATURE_UNSUPPORTED for the advertising-set path
- [x] PeripheralSyncService's self-healing (handleEnsureUp) only retries advertising when
      gattServer is null or isAdvertising is false, and isAdvertising only ever flips false
      inside AdvertiseCallback.onStartFailure or an explicit stopAdvertising() call — so a
      native BLE stack stall that never calls back at all (neither onStartSuccess nor
      onStartFailure) leaves isAdvertising stuck stale at true forever, invisible to every
      retry/warning path that exists today (including BluetoothStateRepository's "5
      consecutive failures -> restart the phone" warning, since that also needs a reported
      failure to count). Suspected live: on the beacon-tracking field test, one phone's
      advertiser went fully dark — stopped being seen by its peer entirely — coincident with a
      cluster of status=133 connection collisions, and only recovered after a force-stop +
      relaunch, not via any self-healing; could not be conclusively confirmed via logs (logcat
      ring-buffer eviction under heavy ambient BLE scan traffic). Add a callback-independent
      liveness check: after issuing startAdvertising(), if neither onStartSuccess nor
      onStartFailure has fired within a few seconds, treat it as failed (log it, feed
      recordAdvertisingFailure(), and let the existing retry loop take it from there) instead
      of trusting the stale isAdvertising flag indefinitely — done: startAdvertisingAttempt now
      wraps both the legacy and advertising-set start paths in a bounded wait
      (ADVERTISING_START_LIVENESS_TIMEOUT, 5s) on a pendingAdvertisingStart CompletableDeferred
      that either callback completes; a timeout is now treated exactly like a reported failure

# ToDo

## More Tweaks

- [ ] amalgamate Bibs, Count and Close modes into a single Bibs mode that is sensitive to location,
      the location repertoire is: pen, start, CPn, finish, close and the location implies the mode,
      pen==count, start==start, CPn==cp, finish==finish,
      the 'relocate' link in bibs mode should be renamed 'move' and allow moving to/from any of
      these locations and their implied modes, the location drop-down in setup race should include
      'Pen'
- [x] when setup race completes and the user set a mode/location, go straight to that mode when
      the screen exits
- [ ] implement the nearby peer sync mechanism for all modes except close
- [ ] add the race name as a feedback line to the setup device screen
- [ ] need a battery health check on the phone - one of the XCover4 is a bit dodgy

## Factor out all the BLe stuff so there is a single source of truth

- [ ] Mule mode uses BLE to transport data when there is no mobile signal or no wifi,
      Other modes use BLE to detect nearby peers and to update the mule,
      Document how all this hangs together so a niaive code reader can work out what is going on

## Count mode

- [ ] a beacon that has not been counted should not be expected to be seen on-course anywhere,
      this only applies when the timing method is beacons, so the mobile app needs to know the
      race timing method being used - that is now a field in progress.json put there by the
      web app, beacons not counted can be determined from the progress.json file
- [ ] bibs retired at CPn should not be expected to be seen at CPn+ or the finish, but should
      be expected at the close station, this is irrespective of the timing method

## Start line

- [ ] mobile app needs to be able to record the mass start time as well as individual start times
      (bibs mode at a start location)
- [ ] at the start location the mass start time is a manual action
- [ ] at the start location an individual start time is also a manual action
- [ ] this is the same as the current bibs mode at the start location with the addition that it
        knows the runners beacon number, the operator needs feedback that the beacon is visible
        for the runner being started, it not being visible could mean the runner is not wearing
        the beacon and if so should not be allowed to start
- [ ] when bibs mode is at the start location the operator needs to be able to 'prepare' a
        bib for starting by entering it's number, but it must not be committed until the operator
        confirms it, this is so the actual start time can be recorded with sub-second accuracy
- [ ] also, the operator needs to be able to 'prepare' for the mass start - by selecting the
        'seniors' event - but it must not be committed until the operator confirms it, for the
        same reason as above
- [ ] the confirm action should be available by volume up/down, et al, like time mode
- [ ] this behaviour change needs to be gated against an option in Setup Race - "Using beacons"

## Finish line

- [ ] mobile app detects, records and times beacons crossing the finish line
      (bibs mode at the finish location)
- [ ] timing is critical so two co-ordinated phones are required
- [ ] finish time needs to be set when the strongest signal is seen (on either phone)
- [ ] runners will mill about in-range after finishing so a means of knowing they have finished
        is required - how? the finish line will typically be 3 metres wide, the finish funnel
        typically 5 metres long, with a phone either side of the finish line the runner will pass
        within 1.5 metres of a phone, 3 times nearer than the end of the finish funnel, this
        should create a characteristic and detectable power curve (bell curve?), but false local
        peaks need to be allowed for

## Sweep

- [ ] whenever seen, beacon number 000 is a sweep, a sweep is a runner that follows on behind the
      pack for the purposes of ensuring nobody goes off-course, they do not count as competitors,
      they are a safety feature, so count mode should ignore them, other modes should record them
      but not give them a split number, their nominal bib number is always 000
      — **count mode's own half done**: `CountModeViewModel` ignores beacon 000 outright. The
      "other modes should record but not split-number it" half is still open — unrelated to the
      Count/Close mode rework, a separate item.

## Some issues with the Trigs race

- [ ] some CP's are visited twice (railway bridge and road crossing there)
- [ ] some CP's are unmanned (railway bridge)
- [ ] need to mimic SI's ability to have a time allowance between CP's (that road crossing)
      (that's a web-app issue - see ToDo.md there)

## Samsung XCover 4 setup

Need a script/checklist of everything that can be turned off to 'lock-down' the phone to be as
specific as possible to just running this app. In particular to minimise network and BLE traffic
and minimise battery drain from 'irrelevant' services.

## Network issue question

In the context of a weak mobile signal where connections can drop out, how is a re-send handled?
If a message is long the probability of a drop-out is much higher than for a short message.
Should we mitigate for this by breaking up long messages into shorter ones that can be
re-constructed at the destination as pieces arrive? Or is this auto by the TCP/IP protocol?

## Do a range calibration test (when got new beacons)

- [ ] indoors, cluttered
- [ ] outdoors, open space
- [ ] use results to work out a better range estimation algorithm
- [ ] do tests an XCover 4 and 7

## Update help

- [ ] The Claude.AI generated help is confusing, re-do it manually.

## Location Maps

Modify all modes to relay their physical location, via GPS, back to the server/racemaster app.
Modify the racemaster web-app to show mobile locations on a map tagging the location with the
location code reported by the phone. The best place for the map is another tab on the Mobile
Files page. Ideally an OS map if available for free, else the best OpenStreetMap variant.

## Beacon options

Buy bare DX-CP27 beacons, plus 25mm wide hook-and-loop velcro cable strap and use a clear 
heat-shrink tube (30mm flat) over the strap and beacon. Rinse in a bucket of soapy water 
after use and air dry.

Velcro straps can be bought in 10m rolls by 25mm wide with 50 buckles for ~£14 from Amazon.
Each beacon would need ~300mm (to allow for folding both ends around the buckle), so ~40p per band.

Heat-shrink clear 30mm tubing can be bought in 5m rolls for ~£9 from Amazon, ~60mm per beacon
so ~12p per band.

No cost advantage over the 'other' beacons on order (£3.50 each).

## Mule mode tweak

To facilitate phone to browser connection over (dongle provided) WiFi, implement a small server
on the phone that the web-app can connect to. Do this in preference to a bluetooth connection
from the browser (as that is not universally available). This is two-way, so the web-app can
send data to the phone via its server and the other modes in the phone can pick it up from there.

In fact, make this local server always the way other modes send/receive data, and when the phone
is online it just relays to/from the remote server from/to the local one. The web-browser
bluetooth stuff can then be dropped.

An auto way to detect the phone when it connects to the dongle Wifi would be nice,
if not it can show its address and that be entered into the web-app manually.
