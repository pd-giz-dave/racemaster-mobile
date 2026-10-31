package mobile.racemaster.data.settings

// Which gendered voice BibSpeaker should use when SettingsRepository.speakBibsAloud is on — see
// that flow's own doc. Realized via TextToSpeech.setPitch() on the device's own default voice
// (not by enumerating/filtering TextToSpeech.getVoices()), so this is a *request*, not a
// guarantee of what a given device/engine will actually sound like.
enum class BibVoice { MALE, FEMALE }
