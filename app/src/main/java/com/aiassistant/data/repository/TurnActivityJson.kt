package com.aiassistant.data.repository

import com.aiassistant.domain.model.TurnActivity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * How a turn's activity is stored on the message row.
 *
 * Kept apart from the repository so the round trip can be tested directly: the bug this guards
 * against was invisible in isolation and only showed up as blank gaps in a reloaded transcript.
 */
internal object TurnActivityJson {

    /**
     * The declared type of a stored activity list.
     *
     * Both directions have to name it. Handing Gson a bare list makes it serialise each element by
     * its *runtime* class, which is the concrete Thought or ToolRun and never consults the adapter
     * registered against the interface -- so rows were written with no `kind` at all, and a ToolRun
     * came back as a Thought with empty text. That is what left blank gaps where the pills had been
     * once a turn was reloaded from the database.
     */
    val LIST_TYPE: Type = object : TypeToken<List<TurnActivity>>() {}.type

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TurnActivity::class.java, TurnActivitySerializer())
        .create()

    /** Null for nothing worth storing, so an empty list does not occupy a column. */
    fun encode(activity: List<TurnActivity>?): String? =
        activity?.takeIf { it.isNotEmpty() }?.let { gson.toJson(it, LIST_TYPE) }

    /** Null when the column is empty or holds something unreadable; a bad row must not break a turn. */
    fun decode(json: String?): List<TurnActivity>? =
        json?.let { runCatching { gson.fromJson<List<TurnActivity>>(it, LIST_TYPE) }.getOrNull() }
}

/**
 * TurnActivity is a sealed interface, so Gson needs to be told which subtype a row holds.
 * RuntimeTypeAdapterFactory is not in the core artifact, so the discriminator is written by hand.
 */
private class TurnActivitySerializer :
    JsonSerializer<TurnActivity>,
    JsonDeserializer<TurnActivity> {

    override fun serialize(
        src: TurnActivity,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val obj = JsonObject()
        when (src) {
            is TurnActivity.Thought -> {
                obj.addProperty("kind", "thought")
                obj.addProperty("text", src.text)
            }
            is TurnActivity.ToolRun -> {
                obj.addProperty("kind", "tool")
                obj.addProperty("name", src.name)
                obj.addProperty("arguments", src.arguments)
                src.result?.let { obj.addProperty("result", it) }
            }
        }
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): TurnActivity {
        val obj = json.asJsonObject
        // Rows written before the discriminator existed carry no `kind`, so fall back to the
        // shape: only a tool run has a name. Without this those turns still read back as empty
        // thoughts, and the history already on the device would keep its gaps.
        val kind = obj.get("kind")?.asString
            ?: if (obj.has("name")) "tool" else "thought"
        return when (kind) {
            "tool" -> TurnActivity.ToolRun(
                name = obj.get("name")?.asString.orEmpty(),
                arguments = obj.get("arguments")?.asString.orEmpty(),
                result = obj.get("result")?.takeIf { !it.isJsonNull }?.asString
            )
            else -> TurnActivity.Thought(
                text = obj.get("text")?.asString.orEmpty()
            )
        }
    }
}
