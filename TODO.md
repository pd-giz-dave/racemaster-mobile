# Racemaster Mobile — Implementation TODO

## General

- [ ] remove the course field from race details, a race id becomes just its name and date
- [ ] when entering a bib that is out of range instead of saying "Cannot log that" and disallowing it
      allow it but tag it as an error in a similar way to entering a duplicate,
      show the error as "not in legal range 1 to N" where N is the current limit
- [ ] all modes need to be able to pull/push directly from/to the server when a mobile signal is available

## Re-jig time mode

- [ ] when time mode is stopped the split button becomes a disabled "Stopped",
      change that so it becomes "New Start",
      and when that is selected it starts a new race with a new sequence (starting at Clock s0) but it is appended to the history,
      so there can be multiple start,splits,stop sessions in the history for the race

Time mode just logs time events, the significance of those events is not known by time mode,
the time events only become meaningful when they are matched up to the corresponding bibs mode entries.

## Re-jig bibs mode

- [ ] when bibs mode is stopped the finish button becomes disabled, change that so it becomes "New Start" (make sure it fits the button, shrink font if necessary)
      and when that is selected it starts a new race with a new sequence (starting at Clock s0) but it is appended to the history,
      so there can be multiple clock,finish,stop sessions in the history for the race
- [ ] bibs mode needs to know what course it is currently logging and what bibs are valid for that course,
      this is so it can reliably report what bibs are outstanding,
      when no mobile signal it must be given it via bluetooth from the web app,
      if there is a mobile signal it can get it via the server

## Re-jig Cp mode

- [ ] show line number instead of split number as split number has no meaning in CP mode
- [ ] add a time-of-day column between the action column and the note column

CP Mode is unaffected by the multiple session tweaks in other modes.
CP mode is just a safety thing to record where on a course a runner is.
It also provides a rough time for recording split times.
CP Mode records time-of-day when a runner passed a CP.
Time mode also records time-of-day for all its actions, 
this means an appropriate elapsed time can be calculated.
