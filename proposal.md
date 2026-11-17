# An SI Replacement Proposal

Our SI licence expires on Jan 1st 2028 (just over a year from now).
To renew for one year costs £205, for five (as we did before) costs £820 (including VAT).
I propose to replace it with a Bluetooth beacon system.

## The hardawre

### SI

- Runner wrist/finger worn dibbers (180 off, £30 each)
- On-course dib stations (12 off, £105 each)
- Programming dib stations (2 off, £107 each)
- Master clear station (1 off, £162 each - never used, dunno why we've got this)
- Programming ferrite rod (1 off, £6)
- Two specialist dibbers (Service Off, Clear backup, £12 each - never used, dunno why we've got these)
- Wrist bands (consumable, one per runner per event, ~1000 per year, ~£15 per year)
- Glass fibre stakes (12 off, £15 each)
- Windoze laptop to run the SI Timing software (£nnn per year licence fee) and the 'Racemaster'
  software (free)
- Epson TM-T20 thermal printer (1 off, £192)
- Thermal paper (consumable, ~£1 per roll, ~10 per year)

### Beacons

- Runner wrist worn Bluetooth beacon (£3 each, battery lifetime estimate - 3 years)
- On-course - Samsung XCover 4 smartphones (~£20 each, obsolete but readily available second 
  hand on eBay, has replaceable battery, ~£7)
- Any laptop to run the 'Racemaster' software (free), does not need to be Windoze, can be anything
  that can run a modern web browser
- (Optional) battery operated thermal printer (£50)

## The software

### SI

- SI Timing system (annual licence fee)
- 'Racemaster' reg system (free)

### Beacons

- 'Racemaster' reg system (free - written by me, been in use for nearly a year now)
- 'Racemaster - Mobile' Android smartphone app (free - written by me, not (quite) ready yet)

## Operational usage

### SI

- Preparation
    - Each race has to be configured in the SI Timing software.
    - Each dib station has to be prepared for each race.
    - Each dibber has to be prepared for each race.
    - Each race has also to be configured in the 'Racemaster' system 
- Registration
    - Dibber checked for operation
    - Runner issued a dibber and a wristband and a number bib
    - Runner registration recorded in 'Racemaster'
    - When registration closes:
      - registrations are transferred from 'Racemaster' to SI Timing
      - check station is uploaded to SI Timing
      - SI Timing software has to be setup to receive downloads
- Race start procedure
      - When the race starts SI Timing has to be updated with actual race start time
- On-course
    - Dib station and stake issued to marshalls (marshall deploys at CP)
    - Dib station deployed at the finish
    - Runner has to stop and 'dib' into each checkpoint station
    - Runner has to 'dib' at the finish
    - Runner has to 'dib' at the download station (which may be remote from the finish)
- Post-race
    - Downloads are transferred from SI Timing to 'Racemaster'
    - Prize list is printed
    - Results published
  
### Beacons

- Preparation
    - Each race has to be configured in the 'Racemaster' system
    - The smartphone batteries would need to be charged up.
- Registration
    - Beacon switched on and checked working via a 'check' smartphone
    - Runner issued with a beacon and a bib number
    - Runner registration recorded in 'Racemaster'
- Race start procedure
    - Race start recorded on a smartphone (it is auto sync'd to all other phones and to Racemaster)
- On-course
    - Smartphone issued to marshalls - they just put in their pocket and forget about it
    - Smartphones (2) issued to finish marshalls, one either side of the finish line
    - Runners pass a checkpoint at full speed:
        - their number is called out by the smartphone,
        - their passing and their time are auto-forwarded to the Racemaster server
        - the Racemaster system auto logs their passing in real-time
    - Runners cross the finish line at full speed:
        - their number is called out by the smartphones at the finish,
        - their passing and their time are auto-forwarded to the Racemaster server
        - the Racemaster system auto logs their finishing and updates the results
- Post-race
    - Prize list is printed
    - Results are published

## Benefits - pros

### SI

- We already have all the hardware (for up to 180 runners per event)
- Runners get a splits print-out as they finish (but finish order is inaccurate)

### Beacons

- Runners do not have to stop or slow down as they pass checkpoints or the finish
- Far less pre-race setup required
- Far fewer race-day operations required
- Real-time feedback of where runners are
- Automatic results production, no manual actions
- Cheaper to extend runner limit (£3 per extra vs £30)
- All the software is open-source so can be modified to suit experience/cirucmstances (e.g the
  recent addition of a 'handicap' mode to Racemaster)

## Benefits - cons

### SI

- The SI Timing software is a closed proprietary system that we cannot tweak
- The SI Timing software requires an annual licence (£820 for 5 years)
- The pre-race setup is a pita
- Runners have to stop to dib at checkpoints and the finish (compromising slow dibs)
- Consumables have to be periodically replenished (paper, wristbands, dib station batteries)

### Beacons

- We would need to buy 180 beacons (£540) and 12 smartphones (£240) to be SI equivalent.
- Beacons have sealed batteries and have to be scrapped when exhausted (estimated every 3 years)
- There are replaceable battery alternatives, but they are £7 each and the batteries are £2 each.

## Start-up costs

### SI

- Nothing

### Beacons

- 180 beacons + 12 smartphones - £780
- 12 data SIM cards - £60 activation fee

## Running Costs

### SI

- SI Timing licence - £164 per year (if we buy 5 years at a time)
- Consumables - wrist bands, paper - ~$25 per year

### Beacons

- 180 beacons every 3 years after the first 3 years - £180 per year
- 12 smartphone batteries every 10 years (?) - ~£9 per year 
- Things Mobile data usage - up to £72/year (there is some doubt here, it could be much less)

## Recommendation

Run a small test race using the beacons to prove it works.
Assuming it does: sell the SI equipment, buy sufficient beacons (180?) and smartphones (12?)
Run all races using beacons.

## Onward development

It would be a simple extension for the smartphones to report their actual location via GPS.
The Racemaster system could then draw a map of where they are and also allow for 'dot watching'
of runners as they pass checkpoints. Knowing the marshalls are in the correct place would help
the RO to direct them if they are off course. The system could also track where the sweepers are
(they would have a special beacon that identifies the wearer as a sweeper).
