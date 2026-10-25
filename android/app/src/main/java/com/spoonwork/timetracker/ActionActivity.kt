package com.spoonwork.timetracker

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.widget.Toast
import org.json.JSONArray

/** 위젯 목록을 눌렀을 때: 할 일 체크 / 일정 고치기 / 캘린더 일정 열기 */
class ActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (intent.getStringExtra("type")) {
                "todo" -> toggleTodo(applicationContext, intent.getStringExtra("text") ?: "")
                "plan" -> startActivity(
                    Intent(this, QuickActivity::class.java)
                        .putExtra("mode", "plan")
                        .putExtra("hour", intent.getIntExtra("hour", -1))
                        .putExtra("day", intent.getStringExtra("day") ?: "")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                "gcal" -> {
                    val id = intent.getLongExtra("id", -1)
                    val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, intent.getLongExtra("begin", 0))
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, intent.getLongExtra("end", 0))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
                "calendar" -> {
                    val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                    ContentUris.appendId(uri, System.currentTimeMillis())
                    startActivity(Intent(Intent.ACTION_VIEW, uri.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "열 수 없어요: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun toggleTodo(ctx: Context, text: String) {
            if (text.isEmpty()) return
            val day = Data.trackerDay()
            var done = true
            Data.cache(ctx)?.optJSONArray("todos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    if (t.optString("text") == text) { done = !t.optBoolean("done"); break }
                }
            }
            val ops = JSONArray().put(Data.op("todo_set", day).put("text", text).put("done", done))
            Data.applyOpsToCache(ctx, ops)
            TrackerWidget.render(ctx)
            Thread {
                try {
                    Data.sendOps(ctx, ops)
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(ctx, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                TrackerWidget.refresh(ctx)
            }.start()
        }
    }
}
