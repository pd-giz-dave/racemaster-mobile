# ToDo

## Setup Race screen

- [ ] the race name field as entered or fetched or adopted should persist and should always
      reflect what the device is actually recording
- [ ] the race name field should follow the same style as location, specifically:
      prompt should be in the box not the field, also the field should default to 'unknown'
      as a hint to the web-app that it has not been adopted
- [ ] the location field entered/selected should also persist
- [ ] add a prompt for the mode: time, bibs, cp - also persisted
- [ ] location validation against mode should be performed here,
- [ ] both mode and location can be changed via the 'Relocate' option, a relocate change is
      as now - add a LOCATION record (as of 20/9/26 edits not documented here) but also add a
      ModeStart record 
- [ ] with the mode now being defined here the 'setup' special record is no longer required
      the ModeStart record will suffice, so drop the 'setup' record
- [ ] for a new race a LOCATION marker should be inserted so all location segments are identically
      structured including the first one, so a segment is LOCATION + any number of ModeStart
      records, each followed by any number of bib/split records, in BNF:
        records ::= {segment}
        segment ::= {modes}
        modes   ::= Location ModeStart {bib | split}
      in this scheme the ModeStart record does not need the location, location is purely defined
      by the location record
- [ ] given the above, in the ModeStart record, instead of implying the mode from the bibNumber 
      and splitTime field, explicitly state it in the note field and leave bibNumber and splitNumber
      fields as null
- [ ] once a race is setup the Location and ModeStart records should be written and the device 
      should start advertising itself (and not wait for a 'start')
- [ ] change the "Create" button name to "Save" with a hint under it that it also makes the
      device visible to the web-app

## Mode picker screen

- [ ] mode is now setup in race setup form, so Time Mode/Bibs Mode/CP Mode buttons collapse to
      "Start <mode>" - where <mode> is as setup race, disabled before any mode selected
- [ ] "Start <mode>" now goes straight to the mode screen, no intervening start screen and no
      writing of a ModeStart record (that is now done by Setup Race or Relocate)
- [ ] the setup feedback that was on the start screens now appears on the picker screen in place
      of the current "Select device mode" text

## Mode screens

- [ ] rename 'Mode' to 'Back'

## All screens

- [ ] change the "Cancel" link to "Back" and move it to the right of the screen title
- [ ] make the same change to all screens that have a "Cancel"

## Re-work history files?

Instead of a separate file for each race+date have just one per race.
Drop the date suffix both here and in the mobile files stored on the server.
The data there is only relevant on race day, so when the same race is re-run next year it can
just overwrite it. Users can choose to add a date suffix if they wish on the race name as they
now already do for the course (e.g lmv-2026, brieddens-seniors-2026, brieddens-juniors-2026).
The web-app can extract the date from the timestamps in the history file and echo that as an
indication to the web-app operator that they are looking at a current file and not a historical
one.
