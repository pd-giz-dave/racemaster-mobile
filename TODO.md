# Racemaster Mobile — Implementation TODO

## Phase 1 — Data layer, Time mode, Bibs mode

done

## Phase 2 — Bibs Mode rework: legal bib range, duplicate flagging, unified Event/Log, editable rows

done

## Phase 3 — Mule Mode Phase 1: BLE pickup from Time/Bibs phones + internet sync

done

## Random ToDo's

- [ ] treat 404 as an expected response when ping'ing or post/put a server that is not a race master server
      and just invalidate the server (with app header feedback), put keep re-trying
- [ ] Update readme.md and structure.md
- [ ] add a location name field to race details, default is "Finish"

## Bugs


## Later phases (not started)

- [ ] Mule-to-mule "chain home" relay: multi-hop store-and-forward between mule devices
  (stable dedup, loop prevention) so mules can pass data to each other, not just pull from
  Time/Bibs phones and push to the internet
- [ ] BLE SYNC receiver: either a Web-Bluetooth page in the racemaster web app (Chrome/Edge
  only — browsers can't act as a BLE peripheral, so the phone would have to be the
  peripheral and the browser the central, with an unavoidable manual "Connect" click) or a
  separate small receiver app writing a file for later import (racemaster's own `ToDo.MD`
  already lists CSV import as planned separately, so this could piggyback on that)
