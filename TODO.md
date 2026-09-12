# Bibs and CP mode auto bib allocations

- [ ] add the ability to pull a race name, its courses and bib allocations from the server,
      or a mule, if neither is available bib number range checking is disabled,
      only duplicate detection remains,
      the list of outstanding bib #'s should be a clickable link and when clicked should 
      show a full list of outstanding bibs with all the info pulled from the server
      (bib #, name, course, cat), with an option to show all bibs seen with their status -
      pass, retire, start, finish, outstanding with outstanding highlighted
      (see racemaster web app ToDo.MD for the races folder mechanism),
      when connected to a server the bib allocations should be checked for changes regularly,
      also the race progress info should be grabbed regularly to update bib expectations
- [ ] the race name field history should include races pulled from the server (as above), selecting 
      one pulls the bib numbers from the server and number validity checking, per course, becomes
      enabled using those, users can enter a name as now if no server list available (with the 
      current manual history unchanged, just mixed and in distinguishable via name format)
- [ ] the above requires that the race details form has access to the setup server form
- [ ] retirees seen at a CP or the finish need to be propagated to all other devices so they can 
      update their bib expectations
- [ ] CP mode needs a count of passes and retires so the marshal can report to the sweep team how 
      many people they have seen, the sweep team will know what they should have seen (==passes from
      previous CP)
