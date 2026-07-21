package mobile.racemaster.util

import kotlin.random.Random

// Short, easy-to-say-aloud words — this name gets read out over a phone call or shouted
// across a car park, not typed, so it favours familiar words over uniqueness guarantees.
// Collisions are fine: the device's real join key is its UUID deviceId, this is purely a
// spoken/display identity.
private val ADJECTIVES = listOf(
    "clever", "swift", "bright", "quiet", "bold", "happy", "gentle", "brave", "calm", "eager",
    "fuzzy", "jolly", "lively", "mighty", "nimble", "plucky", "sunny", "tidy", "witty", "zesty",
    "amber", "coral", "golden", "silver", "scarlet", "violet", "misty", "rusty", "dusty", "breezy",
    "daring", "cheerful", "cosy", "crisp", "dapper", "drowsy", "earnest", "feisty", "frosty", "glossy",
    "grand", "hardy", "hearty", "humble", "keen", "larky", "loyal", "merry", "noble", "perky",
    "plush", "proud", "rosy", "ruddy", "shiny", "sleek", "snug", "spry", "steady", "trusty",
    "gallant", "sturdy", "vivid", "chipper", "spirited", "wily", "genial", "radiant", "supple", "stalwart",
    "peppy", "bonny", "canny", "chirpy", "dashing", "jaunty", "nifty", "sprightly", "zippy", "zingy",
    "spiffy", "plummy", "comely", "dinky", "flashy", "frisky", "gutsy", "handy", "jazzy", "lucky",
    "mellow", "natty", "pert", "quaint", "saucy", "speedy", "sporty", "suave", "trim", "wiry",
)

private val NOUNS = listOf(
    "cricket", "otter", "falcon", "badger", "heron", "sparrow", "beetle", "rabbit", "salmon", "raven",
    "wombat", "gecko", "puffin", "weasel", "marten", "linnet", "plover", "kestrel", "cygnet", "vole",
    "meadow", "harbour", "summit", "valley", "orchard", "thicket", "brook", "ridge", "hollow", "cove",
    "fox", "hare", "lynx", "robin", "magpie", "swallow", "wren", "finch", "stoat", "ferret",
    "dolphin", "seal", "osprey", "merlin", "harrier", "teal", "grouse", "wagtail", "dipper", "gull",
    "glen", "dale", "moor", "marsh", "grove", "knoll", "bay", "creek", "cliff", "dune",
    "swan", "stork", "crane", "egret", "curlew", "warbler", "siskin", "bunting", "vale", "fen",
    "copse", "heath", "crag", "tarn", "reef", "shoal", "delta", "isle", "cape", "spring",
    "viper", "cobra", "panther", "jaguar", "ibis", "toucan", "parrot", "condor", "beaver", "squirrel",
    "hedgehog", "mongoose", "ocelot", "panda", "koala", "wallaby", "kiwi", "emu", "pelican", "flamingo",
)

/** Generates a memorable "adjective-noun" device name, e.g. "clever-cricket". */
fun generateDeviceName(random: Random = Random.Default): String {
    val adjective = ADJECTIVES[random.nextInt(ADJECTIVES.size)]
    val noun = NOUNS[random.nextInt(NOUNS.size)]
    return "$adjective-$noun"
}
