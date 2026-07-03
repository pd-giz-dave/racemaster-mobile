package mobile.racemaster.ui.timemode

fun formatElapsed(millis: Long): String {
    val total = millis.coerceAtLeast(0)
    val hours = total / 3_600_000
    val minutes = (total % 3_600_000) / 60_000
    val seconds = (total % 60_000) / 1000
    val centis = (total % 1000) / 10
    return "%02d:%02d:%02d.%02d".format(hours, minutes, seconds, centis)
}