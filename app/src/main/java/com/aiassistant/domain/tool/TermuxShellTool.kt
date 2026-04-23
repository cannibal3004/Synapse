package com.aiassistant.domain.tool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.aiassistant.domain.service.ToolManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class TermuxShellTool @Inject constructor(
    private val context: Context
) {
    private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val executionId = AtomicInteger(0)
    private val resultReceiver = TermuxResultReceiver()

    inner class TermuxResultReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val execId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
            android.util.Log.d("TermuxShellTool", "Broadcast received (execId=$execId)")
            if (execId == -1) return

            val deferred = pendingResults.remove(execId) ?: run {
                android.util.Log.w("TermuxShellTool", "No pending result for execId=$execId")
                return
            }

            val resultBundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
                ?: run {
                    deferred.complete("Error: No result bundle received from Termux")
                    return
                }

            val stdout = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "")
            val stderr = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR, "")
            val exitCode = resultBundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, -1)
            val err = resultBundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_ERR, 0)
            val errmsg = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG, "")

            android.util.Log.d("TermuxShellTool", "Bundle: stdout=${stdout.take(80)} exitCode=$exitCode err=$err errmsg=$errmsg")

            val output = buildString {
                val hasRealError = err != 0 && (!errmsg.isNullOrBlank() || exitCode != 0)
                if (hasRealError) {
                    append("Error: Termux execution failed (err=$err)")
                    if (!errmsg.isNullOrBlank()) append(": $errmsg")
                    if (!stderr.isNullOrBlank()) append("\nSTDERR: $stderr")
                    if (!stdout.isNullOrBlank()) append("\nSTDOUT: $stdout")
                    append("\n\nTroubleshooting:\n")
                    append("1. Ensure 'allow-external-apps = true' in ~/.termux/termux.properties\n")
                    append("2. Grant RUN_COMMAND permission: Settings > Apps > Synapse > Additional permissions\n")
                    append("3. Restart Termux after changes")
                } else {
                    if (!stdout.isNullOrBlank()) append(stdout)
                    if (!stderr.isNullOrBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("STDERR:\n$stderr")
                    }
                    append("\n\nExit code: $exitCode")
                }
            }
            deferred.complete(output)
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

        private const val RESULT_ACTION = "com.aiassistant.TERMUX_RESULT"
        private const val MAX_TIMEOUT_MS = 120_000L
    }

    init {
        val filter = IntentFilter(RESULT_ACTION)
        ContextCompat.registerReceiver(
            context,
            resultReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        register()
    }

   fun register() {
        ToolManager.registerTool(
            ToolManager.ToolDefinition(
                name = "termux_shell",
                description = "Execute commands in a full Linux shell (Termux). This gives you access to a complete Linux environment on the device. Use this for: network diagnostics (ping, curl, wget, nslookup, dig, traceroute, netstat, ss), file operations (ls, cat, grep, find, cp, mv, rm, mkdir, tar, zip, unzip, diff, wc, head, tail), system info (uname, df, free, top, ps, whoami, id, hostname, uptime), text processing (sed, awk, sort, uniq, tr, cut, xargs), package management (pkg, apt), Python/Node scripts, and any other Linux command-line task. This is a powerful tool for diagnosing issues, fetching data, processing files, and running scripts. Commands run synchronously with a timeout (default 30s, max 120s). Avoid long-running or interactive commands that would block indefinitely.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "command" to mapOf(
                            "type" to "string",
                            "description" to "The interpreter to use. Default: 'bash'. Use 'python3', 'node' etc. for specific interpreters."
                        ),
                        "shell_command" to mapOf(
                            "type" to "string",
                            "description" to "REQUIRED: The shell command to run. Passed to 'bash -c'. Examples: 'ping -c 4 8.8.8.8', 'curl -s https://api.example.com', 'ls -la /sdcard', 'grep -r \"error\" *.log'."
                        ),
                        "script" to mapOf(
                            "type" to "string",
                            "description" to "Alternative to shell_command: multi-line script content passed via stdin. Use for complex Python/Node scripts."
                        ),
                        "workdir" to mapOf(
                            "type" to "string",
                            "description" to "Working directory. Defaults to ~. Use ~/path or /absolute/path"
                        ),
                        "timeout" to mapOf(
                            "type" to "integer",
                            "description" to "Timeout in seconds. Default: 30, max: 120"
                        )
                    ),
                    "required" to listOf("shell_command")
                ),
                executor = { arguments ->
                    runCatching {
                        val args = com.google.gson.Gson().fromJson(arguments, Map::class.java)
                        val command = args["command"] as? String ?: "bash"
                        val shellCommand = args["shell_command"] as? String
                        val script = args["script"] as? String ?: null
                        val workdir = args["workdir"] as? String ?: null
                        val timeoutSeconds = (args["timeout"] as? Number)?.toInt() ?: 30

                        if (!shellCommand.isNullOrBlank()) {
                            android.util.Log.d("TermuxShellTool", "Executing: cmd=$command args=-c $shellCommand workdir=$workdir timeout=${timeoutSeconds}s")
                            executeCommand(command, "-c $shellCommand", null, workdir, timeoutSeconds)
                        } else if (!script.isNullOrBlank()) {
                            android.util.Log.d("TermuxShellTool", "Executing: cmd=$command script=${script.length} chars workdir=$workdir timeout=${timeoutSeconds}s")
                            executeCommand(command, "", script, workdir, timeoutSeconds)
                        } else {
                            throw IllegalArgumentException("Missing 'shell_command' parameter. You must provide a command to run.")
                        }
                    }
                }
            )
        )
    }

    fun executeCommand(
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
        val deferred = CompletableDeferred<String>()
        pendingResults[id] = deferred

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
                withTimeoutOrNull(timeout) {
                    deferred.await()
                }
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
