package com.aiassistant.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tool argument the model produces passes through here, on both the hosted and on-device
 * paths. It is deliberately lenient -- small models emit unquoted keys and bare values -- so the
 * point of these is that leniency does not quietly mangle well-formed input.
 */
class JsonUtilsTest {

    @Test
    fun `well-formed json parses`() {
        val result = JsonUtils.parseToJsonMap("""{"query":"kotlin coroutines","numResults":5}""")

        assertEquals("kotlin coroutines", result["query"])
        assertEquals(5L, result["numResults"])
    }

    @Test
    fun `booleans and nulls keep their types`() {
        val result = JsonUtils.parseToJsonMap("""{"enabled":true,"notify":false,"id":null}""")

        assertEquals(true, result["enabled"])
        assertEquals(false, result["notify"])
        assertTrue(result.containsKey("id"))
        assertEquals(null, result["id"])
    }

    @Test
    fun `escapes survive, including a quoted shell command`() {
        val result = JsonUtils.parseToJsonMap("""{"command":"echo \"hi\"\nls"}""")

        assertEquals("echo \"hi\"\nls", result["command"])
    }

    @Test
    fun `nested objects and arrays parse`() {
        val result = JsonUtils.parseToJsonMap(
            """{"contents":{"text":true},"includeDomains":["a.com","b.com"]}"""
        )

        assertEquals(mapOf("text" to true), result["contents"])
        assertEquals(listOf("a.com", "b.com"), result["includeDomains"])
    }

    @Test
    fun `an array is not truncated by what follows it`() {
        // The array read past its own closing bracket and came back empty unless it happened to
        // start at offset 0, which quietly disabled the web_search domain filters.
        val result = JsonUtils.parseToJsonMap(
            """{"query":"q","includeDomains":["a.com"],"excludeDomains":["b.com","c.com"]}"""
        )

        assertEquals(listOf("a.com"), result["includeDomains"])
        assertEquals(listOf("b.com", "c.com"), result["excludeDomains"])
        assertEquals("q", result["query"])
    }

    @Test
    fun `an array of numbers parses`() {
        val result = JsonUtils.parseToJsonMap("""{"ids":[1,2,3]}""")

        assertEquals(listOf(1L, 2L, 3L), result["ids"])
    }

    @Test
    fun `an empty array parses`() {
        assertEquals(emptyList<Any?>(), JsonUtils.parseToJsonMap("""{"tags":[]}""")["tags"])
    }

    @Test
    fun `unquoted keys and values are tolerated`() {
        // Small models emit this shape; rejecting it would fail the call outright.
        val result = JsonUtils.parseToJsonMap("{query=latest news about Nvidia}")

        assertEquals("latest news about Nvidia", result["query"])
    }

    @Test
    fun `negative and decimal numbers parse`() {
        val result = JsonUtils.parseToJsonMap("""{"lat":-33.86,"count":-2}""")

        assertEquals(-33.86, result["lat"])
        assertEquals(-2L, result["count"])
    }

    @Test
    fun `anything that is not an object yields an empty map rather than throwing`() {
        assertEquals(emptyMap<String, Any?>(), JsonUtils.parseToJsonMap(""))
        assertEquals(emptyMap<String, Any?>(), JsonUtils.parseToJsonMap("[1,2,3]"))
        assertEquals(emptyMap<String, Any?>(), JsonUtils.parseToJsonMap("just prose"))
    }

    @Test
    fun `an empty object is empty`() {
        assertEquals(emptyMap<String, Any?>(), JsonUtils.parseToJsonMap("{}"))
    }
}
