package dev.daisy.core

/**
 * Feed dates, in the two formats feeds actually use.
 *
 * RSS 2.0 specifies RFC 822 -- "Tue, 10 Jun 2014 04:00:00 GMT". Atom specifies
 * RFC 3339 -- "2014-06-10T04:00:00Z". Real feeds mix them freely regardless of
 * format, so both are tried against either.
 *
 * Hand-rolled instead of java.time / SimpleDateFormat because those differ by
 * API level on Android (java.time needs 26 or desugaring; this file targets 24)
 * and this stays runnable in build.sh. The grammar accepted is exactly the two
 * above plus their common malformations, and anything else returns null rather
 * than a guess.
 *
 * Null is meaningful: it means "no usable date", which sorts to the bottom.
 * Returning 0 would sort every undated item to 1970.
 */
object Dates {

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    /** RSS day names are ignored; some feeds get them wrong. */
    private val RFC822 = Regex(
        """(?:[A-Za-z]{3},\s*)?(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(.*)""",
    )

    private val RFC3339 = Regex(
        """(\d{4})-(\d{2})-(\d{2})(?:[Tt ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?\s*([Zz]|[+-]\d{2}:?\d{2})?""",
    )

    /** Epoch millis, or null if [raw] is not a date this understands. */
    fun parse(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return parseRfc3339(s) ?: parseRfc822(s)
    }

    private fun parseRfc3339(s: String): Long? {
        val m = RFC3339.matchEntire(s) ?: return null
        val g = m.groupValues
        val year = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val day = g[3].toIntOrNull() ?: return null
        val hour = g[4].ifEmpty { "0" }.toIntOrNull() ?: return null
        val min = g[5].ifEmpty { "0" }.toIntOrNull() ?: return null
        val sec = g[6].ifEmpty { "0" }.toIntOrNull() ?: return null
        val offsetMin = zoneOffsetMinutes(g[7]) ?: return null
        return build(year, month, day, hour, min, sec, offsetMin)
    }

    private fun parseRfc822(s: String): Long? {
        val m = RFC822.matchEntire(s) ?: return null
        val g = m.groupValues
        val day = g[1].toIntOrNull() ?: return null
        val month = MONTHS[g[2].lowercase()] ?: return null
        var year = g[3].toIntOrNull() ?: return null
        // Two-digit years: RFC 2822 says 00-49 is 2000s, 50-99 is 1900s.
        if (year < 100) year += if (year < 50) 2000 else 1900
        val hour = g[4].toIntOrNull() ?: return null
        val min = g[5].toIntOrNull() ?: return null
        val sec = g[6].ifEmpty { "0" }.toIntOrNull() ?: return null
        val offsetMin = zoneOffsetMinutes(g[7]) ?: return null
        return build(year, month, day, hour, min, sec, offsetMin)
    }

    /**
     * Minutes east of UTC for a zone field.
     *
     * Accepts Z, +05:30, +0530, +05, and the obsolete single-letter names that
     * appear in older feeds. An unknown name returns null; a missing zone is
     * treated as UTC, which is what a bare RFC 822 stamp means.
     */
    private fun zoneOffsetMinutes(zone: String): Int? {
        val z = zone.trim()
        if (z.isEmpty()) return 0
        if (z == "Z" || z == "z") return 0
        if (z.length == 1) {
            // RFC 822 single-letter zones, before RFC 2822 deprecated them.
            return when (z.uppercase()) {
                "Z" -> 0
                "A", "B", "C", "D", "E", "F", "G", "H", "I" -> 60 * (1 + (z.uppercase()[0] - 'A'))
                "K", "L", "M" -> 60 * (10 + (z.uppercase()[0] - 'K'))
                "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y" ->
                    -60 * (1 + (z.uppercase()[0] - 'N'))
                else -> null
            }
        }
        val upper = z.uppercase()
        // Named zones seen in real feeds. The US ones ignore DST, which is the
        // conservative choice: an hour wrong beats a date in the future.
        when (upper) {
            "GMT", "UT", "UTC", "WET" -> return 0
            "EST" -> return -300
            "EDT" -> return -240
            "CST" -> return -360
            "CDT" -> return -300
            "MST" -> return -420
            "MDT" -> return -360
            "PST" -> return -480
            "PDT" -> return -420
            "BST" -> return 60
            "CET" -> return 60
            "CEST" -> return 120
            "JST" -> return 540
        }
        val sign = when (upper.firstOrNull()) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val digits = upper.drop(1).replace(":", "")
        if (digits.length < 2 || digits.length > 4) return null
        val hh = digits.take(2).toIntOrNull() ?: return null
        val mm = if (digits.length > 2) digits.drop(2).toIntOrNull() ?: return null else 0
        return sign * (hh * 60 + mm)
    }

    private fun build(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int, offsetMinutes: Int,
    ): Long? {
        if (month !in 1..12) return null
        if (day !in 1..31) return null
        if (hour !in 0..24) return null
        if (minute !in 0..59) return null
        if (second !in 0..60) return null // leap second
        if (year !in 1970..2200) return null
        if (day > lengthOfMonth(year, month)) return null

        val days = daysFromEpoch(year, month, day)
        val seconds = days * 86400L +
            hour * 3600L + minute * 60L + second -
            offsetMinutes * 60L
        return seconds * 1000L
    }

    private fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeap(year)) 29 else 28
    }

    private fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    /** Days since 1970-01-01. Howard Hinnant's civil-from-days, inverted. */
    private fun daysFromEpoch(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097L + doe - 719468L).toLong()
    }
}
