package com.aiassistant.domain.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.aiassistant.domain.service.ToolManager
import com.google.ai.edge.litertlm.OpenApiTool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val TAG = "SmsTool"

/**
 * Text messages: read the inbox, and send.
 *
 * Sending is the first thing in this app that acts on the world irreversibly and on the user's
 * behalf, so it is deliberately awkward for the model to do by accident: the number must be
 * given literally, `confirmed` must be set, and a resolved contact name is echoed back in the
 * result so a wrong recipient is visible in the transcript rather than only in the recipient's
 * phone.
 *
 * READ_SMS and SEND_SMS are restricted permissions on Google Play -- normally only the default
 * SMS handler gets them. That is fine for a sideloaded personal build and a blocker for
 * distribution; see the manifest.
 */
class SmsTool @Inject constructor(
    private val context: Context
) : OpenApiTool {

    init {
        register()
    }

    fun register() {
        ToolManager.registerOpenApiTool(this)
    }

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "sms",
          "description": "Read and send the user's text messages. Use 'list' to catch up on recent messages or to find what someone said. Use 'send' only when the user has clearly asked for a message to be sent, and only after showing them the exact text and recipient and getting a yes -- sending cannot be undone. Never send on your own initiative.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "enum": ["list", "send"],
                "description": "What to do."
              },
              "limit": {
                "type": "integer",
                "description": "For 'list': how many recent messages, 1-50. Default 15."
              },
              "from": {
                "type": "string",
                "description": "For 'list': only messages from this number or contact name."
              },
              "search": {
                "type": "string",
                "description": "For 'list': only messages whose text contains this."
              },
              "to": {
                "type": "string",
                "description": "For 'send': the recipient's phone number. A literal number, not a name -- look the number up with 'list' first if the user gave you a name, and if you cannot find it, ask."
              },
              "message": {
                "type": "string",
                "description": "For 'send': the exact text to send."
              },
              "confirmed": {
                "type": "boolean",
                "description": "For 'send': set true only after the user has seen this exact recipient and text and agreed. Sending is refused without it."
              }
            },
            "required": ["action"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String = runCatching {
        val args = JsonUtils.parseToJsonMap(paramsJsonString)
        when (val action = (args["action"] as? String)?.lowercase()) {
            "list" -> list(args)
            "send" -> send(args)
            null -> "Error: Missing 'action'. One of: list, send."
            else -> "Error: Unknown action '$action'. One of: list, send."
        }
    }.getOrElse { "Error: ${it.message}" }

    private fun missing(permission: String, what: String): String? {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return null
        return "Error: $what permission not granted. Tell the user to open Settings in this app " +
            "and grant it under Phone access, then ask again."
    }

    private fun list(args: Map<*, *>): String {
        missing(Manifest.permission.READ_SMS, "SMS read")?.let { return it }

        val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 15
        val from = (args["from"] as? String)?.takeIf { it.isNotBlank() }
        val search = (args["search"] as? String)?.takeIf { it.isNotBlank() }

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        val rows = mutableListOf<String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, null, null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val address = cursor.getString(0).orEmpty()
                val body = cursor.getString(1).orEmpty()
                val date = cursor.getLong(2)
                val incoming = cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX
                val name = contactName(address)

                // Filtering in Kotlin rather than SQL: `from` may be a contact name, which the
                // SMS table has never heard of -- it only stores the number.
                if (from != null && !address.contains(from, true) &&
                    name?.contains(from, true) != true
                ) continue
                if (search != null && !body.contains(search, true)) continue

                val who = name?.let { "$it ($address)" } ?: address
                rows.add(
                    "- ${if (incoming) "from" else "to"} $who · ${stamp(date)}\n" +
                        "  ${body.replace("\n", "\n  ")}\n"
                )
            }
        } ?: return "Error: Could not read the SMS provider."

        if (rows.isEmpty()) return "No matching messages."
        return buildString {
            append("${rows.size} message${if (rows.size == 1) "" else "s"}:\n\n")
            rows.forEach(::append)
        }
    }

    private fun send(args: Map<*, *>): String {
        missing(Manifest.permission.SEND_SMS, "SMS send")?.let { return it }

        val to = (args["to"] as? String)?.takeIf { it.isNotBlank() }
            ?: return "Error: 'to' is required, as a phone number."
        val message = (args["message"] as? String)?.takeIf { it.isNotBlank() }
            ?: return "Error: 'message' is required."
        if (args["confirmed"] != true) {
            return "Not sent. Show the user the exact recipient and message, get their agreement, " +
                "then call again with confirmed=true."
        }
        // A name here means the model skipped looking the number up, and the send would go
        // nowhere or somewhere wrong.
        if (to.none { it.isDigit() }) {
            return "Error: '$to' is not a phone number. Find the number first, or ask the user."
        }

        return try {
            val manager = context.getSystemService(SmsManager::class.java)
                ?: return "Error: No SMS service on this device."
            // Long messages have to be split or they are silently truncated by the radio.
            val parts = manager.divideMessage(message)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                manager.sendTextMessage(to, null, message, null, null)
            }
            val name = contactName(to)
            "Sent to ${name?.let { "$it ($to)" } ?: to}: \"$message\""
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            "Error: Could not send: ${e.message}"
        }
    }

    /** Best-effort display name, so the transcript shows who a number belongs to. */
    private fun contactName(address: String): String? {
        if (address.isBlank()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(address)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    private fun stamp(ms: Long) =
        SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ms))
}
