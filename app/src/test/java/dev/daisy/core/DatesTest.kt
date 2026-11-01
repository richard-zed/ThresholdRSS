package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatesTest {

    // Reference values from `date -u -d ... +%s`, times 1000.
    private val JUN_10_2014 = 1_402_372_800_000L

    @Test
    fun `epoch itself`() {
        assertEquals(0L, Dates.parse("1970-01-01T00:00:00Z"))
    }

    @Test
    fun `a known rfc3339 instant`() {
        assertEquals(JUN_10_2014, Dates.parse("2014-06-10T04:00:00Z"))
    }

    @Test
    fun `a known rfc822 instant`() {
        assertEquals(JUN_10_2014, Dates.parse("Tue, 10 Jun 2014 04:00:00 GMT"))
    }

    @Test
    fun `rfc822 without the day name`() {
        assertEquals(JUN_10_2014, Dates.parse("10 Jun 2014 04:00:00 GMT"))
    }

    @Test
    fun `two digit years split at fifty`() {
        assertEquals(Dates.parse("2024-06-10T00:00:00Z"), Dates.parse("10 Jun 24 00:00:00 GMT"))
        assertEquals(Dates.parse("1994-06-10T00:00:00Z"), Dates.parse("10 Jun 94 00:00:00 GMT"))
    }

    @Test
    fun `a positive offset is subtracted`() {
        // 05:30 in +05:30 is midnight UTC.
        assertEquals(Dates.parse("2014-06-10T00:00:00Z"), Dates.parse("2014-06-10T05:30:00+05:30"))
    }

    @Test
    fun `a negative offset is added`() {
        assertEquals(Dates.parse("2014-06-10T12:00:00Z"), Dates.parse("2014-06-10T08:00:00-04:00"))
    }

    @Test
    fun `an offset without a colon parses`() {
        assertEquals(Dates.parse("2014-06-10T12:00:00Z"), Dates.parse("2014-06-10T07:00:00-0500"))
    }

    @Test
    fun `named zones resolve`() {
        assertEquals(Dates.parse("2014-06-10T12:00:00Z"), Dates.parse("Tue, 10 Jun 2014 07:00:00 EST"))
        assertEquals(Dates.parse("2014-06-10T12:00:00Z"), Dates.parse("Tue, 10 Jun 2014 04:00:00 PST"))
    }

    @Test
    fun `a missing zone is utc`() {
        assertEquals(JUN_10_2014, Dates.parse("2014-06-10T04:00:00"))
    }

    @Test
    fun `fractional seconds are dropped`() {
        assertEquals(JUN_10_2014, Dates.parse("2014-06-10T04:00:00.123Z"))
    }

    @Test
    fun `a date only atom value parses at midnight`() {
        assertEquals(Dates.parse("2014-06-10T00:00:00Z"), Dates.parse("2014-06-10"))
    }

    @Test
    fun `leap day in a leap year`() {
        // 2024-02-29T00:00:00Z
        assertEquals(1_709_164_800_000L, Dates.parse("2024-02-29T00:00:00Z"))
    }

    @Test
    fun `february 29 in a non leap year is rejected`() {
        assertNull(Dates.parse("2023-02-29T00:00:00Z"))
    }

    @Test
    fun `a century non leap year is rejected`() {
        assertNull(Dates.parse("1900-02-29T00:00:00Z"))
    }

    @Test
    fun `april 31 is rejected`() {
        assertNull(Dates.parse("2014-04-31T00:00:00Z"))
    }

    @Test
    fun `garbage is null`() {
        assertNull(Dates.parse("last tuesday"))
        assertNull(Dates.parse(""))
        assertNull(Dates.parse("not a date at all"))
    }

    @Test
    fun `an out of range year is null`() {
        assertNull(Dates.parse("1066-06-10T00:00:00Z"))
    }

    @Test
    fun `day boundaries land on the right day`() {
        // One second before and after midnight, to catch an off-by-one in the
        // civil-from-days conversion rather than just a valid-looking value.
        val before = Dates.parse("2014-06-09T23:59:59Z")
        val after = Dates.parse("2014-06-10T00:00:00Z")
        assertEquals(1000L, after!! - before!!)
    }

    @Test
    fun `a leap year day count is right`() {
        // 2024 is a leap year: Jan 1 to Dec 31 is 365 days apart in a leap year.
        val jan1 = Dates.parse("2024-01-01T00:00:00Z")!!
        val dec31 = Dates.parse("2024-12-31T00:00:00Z")!!
        assertEquals(365L * 86_400_000L, dec31 - jan1)
    }
}
