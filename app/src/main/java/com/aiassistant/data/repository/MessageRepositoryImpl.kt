package com.aiassistant.data.repository

import com.aiassistant.data.database.MessageDao
import com.aiassistant.data.model.MessageEntity
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.google.gson.reflect.TypeToken
import java.util.UUID

class MessageRepositoryImpl(
    private val messageDao: MessageDao
) : MessageRepository {

    override fun getMessages(conversationId: String): Flow<List<ChatMessage>> {
        return messageDao.getMessagesByConversation(conversationId)
            .map { entities ->
                entities.map { it.toDomain() }
            }
    }

    override suspend fun getMessagesSync(conversationId: String): List<ChatMessage> {
        return messageDao.getMessagesByConversationSync(conversationId)
            .map { it.toDomain() }
    }

    override suspend fun addMessage(
        conversationId: String,
        role: String,
        content: String,
        activity: List<com.aiassistant.domain.model.TurnActivity>?
    ): String {
        val id = UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            activity = activity?.takeIf { it.isNotEmpty() }
                ?.let { activityGson.toJson(it, TURN_ACTIVITY_LIST_TYPE) }
        )
        messageDao.insertMessage(entity)
        return id
    }

    override suspend fun addMessageWithToolCalls(
        conversationId: String,
        role: String,
        content: String,
        toolCalls: String
    ): String {
        val id = UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            toolCalls = toolCalls
        )
        messageDao.insertMessage(entity)
        return id
    }

    override suspend fun addMessages(messages: List<ChatMessage>) {
        val entities = messages.map {
            MessageEntity(
                id = it.id,
                conversationId = it.conversationId,
                role = it.role.name.lowercase(),
                content = it.content,
                timestamp = it.timestamp,
                toolCalls = it.toolCalls?.let { tools -> com.google.gson.Gson().toJson(tools) },
                toolResults = it.toolResults?.let { results -> com.google.gson.Gson().toJson(results) }
            )
        }
        messageDao.insertMessages(entities)
    }

    override suspend fun deleteMessages(conversationId: String) {
        messageDao.deleteMessagesByConversation(conversationId)
    }

    private fun MessageEntity.toDomain(): ChatMessage {
        val gson = com.google.gson.Gson()
        return ChatMessage(
            id = id,
            conversationId = conversationId,
            role = when (role) {
                "system" -> MessageRole.SYSTEM
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                "tool" -> MessageRole.TOOL
                else -> MessageRole.USER
            },
            content = content,
            timestamp = timestamp,
            toolCalls = toolCalls?.let { gson.fromJson(it, Array<com.aiassistant.domain.model.ToolCall>::class.java) }?.toList(),
            toolResults = toolResults?.let { gson.fromJson(it, Array<com.aiassistant.domain.model.ToolResult>::class.java) }?.toList(),
            activity = activity?.let {
                runCatching {
                    activityGson.fromJson<List<com.aiassistant.domain.model.TurnActivity>>(
                        it,
                        TURN_ACTIVITY_LIST_TYPE
                    )
                }.getOrNull()
            }
        )
    }
}

/**
 * The declared type of a stored activity list.
 *
 * Both directions have to name it. Handing Gson a bare list makes it serialise each element by
 * its *runtime* class, which is the concrete Thought or ToolRun and never consults the adapter
 * registered against the interface -- so rows were written with no `kind` at all, and a ToolRun
 * came back as a Thought with empty text. That is what left blank gaps where the pills had been
 * once a turn was reloaded from the database.
 */
private val TURN_ACTIVITY_LIST_TYPE: java.lang.reflect.Type =
    object : TypeToken<List<com.aiassistant.domain.model.TurnActivity>>() {}.type

/**
 * TurnActivity is a sealed interface, so Gson needs to be told which subtype a row holds.
 * RuntimeTypeAdapterFactory is not in the core artifact, so the discriminator is written by hand.
 */
private val activityGson: com.google.gson.Gson = com.google.gson.GsonBuilder()
    .registerTypeAdapter(
        com.aiassistant.domain.model.TurnActivity::class.java,
        TurnActivitySerializer()
    )
    .create()

private class TurnActivitySerializer :
    com.google.gson.JsonSerializer<com.aiassistant.domain.model.TurnActivity>,
    com.google.gson.JsonDeserializer<com.aiassistant.domain.model.TurnActivity> {

    override fun serialize(
        src: com.aiassistant.domain.model.TurnActivity,
        typeOfSrc: java.lang.reflect.Type,
        context: com.google.gson.JsonSerializationContext
    ): com.google.gson.JsonElement {
        val obj = com.google.gson.JsonObject()
        when (src) {
            is com.aiassistant.domain.model.TurnActivity.Thought -> {
                obj.addProperty("kind", "thought")
                obj.addProperty("text", src.text)
            }
            is com.aiassistant.domain.model.TurnActivity.ToolRun -> {
                obj.addProperty("kind", "tool")
                obj.addProperty("name", src.name)
                obj.addProperty("arguments", src.arguments)
            }
        }
        return obj
    }

    override fun deserialize(
        json: com.google.gson.JsonElement,
        typeOfT: java.lang.reflect.Type,
        context: com.google.gson.JsonDeserializationContext
    ): com.aiassistant.domain.model.TurnActivity {
        val obj = json.asJsonObject
        // Rows written before the discriminator existed carry no `kind`, so fall back to the
        // shape: only a tool run has a name. Without this those turns still read back as empty
        // thoughts, and the history already on the device would keep its gaps.
        val kind = obj.get("kind")?.asString
            ?: if (obj.has("name")) "tool" else "thought"
        return when (kind) {
            "tool" -> com.aiassistant.domain.model.TurnActivity.ToolRun(
                name = obj.get("name")?.asString.orEmpty(),
                arguments = obj.get("arguments")?.asString.orEmpty()
            )
            else -> com.aiassistant.domain.model.TurnActivity.Thought(
                text = obj.get("text")?.asString.orEmpty()
            )
        }
    }
}
