package com.spoonwork.timetracker

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 최신 릴리스 확인 → 앱 안에서 바로 업데이트 */
object Updater {
    private const val API = "https://api.github.com/repos/spoonwork/timetracker/releases/latest"
    const val APK_URL = "https://github.com/spoonwork/timetracker/releases/latest/download/timetracker-widget.apk"

    fun currentVersion(c: Context): Int =
        if (Build.VERSION.SDK_INT >= 28) c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt()
        else @Suppress("DEPRECATION") c.packageManager.getPackageInfo(c.packageName, 0).versionCode

    /** 최신 버전 번호 (태그 widget-vN), 실패하면 -1 */
    fun latestVersion(): Int = try {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        j.optString("tag_name").removePrefix("widget-v").toIntOrNull() ?: -1
    } catch (e: Exception) {
        -1
    }
}
