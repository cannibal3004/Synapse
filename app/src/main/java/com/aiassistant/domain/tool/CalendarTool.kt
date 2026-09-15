package com.aiassistant.domain.tool

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import com.aiassistant.domain.service.ToolManager
import com.google.ai.edge.litertlm.OpenApiTool
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

private const val TAG = "CalendarTool"

/**
 * The device calendar, read and write.
 *
 * Goes through the CalendarContract provider, so it sees whatever accounts the user has synced --
 * Google, Exchange, local -- without knowing anything about them.
 *
 * Permission is checked per call rather than assumed. A tool runs on a background thread and
 * cannot put a permission dialog on screen, so the honest thing when it is missing is to say so
 * in a way the model can relay: Settings has a button that asks properly.
 */
class CalendarTool @Inject constructor(
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
          "name": "calendar",
          "description": "Read and write the user's device calendar. Use 'list' to see what is on -- before answering anything about the user's availability, plans or schedule, and before proposing a time. Use 'create' to add an event once the user has asked for one; say what you are adding before you add it. Use 'delete' only when the user names an event to remove. Times are in the device's local timezone.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "enum": ["list", "create", "delete"],
                "description": "What to do."
              },
              "days_ahead": {
                "type": "integer",
                "description": "For 'list': how many days forward to look. Default 7. Use 1 for 'today', 0 for what is on right now."
              },
              "days_back": {
                "type": "integer",
                "description": "For 'list': how many days back to include. Default 0. Use this to answer questions about what already happened."
              },
              "title": {
                "type": "string",
                "description": "For 'create': the event title."
              },
              "start": {
                "type": "string",
                "description": "For 'create': local start time as YYYY-MM-DD HH:MM (24 hour), e.g. 2026-09-15 14:30."
              },
              "end": {
                "type": "string",
                "description": "For 'create': local end time as YYYY-MM-DD HH:MM. Defaults to one hour after start."
              },
              "location": { "type": "string", "description": "For 'create': optional location." },
              "description": { "type": "string", "description": "For 'create': optional notes." },
              "event_id": {
                "type": "string",
                "description": "For 'delete': the id shown in square brackets by 'list'."
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
            "create" -> create(args)
            "delete" -> delete(args)
            null -> "Error: Missing 'action'. One of: list, create, delete."
            else -> "Error: Unknown action '$action'. One of: list, create, delete."
        }
    }.getOrElse { "Error: ${it.message}" }

    private fun missing(vararg permissions: String): String? {
        val absent = permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (absent.isEmpty()) return null
        return "Error: Calendar permission not granted. Tell the user to open Settings in this " +
            "app and grant calendar access under Phone access, then ask again. " +
            "(missing: ${absent.joinToString { it.substringAfterLast('.') }})"
    }

    private fun list(args: Map<*, *>): String {
        missing(Manifest.permission.READ_CALENDAR)?.let { return it }

        val daysAhead = (args["days_ahead"] as? Number)?.toInt() ?: 7
        val daysBack = (args["days_back"] as? Number)?.toInt() ?: 0
        val now = System.currentTimeMillis()
        val from = now - daysBack * DAY_MS
        // days_ahead 0 means "what is on now", which still needs a window with width.
        val to = now + (if (daysAhead <= 0) 1 else daysAhead) * DAY_MS

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )

        // Instances rather than Events: a weekly meeting is one Event row but the user is asking
        // about the occurrences, and Instances expands recurrence for us.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, from)
            ContentUris.appendId(this, to)
        }.build()

        val rows = mutableListOf<String>()
        context.contentResolver.query(
            uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < 60) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)"
                val begin = cursor.getLong(2)
                val end = cursor.getLong(3)
                val location = cursor.getString(4)?.takeIf { it.isNotBlank() }
                val allDay = cursor.getInt(5) == 1
                val calendar = cursor.getString(6)?.takeIf { it.isNotBlank() }

                rows.add(buildString {
                    append("- $title [$id]\n")
                    append("  ")
                    if (allDay) {
                        // All-day instances are stored in UTC midnight, so formatting them
                        // locally slides them a day in either direction.
                        append(utcDay(begin)).append(" (all day)")
                    } else {
                        append(stamp(begin)).append(" - ").append(timeOnly(end))
                    }
                    calendar?.let { append(" · ").append(it) }
                    append("\n")
                    location?.let { append("  at $it\n") }
                })
            }
        } ?: return "Error: Could not read the calendar provider."

        if (rows.isEmpty()) {
            return "No events between ${stamp(from)} and ${stamp(to)}."
        }
        return buildString {
            append("${rows.size} event${if (rows.size == 1) "" else "s"} ")
            append("between ${stamp(from)} and ${stamp(to)}:\n\n")
            rows.forEach(::append)
        }
    }

    private fun create(args: Map<*, *>): String {
        missing(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            ?.let { return it }

        val title = (args["title"] as? String)?.takeIf { it.isNotBlank() }
            ?: return "Error: 'title' is required for create."
        val startText = (args["start"] as? String)
            ?: return "Error: 'start' is required for create, as YYYY-MM-DD HH:MM."
        val start = parseLocal(startText)
            ?: return "Error: Could not read start '$startText'. Use YYYY-MM-DD HH:MM."
        val end = (args["end"] as? String)?.let { parseLocal(it) } ?: (start + 60 * 60_000)
        if (end <= start) return "Error: 'end' is not after 'start'."

        val calendarId = writableCalendarId()
            ?: return "Error: No writable calendar on this device. The user needs at least one " +
                "account set up in the Calendar app."

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            (args["location"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            (args["description"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return "Error: The calendar provider refused the event."
        val id = uri.lastPathSegment
        return "Added \"$title\" [$id] on ${stamp(start)} - ${timeOnly(end)}."
    }

    private fun delete(args: Map<*, *>): String {
        missing(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            ?.let { return it }
        val id = (args["event_id"] as? String)?.toLongOrNull()
            ?: return "Error: 'event_id' is required for delete. Use action 'list' to find it."
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val deleted = context.contentResolver.delete(uri, null, null)
        return if (deleted > 0) "Deleted event $id." else "Error: No event with id $id."
    }

    /** The first calendar this app is actually allowed to write to. */
    private fun writableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        var fallback: Long? = null
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val access = cursor.getInt(1)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (cursor.getInt(2) == 1) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    private fun parseLocal(text: String): Long? {
        val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")
        for (pattern in patterns) {
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                return format.parse(text.trim())?.time
            }
        }
        Log.w(TAG, "Unparseable date: $text")
        return null
    }

    private fun stamp(ms: Long) =
        SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ms))

    private fun timeOnly(ms: Long) =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun utcDay(ms: Long) =
        SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ms))

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
