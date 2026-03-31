package com.clauderemote

import android.app.Application
import android.util.Log
import com.clauderemote.util.FileLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Bridge FileLogger to Android Logcat
        FileLogger.platformLog = { level, tag, message, throwable ->
            when (level) {
                "E" -> Log.e(tag, message, throwable)
                "W" -> Log.w(tag, message, throwable)
                else -> Log.d(tag, message)
            }
        }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        FileLogger.init(filesDir, appVersion)

        // Catch uncaught exceptions
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.error("CRASH", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
