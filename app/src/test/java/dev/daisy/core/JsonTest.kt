package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `a string round trips`() {
        val v = Json.parse(Json.quote("he said \"hi\""))
        assertEquals("he said \"hi\"", v?.str())
    }

    @Test
    fun `control characters are escaped`() {
        assertEquals("\"a\\u0001b\"", Json.quote("a\u0001b"))
        assertEquals("\"a\\nb\"", Json.quote("a\nb"))
    }

    @Test
    fun `an object round trips`() {
        val o = Json.Obj().put("a", "x").put("n", 5L).put("b", true).render()
        val v = Json.parse(o)!!
        assertEquals("x", v.get("a")?.str())
        assertEquals(5L, v.get("n")?.long())
        assertEquals(true, v.get("b")?.bool())
    }

    @Test
    fun `nulls are omitted, not written as null`() {
        val o = Json.Obj().put("a", null as String?).put("b", "x").render()
        assertEquals("""{"b":"x"}""", o)
    }

    @Test
    fun `an array of strings renders`() {
        val o = Json.Obj().arr("k", listOf("a", "b")).render()
        assertEquals("""{"k":["a","b"]}""", o)
    }

    @Test
    fun `nested objects render`() {
        val o = Json.Obj().objs("k", listOf(Json.Obj().put("a", 1L))).render()
        assertEquals("""{"k":[{"a":1}]}""", o)
    }

    @Test
    fun `an epoch millis value survives a round trip`() {
        // 2^53 boundary: the reason numbers are read as Double is only safe if
        // values stay well inside it. Epoch millis do.
        val n = 1_797_976_800_000L
        assertEquals(n, Json.parse(Json.Obj().put("t", n).render())?.get("t")?.long())
    }

    @Test
    fun `malformed input is null, not a throw`() {
        assertNull(Json.parse("{"))
        assertNull(Json.parse("""{"a":}"""))
        assertNull(Json.parse(""))
        assertNull(Json.parse("""{"a":1} trailing"""))
    }

    @Test
    fun `unicode is not escaped on the way out`() {
        assertEquals("\"日本\"", Json.quote("日本"))
    }

    @Test
    fun `a unicode escape on the way in decodes`() {
        assertEquals("A", Json.parse("\"\\u0041\"")?.str())
    }

    @Test
    fun `an empty object and array parse`() {
        assertTrue(Json.parse("{}")?.obj()?.isEmpty() == true)
        assertTrue(Json.parse("[]")?.arr()?.isEmpty() == true)
    }

    @Test
    fun `a string containing a brace does not confuse the reader`() {
        val v = Json.parse("""{"a":"}"}""")
        assertEquals("}", v?.get("a")?.str())
    }
}
