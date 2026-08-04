# Racemaster Mobile — Implementation TODO

## Re-jig race details

- [ ] remove the course, first bib and number of runners fields from race details, a race id becomes just its name and date
- [ ] add the ability to pull a race name and bib allocations from the server, or a mule, if neither is available
      bib number range checking is disabled, only duplicate detection remains,
      (see racemaster web app ToDo.MD for the races folder mechanism)
- [ ] the race name field history should include races pulled from the server (as above), selecting one pulls the bib numbers from
      the server and number validity checking, per course, becomes enabled using those, users can enter a name as now
      if no server list available (with the current manual history unchanged, just mixed and in distinguishable via name format)
- [ ] the above requires that the race details form has access to the setup server form      

## Re-jig time mode

- [ ] on the initial "Start" screen, split the "START" button into 2 - one "Start Juniors" the other "Start Seniors",
      selecting either starts the clock but tags the start line as "Juniors" or "Seniors" as appropriate, ditto in the history
- [ ] when time mode is stopped the split button becomes a disabled "Stopped",
      change that so it becomes two buttons "Start Juniors" and "Start Seniors",
      and when one is selected it starts a new race with a new sequence (starting at s0) but it is appended to the history,
      so there can be multiple start,splits,stop sessions in the history for the race

Time mode just logs time events, the significance of those events is not known by time mode,
the time events only become meaningful when they are matched up to the corresponding bibs mode entries.

## Re-jig bibs mode

- [ ] on the initial "Start" screen, split the "START" button into 2 - one "Start Juniors" the other "Start Seniors",
      selecting either starts the clock but tags the start line as "Juniors" or "Seniors" as appropriate, ditto in the history
- [ ] when entering a bib that is invalid for the course, instead of saying "Cannot log that" and disallowing it,
      allow it but tag it as an error in a similar way to entering a duplicate,
      show the error as "not in legal range 1 to N" where N is the max bib number pulled from the server for the course
- [ ] when bibs mode is stopped the finish and event buttons become disabled, change them so they become "Start Juniors" 
      and "Start Seniors" (make sure they fit the button, shrink the font if necessary)
      and when either is selected it starts a new race with a new sequence (starting at s0) but it is appended to the history,
      so there can be multiple clock,finish,stop sessions in the history for the race

## Re-jig Cp mode

- [ ] on the initial "Start" screen, split the "START" button into 2 - one "Start Juniors" the other "Start Seniors",
      selecting either starts the mode and adds a line tagged as either "Juniors" or "Seniors" as appropriate, ditto in the history
- [ ] the location for CP mode must contains a single number somewhere in its name,
      eg "polebank 1", "2 hadden", "cp3", etc, tell user of that constraint in the UI
- [ ] retirees seen at a CP or the finish need to be propagated to all other devices so they can update their bib expectations

CP mode is just a safety thing to record where on a course a runner is.
It also provides a rough time for recording split times.
CP Mode records time-of-day when a runner passed a CP.
Time mode also records time-of-day for all its actions, this means an appropriate elapsed time can be calculated.
