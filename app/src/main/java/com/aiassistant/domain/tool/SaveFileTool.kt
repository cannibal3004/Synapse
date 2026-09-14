package com.aiassistant.domain.tool

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.aiassistant.domain.service.ToolManager
import com.google.ai.edge.litertlm.OpenApiTool
import java.io.File
import javax.inject.Inject

private const val TAG = "SaveFileTool"

/** Where saved files land, under the shared Downloads folder. */
private const val SUBFOLDER = "Synapse"

/** Past this a "file" is really a symptom of the model pasting its whole context in. */
private const val MAX_CHARS = 2_000_000

/**
 * Writes a file the user can actually open.
 *
 * Everything the assistant produced until now was text in a transcript, or a file inside
 * Termux's private home directory, which no file manager shows. This puts it in shared
 * Downloads, so it appears in the Files app, the Downloads notification shade entry, and
 * anything the user might want to attach it to.
 *
 * MediaStore rather than a raw path: on Android 10 and later an app cannot write to shared
 * storage directly, and MediaStore needs no permission for its own inserts. Below that there is
 * no MediaStore Downloads collection, so the fallback is the app's own external directory --
 * still visible over USB, without asking for the legacy storage permission.
 */
class SaveFileTool @Inject constructor(
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
          "name": "save_file",
          "description": "Save text to a real file in the user's Downloads folder, where they can open it, share it or attach it elsewhere. Use it whenever the answer is something to keep rather than something to read in a chat: a CSV of results, a written report, a script, extracted data, notes. Say what you saved and where afterwards. For anything that only needs reading once, just reply normally instead.",
          "parameters": {
            "type": "object",
            "properties": {
              "filename": {
                "type": "string",
                "description": "REQUIRED. Name with an extension, e.g. 'tide-times.csv' or 'summary.md'. No folders. If the name is taken a number is appended rather than overwriting."
              },
              "content": {
                "type": "string",
                "description": "REQUIRED. The complete text to write. Write the whole file, not a fragment."
              },
              "mime_type": {
                "type": "string",
                "description": "Optional. Inferred from the extension when omitted."
              }
            },
            "required": ["filename", "content"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String = runCatching {
        val args = JsonUtils.parseToJsonMap(paramsJsonString)
        val rawName = (args["filename"] as? String)?.takeIf { it.isNotBlank() }
            ?: return "Error: 'filename' is required."
        val content = args["content"] as? String
            ?: return "Error: 'content' is required."
        if (content.length > MAX_CHARS) {
            return "Error: content is ${content.length} characters; the limit is $MAX_CHARS."
        }

        val name = sanitise(rawName)
        val mime = (args["mime_type"] as? String)?.takeIf { it.isNotBlank() } ?: mimeFor(name)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(name, mime, content)
        } else {
            saveToAppDirectory(name, content)
        }
    }.getOrElse {
        Log.e(TAG, "Save failed", it)
        "Error: Could not save the file: ${it.message}"
    }

    /**
     * Strips anything that would make this a path rather than a name.
     *
     * A model asked for "notes.md" will occasionally produce "../notes.md" or an absolute path,
     * and MediaStore would take the last segment silently. Being explicit is cheaper than
     * wondering later where a file went.
     */
    private fun sanitise(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
            .trim()
            .trimStart('.')
        return base.takeIf { it.isNotEmpty() } ?: "synapse-output.txt"
    }

    private fun saveViaMediaStore(name: String, mime: String, content: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            // Hides the row from other apps until the bytes are actually there, so nothing reads
            // a half-written file.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "Error: The system would not create the file."

        val bytes = content.toByteArray()
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return "Error: The system would not open the file for writing."

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        // MediaStore renames on collision, so report what it actually called the file rather
        // than what was asked for.
        val actual = resolver.query(
            uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name

        return "Saved ${bytes.size} bytes to Downloads/$SUBFOLDER/$actual. " +
            "The user can open it from their Files app."
    }

    private fun saveToAppDirectory(name: String, content: String): String {
        val dir = File(context.getExternalFilesDir(null), SUBFOLDER).apply { mkdirs() }
        var target = File(dir, name)
        var n = 1
        while (target.exists()) {
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            target = File(dir, "$stem ($n)" + if (ext.isEmpty()) "" else ".$ext")
            n++
        }
        target.writeText(content)
        return "Saved ${target.length()} bytes to ${target.absolutePath}."
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "json" -> "application/json"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        "py" -> "text/x-python"
        "js" -> "text/javascript"
        "kt", "java", "sh", "log", "txt", "" -> "text/plain"
        else -> "text/plain"
    }
}
