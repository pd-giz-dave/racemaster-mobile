package mobile.racemaster.util

private val DIGIT_GROUP = Regex("\\d+")

/**
 * Parses a minutes:seconds offset time entered with any non-digit separator (e.g. "5:30",
 * "5.30", "5 30"), returning a canonical "M:SS" string, or null if the input isn't valid. A
 * single number is treated as seconds (matching the reference web app's "ss or mm:ss" offset
 * convention) and isn't capped at 59 — e.g. "90" means 90 seconds late, i.e. "1:30".
 */
fun parseMinutesSeconds(input: String): String? {
    val groups = DIGIT_GROUP.findAll(input).map { it.value }.toList()
    return when (groups.size) {
        1 -> {
            val totalSeconds = groups[0].toIntOrNull() ?: return null
            "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
        }
        2 -> {
            val minutes = groups[0].toIntOrNull() ?: return null
            val seconds = groups[1].toIntOrNull() ?: return null
            if (seconds !in 0 until 60) return null
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
        else -> null
    }
}
