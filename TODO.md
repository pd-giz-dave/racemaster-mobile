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
- [ ] all fields are editable after creation provided the current mode has stopped, 
- [ ] with the mode now being defined here the 'setup' special marker is no longer required
      the usual mode start line will suffice
- [ ] once a race is setup the ModeStart record should be written and the device should start 
      advertising itself (and not wait for a 'start')
- [ ] change the "Create" button name to "Save" with a hint under it that it also makes the
      device visible to the web-app
- [ ] the ModeStart record for time mode should have n/a as the time not 00:00:00
- [ ] drop the 'Setup' action, its no longer needed as there will always be a ModeStart

## Mode picker screen

- [ ] mode is now setup in race setup form, so Time Mode/Bibs Mode/CP Mode buttons collapse to
      "Start <mode>" - where <mode> is as setup race, disabled before any mode selected
- [ ] "Start <mode>" now goes straight to the mode screen, no intervening start screen and no
      writing of a ModeStart record (that is now done by Setup Race)
- [ ] the setup feedback that was on the start screens now appears on the picker screen in place
      of the current "Select device mode" text

## Mode screens

- [ ] remove 'This Race'
- [ ] rename 'Mode' to 'Back'

## All screens

- [ ] change the "Cancel" link to "Back" and move it to the right of the screen title
- [ ] make the same change to all screens that have a "Cancel"

## Re-work location

Once a phone starts recording, location is constant so does not need to be echoed in every history
line. Instead, implement a scheme where it is communicated once per mode setup - in the note field.
A side benefit is payload size reduction. So a ModeStart line populates bibNumber (bibs mode)
or splitTime (time mode) with 'n/a' and note with location.

## Re-work history files

Instead of a separate file for each race+date have just one per race.
Drop the date suffix both here and in the mobile files stored on the server.
The data there is only relevant on race day, so when the same race is re-run next year it can
just overwrite it. Users can choose to add a date suffix if they wish on the race name as they
now already do for the course (e.g lmv-2026, brieddens-seniors-2026, brieddens-juniors-2026).
The web-app can extract the date from the timestamps in the history file and echo that as an
indication to the web-app operator that they are looking at a current file and not a historical
one.

## Re-think adoption

The web-app gets a list of device names that are in the field and visible now.
The web-app operator needs to know which of those are relevant to the current race, where they are
on the course and what they are going to record. The web-app operator can then adopt the relevant
phones and tell them the true race id. The adopted phones then deposit their history in the
appropriate folder on the server and/or to a BT mule.

The phones initially have a placeholder race id - some meaningful abbreviation - the initial 
history file is populated with a ModStart record that indicates its mode and location. That record 
is deposited on the server as mobile/owner/race-id/location/device-name.json and/or propagated via 
BT to a mule as just race-id/location/device-name.json

The web-app asks the server for a list of any files in /mobile/*/*/*.json that have a timestamp
of today. The web-app also needs an equivalent list via a BT mule.
The web-app operator cherry-picks from this list. The adoption record with the true race name is
propagated back from the web-app to the phones either via BT or the server.

Hmmm... getting in a pickle again

## A proposal

What about appending the location to the device name?
That will naturally separate things if a phone moves.