# ToDo

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
      When the first segment is reset - the phone reverts to no race setup (clears the persisted
      race setup fields) and it stops advertising itself and stops updating the web.
      This means an Undo across segment boundaries is not necessary, just reset the segment.

## Tweaks from observations of using the app

- [ ] races history is showing multiple (self) lists for the same race
- [ ] races history is showing multiple progress lists for the same race
     (maybe due to coming via different sources?)
- [ ] if a self history file is deleted, delete it on the web too and tell any connected mules
      to dump it as well (ie. ensure it gets flushed from everywhere, mule should also tell the
      web-app its gone and remove it from the server, else it keeps coming back
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
- [ ] bibs mode not being syn'c to server until a ping comes along (the phone showing this is
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
