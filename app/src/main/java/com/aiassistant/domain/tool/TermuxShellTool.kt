package com.aiassistant.domain.tool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.aiassistant.domain.service.ToolManager
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Runs a command in Termux and waits for its output.
 *
 * The one implementation for every caller: the hosted-model path reaches it through
 * [ToolManager] or [ToolExecutor], the on-device engine through [OpenApiTool] in the `:llm`
 * process. It used to be two near-identical classes carrying two copies of the schema, and they
 * drifted -- the hosted side read a parameter the schema never declared, so no command reached
 * Termux at all on that path. One class cannot disagree with itself.
 *
 * Being an [OpenApiTool] also makes the schema below the only description of the tool: the
 * hosted path derives its definition from that same JSON instead of restating it as a map.
 *
 * An instance per process is expected. Each registers a receiver on its own [RESULT_ACTION]
 * and keeps its own execution ids -- see that constant for why the two must not be shared.
 */
class TermuxShellTool @Inject constructor(
    private val context: Context
) : OpenApiTool {

    /** One in-flight command, accumulated over however many broadcasts Termux sends back. */
    private class Pending(
        val result: CompletableDeferred<String> = CompletableDeferred(),
        val stdout: StringBuilder = StringBuilder(),
        val stderr: StringBuilder = StringBuilder(),
        var exitCode: Int = -1,
        var err: Int = 0,
        var errmsg: String? = null
    )

    private val pendingResults = ConcurrentHashMap<Int, Pending>()
    private val executionId = AtomicInteger(0)
    private val resultReceiver = TermuxResultReceiver()

    init {
        ContextCompat.registerReceiver(
            context,
            resultReceiver,
            IntentFilter(RESULT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        register()
    }

    inner class TermuxResultReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val execId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
            android.util.Log.d("TermuxShellTool", "Broadcast received (execId=$execId)")
            if (execId == -1) return

            val pending = pendingResults[execId] ?: run {
                android.util.Log.w("TermuxShellTool", "No pending result for execId=$execId")
                return
            }

            val resultBundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
            if (resultBundle == null) {
                pendingResults.remove(execId)
                pending.result.complete("Error: No result bundle received from Termux")
                return
            }

            val stdout = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "")
            val stderr = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR, "")
            val exitCode = resultBundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, -1)
            val err = resultBundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_ERR, 0)
            val errmsg = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG, "")

            android.util.Log.d(
                "TermuxShellTool",
                "Bundle: stdout=${stdout.take(80)} stderr=${stderr.take(80)} " +
                    "exitCode=$exitCode err=$err errmsg=$errmsg"
            )

            if (stdout.isNotBlank()) pending.stdout.append(stdout)
            if (stderr.isNotBlank()) pending.stderr.append(stderr)
            if (exitCode >= 0) pending.exitCode = exitCode
            if (err != 0) pending.err = err
            if (errmsg.isNotBlank()) pending.errmsg = errmsg

            // Output can arrive over more than one broadcast, so the command is finished only
            // once an exit code lands. A plugin-level failure never produces one -- Termux
            // reports err with exitCode -1 -- so that ends the wait too, or an unrunnable
            // command would sit here until the timeout rather than saying why.
            if (exitCode < 0 && err == 0) return

            pendingResults.remove(execId)
            val output = pending.render()
            android.util.Log.d(
                "TermuxShellTool",
                "Final result (execId=$execId, length=${output.length})"
            )
            pending.result.complete(output)
        }
    }

    private fun Pending.render(): String = buildString {
        val hasRealError = err != 0 && (!errmsg.isNullOrBlank() || exitCode != 0)
        if (hasRealError) {
            append("Error: Termux execution failed (err=$err)")
            if (!errmsg.isNullOrBlank()) append(": $errmsg")
            if (stderr.isNotEmpty()) append("\nSTDERR: $stderr")
            if (stdout.isNotEmpty()) append("\nSTDOUT: $stdout")
            append("\n\nTroubleshooting:\n")
            append("1. Ensure 'allow-external-apps = true' in ~/.termux/termux.properties\n")
            append("2. Grant RUN_COMMAND permission: Settings > Apps > Synapse > Additional permissions\n")
            append("3. Restart Termux after changes")
        } else {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("STDERR:\n$stderr")
            }
            append("\n\nExit code: $exitCode")
        }
    }

    companion object {
        private const val EXTRA_EXECUTION_ID = "com.aiassistant.termux.execution_id"

        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_SERVICE_NAME = "com.termux.app.RunCommandService"
        private const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"
        private const val TERMUX_PREFIX_DIR = "/data/data/com.termux/files/usr"
        private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"

        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT = "stdout"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR = "stderr"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE = "exitCode"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERR = "err"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG = "errmsg"

        /**
         * Scoped to this process.
         *
         * The on-device engine runs in `:llm` while everything else runs in the main process,
         * so two instances can be alive at once. A result broadcast is delivered to the whole
         * package, and both instances number their commands from zero -- so a reply meant for
         * one could be claimed by the other's identically numbered command. The pid keeps each
         * process listening only to its own.
         */
        private val RESULT_ACTION =
            "com.aiassistant.TERMUX_RESULT.${android.os.Process.myPid()}"

        private const val MAX_TIMEOUT_MS = 120_000L

        private val KNOWN_SHELLS = setOf("bash", "sh", "zsh", "dash")
    }

    /** Registers with [ToolManager] from the same schema the on-device path reads. */
    fun register() {
        ToolManager.registerOpenApiTool(this)
    }

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "termux_shell",
          "description": "Execute commands in a full Linux shell (Termux). This gives you access to a complete Linux environment on the device. Use this for: network diagnostics (ping, curl, wget, nslookup, dig, traceroute, netstat, ss), file operations (ls, cat, grep, find, cp, mv, rm, mkdir, tar, zip, unzip, diff, wc, head, tail), system info (uname, df, free, top, ps, whoami, id, hostname, uptime), text processing (sed, awk, sort, uniq, tr, cut, xargs), package management (pkg, apt), Python/Node scripts, and any other Linux command-line task. This is a powerful tool for diagnosing issues, fetching data, processing files, and running scripts. Commands run synchronously with a timeout (default 30s, max 120s). Avoid long-running or interactive commands that would block indefinitely.",
          "parameters": {
            "type": "object",
            "properties": {
              "command": {
                "type": "string",
                "description": "The interpreter for 'script'. Default: 'bash'. Use 'python3', 'node' etc. 'shell_command' always runs under a shell regardless."
              },
              "shell_command": {
                "type": "string",
                "description": "REQUIRED: The shell command to run. Passed to 'bash -c'. Examples: 'ping -c 4 8.8.8.8', 'curl -s https://api.example.com', 'ls -la /sdcard', 'grep -r \"error\" *.log'."
              },
              "script": {
                "type": "string",
                "description": "Alternative to shell_command: multi-line script content passed via stdin, run by the interpreter named in 'command'. Use for complex Python/Node scripts."
              },
              "workdir": {
                "type": "string",
                "description": "Working directory. Defaults to ~. Use ~/path or /absolute/path"
              },
              "timeout": {
                "type": "integer",
                "description": "Timeout in seconds. Default: 30, max: 120"
              }
            },
            "required": ["shell_command"]
          }
        }
    """.trimIndent()

    /**
     * Runs a `termux_shell` call from the raw JSON arguments the model produced.
     *
     * The only entry point, on purpose -- unpacking these arguments anywhere else is what broke
     * the hosted path once already.
     */
    override fun execute(paramsJsonString: String): String {
        val args = JsonUtils.parseToJsonMap(paramsJsonString)
        val command = args["command"] as? String ?: "bash"
        val shellCommand = args["shell_command"] as? String
        val script = args["script"] as? String
        val workdir = args["workdir"] as? String
        val timeoutSeconds = (args["timeout"] as? Number)?.toInt() ?: 30

        return when {
            !shellCommand.isNullOrBlank() -> {
                // shell_command is documented as going to `bash -c`, so an interpreter that is
                // not a shell does not apply to it -- python3 -c "ping 8.8.8.8" is not what was
                // asked for. Other interpreters take their code through `script`, over stdin.
                val shell = KNOWN_SHELLS.firstOrNull { it == command.lowercase() } ?: "bash"
                android.util.Log.d(
                    "TermuxShellTool",
                    "Executing: cmd=$shell args=-c $shellCommand workdir=$workdir timeout=${timeoutSeconds}s"
                )
                executeCommand(shell, "-c $shellCommand", null, workdir, timeoutSeconds)
            }
            !script.isNullOrBlank() -> {
                android.util.Log.d(
                    "TermuxShellTool",
                    "Executing: cmd=$command script=${script.length} chars workdir=$workdir timeout=${timeoutSeconds}s"
                )
                executeCommand(command, "", script, workdir, timeoutSeconds)
            }
            else -> "Error: Missing 'shell_command'. Pass the command to run, e.g. " +
                "{\"shell_command\": \"ping -c 4 8.8.8.8\"}."
        }
    }

    private fun executeCommand(
        command: String,
        argumentsStr: String,
        script: String?,
        workdir: String?,
        timeoutSeconds: Int
    ): String {
        if (!isTermuxInstalled()) {
            return "Error: Termux is not installed. Install Termux from F-Droid or GitHub, then grant the RUN_COMMAND permission to this app in Android Settings > Apps > Synapse > Additional permissions."
        }

        if (!hasRunCommandPermission()) {
            return "Error: RUN_COMMAND permission not granted. Go to Android Settings > Apps > Synapse > Additional permissions and enable 'Run commands in Termux environment'. Also ensure allow-external-apps = true in ~/.termux/termux.properties"
        }

        val timeout = (timeoutSeconds.coerceIn(1, 120) * 1000L).coerceAtMost(MAX_TIMEOUT_MS)

        val cmdPath = resolveCommandPath(command)
        val cmdArgs: Array<String> = if (argumentsStr.isNotBlank()) {
            val trimmed = argumentsStr.trim()
            if (trimmed.startsWith("-c ")) {
                arrayOf("-c", trimmed.substring(3))
            } else {
                arrayOf(trimmed)
            }
        } else {
            emptyArray()
        }

        android.util.Log.d("TermuxShellTool", "cmdPath=$cmdPath cmdArgs=${cmdArgs.contentToString()}")

        val id = executionId.incrementAndGet()
        val pending = Pending()
        pendingResults[id] = pending

        val intent = buildIntent(cmdPath, cmdArgs, script, workdir, id)
        android.util.Log.d("TermuxShellTool", "Starting Termux service (id=$id)")

        try {
            context.startService(intent)
            android.util.Log.d("TermuxShellTool", "Service started, awaiting result (timeout=${timeout}ms)")
        } catch (e: Exception) {
            pendingResults.remove(id)
            android.util.Log.e("TermuxShellTool", "Failed to start service: ${e.message}")
            return "Error: Failed to start Termux service: ${e.message}"
        }

        return try {
            runBlocking {
                withTimeoutOrNull(timeout) { pending.result.await() }
            }?.also {
                android.util.Log.d("TermuxShellTool", "Result received (id=$id, length=${it.length})")
            } ?: run {
                pendingResults.remove(id)
                android.util.Log.e("TermuxShellTool", "Timeout after ${timeoutSeconds}s (id=$id)")
                "Error: Command timed out after ${timeoutSeconds}s. The command may be hanging (e.g., interactive shell, waiting for input). Make sure you're using 'shell_command' parameter with 'bash'."
            }
        } catch (e: Exception) {
            pendingResults.remove(id)
            "Error: ${e.message}"
        }
    }

    private fun buildIntent(
        commandPath: String,
        arguments: Array<String>,
        stdin: String?,
        workdir: String?,
        id: Int
    ): Intent {
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE_NAME)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            putExtra(EXTRA_ARGUMENTS, arguments)
            putExtra(EXTRA_BACKGROUND, true)
        }

        if (!stdin.isNullOrBlank()) {
            intent.putExtra(EXTRA_STDIN, stdin)
        }
        if (!workdir.isNullOrBlank()) {
            intent.putExtra(EXTRA_WORKDIR, workdir)
        }

        val resultIntent = Intent(RESULT_ACTION).apply {
            putExtra(EXTRA_EXECUTION_ID, id)
            setPackage(context.packageName)
        }

        val flags = android.app.PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            id,
            resultIntent,
            flags
        )
        intent.putExtra(EXTRA_PENDING_INTENT, pendingIntent)

        return intent
    }

    private fun resolveCommandPath(command: String): String {
        return when {
            command.startsWith("/") -> command
            command.startsWith("~") -> command.replaceFirst("~", TERMUX_HOME_DIR)
            command.startsWith("\$PREFIX") -> command.replaceFirst("\$PREFIX", TERMUX_PREFIX_DIR)
            else -> "$TERMUX_BIN_DIR/$command"
        }
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasRunCommandPermission(): Boolean {
        return context.checkPermission(
            PERMISSION_RUN_COMMAND,
            android.os.Process.myPid(),
            android.os.Process.myUid()
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    data class TermuxStatus(
        val installed: Boolean,
        val permissionGranted: Boolean
    ) {
        val ready get() = installed && permissionGranted
    }

    fun getStatus(): TermuxStatus {
        return TermuxStatus(
            installed = isTermuxInstalled(),
            permissionGranted = hasRunCommandPermission()
        )
    }
}
