package app.basis.core.common

/** "1 ч 05 мин", "3 мин 12 с", "45 с". */
fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "%d ч %02d мин".format(h, m)
        m > 0 -> "%d мин %02d с".format(m, s)
        else -> "$s с"
    }
}
