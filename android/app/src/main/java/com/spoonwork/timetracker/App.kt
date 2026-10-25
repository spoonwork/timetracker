package com.spoonwork.timetracker

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/** 앱이 멈추면 원인을 저장해서 설정 화면에 보여줌 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                Data.prefs(this).edit().putString("crash", sw.toString().take(1500)).commit()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
