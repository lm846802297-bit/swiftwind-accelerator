package com.swiftwind.accelerator

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃记录：只写入应用专属目录，不上传。
 * 路径：Android/data/com.swiftwind.accelerator/files/crash-logs/
 */
object CrashLogger {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(appContext.getExternalFilesDir(null), "crash-logs").apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "crash-$timestamp.txt").writeText(
                    buildString {
                        appendLine("time=$timestamp")
                        appendLine("thread=${thread.name}")
                        appendLine(Log.getStackTraceString(throwable))
                    }
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
