package com.aiassistant.data.repository

import com.aiassistant.domain.model.TurnActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression this file exists for: tool pills vanished from a reloaded transcript, leaving
 * blank gaps, because an untyped `toJson` serialised each element by its runtime class and never
 * consulted the adapter registered against the sealed interface. Nothing crashed -- every row
 * simply came back as an empty Thought.
 */
class TurnActivityJsonTest {

    @Test
    fun `tool run survives a round trip`() {
        val original = listOf(
            TurnActivity.ToolRun(
                name = "web_search",
                arguments = """{"query":"kotlin"}""",
                result = "3 results"
            )
        )

        val decoded = TurnActivityJson.decode(TurnActivityJson.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `a mixed turn keeps both its kinds and their order`() {
        val original = listOf(
            TurnActivity.Thought("checking the weather first"),
            TurnActivity.ToolRun("weather", """{"city":"Glasgow"}""", "12C, raining"),
            TurnActivity.Thought("now answering")
        )

        val decoded = TurnActivityJson.decode(TurnActivityJson.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `the discriminator is actually written`() {
        val json = TurnActivityJson.encode(listOf(TurnActivity.ToolRun("sms", "{}", null)))

        // The absence of this property is precisely what caused the gaps.
        assertTrue("no kind discriminator in $json", json!!.contains("\"kind\":\"tool\""))
    }

    @Test
    fun `a tool run still in flight has no result`() {
        val original = listOf(TurnActivity.ToolRun("termux_shell", """{"command":"ls"}""", null))

        val decoded = TurnActivityJson.decode(TurnActivityJson.encode(original))

        assertEquals(original, decoded)
        assertNull((decoded!!.single() as TurnActivity.ToolRun).result)
    }

    @Test
    fun `rows written before the discriminator existed are read by shape`() {
        // Only a tool run carries a name, so history already on a device keeps its pills.
        val legacy = """[{"name":"weather","arguments":"{}"},{"text":"thinking"}]"""

        val decoded = TurnActivityJson.decode(legacy)!!

        assertEquals(TurnActivity.ToolRun("weather", "{}", null), decoded[0])
        assertEquals(TurnActivity.Thought("thinking"), decoded[1])
    }

    @Test
    fun `nothing worth storing encodes to null`() {
        assertNull(TurnActivityJson.encode(null))
        assertNull(TurnActivityJson.encode(emptyList()))
    }

    @Test
    fun `an unreadable column does not break the turn`() {
        assertNull(TurnActivityJson.decode("not json at all"))
        assertNull(TurnActivityJson.decode(null))
    }
}
