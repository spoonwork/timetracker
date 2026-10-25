package com.spoonwork.timetracker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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

    /** 내려받아서 설치 창 띄우기 (백그라운드 스레드에서 호출) */
    fun install(c: Context, onProgress: (String) -> Unit) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            var u = APK_URL
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = URL(u).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 6) {
                    u = conn.getHeaderField("Location"); conn.disconnect(); continue
                }
                break
            }
            onProgress("내려받는 중…")
            session.openWrite("app.apk", 0, -1).use { out ->
                conn.inputStream.use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(c, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, 77, intent, flags)
            onProgress("설치 준비 완료")
            session.commit(pi.intentSender)
        }
    }
}

/** 설치 확인 창 띄우기 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
        }
    }
}
