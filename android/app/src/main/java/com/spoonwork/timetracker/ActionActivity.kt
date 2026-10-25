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
                "todo" -> toggleTodo(applicationContext, intent.getStringExtra("text") ?: "",
                    intent.getStringExtra("day")?.takeIf { it.isNotEmpty() })
                "plan" -> startActivity(
                    Intent(this, QuickActivity::class.java)
                        .putExtra("mode", "plan")
                        .putExtra("hour", intent.getIntExtra("hour", -1))
                        .putExtra("day", intent.getStringExtra("day") ?: "")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                "wt" -> {
                    val ops = JSONArray().put(Data.op("wt_set", intent.getStringExtra("day") ?: Data.trackerDay())
                        .put("text", intent.getStringExtra("text") ?: "").put("done", intent.getBooleanExtra("done", true)))
                    val ctx = applicationContext
                    Data.applyOpsToCache(ctx, ops)
                    TrackerWidget.render(ctx)
                    Thread {
                        try { Data.sendOps(ctx, ops) } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(ctx, "작업 스프레드 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        TrackerWidget.refresh(ctx)
                    }.start()
                }
                "tdel", "wtdel" -> {
                    val d = intent.getStringExtra("day")?.takeIf { it.isNotEmpty() } ?: Data.trackerDay()
                    val text = intent.getStringExtra("text") ?: ""
                    val ops = JSONArray().put(Data.op(if (intent.getStringExtra("type") == "wtdel") "wt_del" else "todo_del", d)
                        .put("text", text))
                    val ctx = applicationContext
                    Data.applyOpsToCache(ctx, ops)
                    TrackerWidget.render(ctx)
                    Toast.makeText(ctx, "지웠어요: $text", Toast.LENGTH_SHORT).show()
                    Thread {
                        try { Data.sendOps(ctx, ops) } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(ctx, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        TrackerWidget.refresh(ctx)
                    }.start()
                }
                "tedit" -> startActivity(
                    Intent(this, QuickActivity::class.java)
                        .putExtra("mode", "todo")
                        .putExtra("day", intent.getStringExtra("day") ?: "")
                        .putExtra("edit", intent.getStringExtra("text") ?: "")
                        .putExtra("work", intent.getBooleanExtra("work", false))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                "wfilter" -> {
                    val wid = intent.getIntExtra("wid", -1)
                    val pr = Data.prefs(applicationContext)
                    pr.edit().putBoolean("wonly_$wid", !pr.getBoolean("wonly_$wid", false)).apply()
                    TrackerWidget.render(applicationContext)
                }
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
        fun toggleTodo(ctx: Context, text: String, dayArg: String? = null) {
            if (text.isEmpty()) return
            val day = dayArg ?: Data.trackerDay()
            var done = true
            val c0 = Data.cache(ctx)
            val arr0 = if (day == Data.trackerDay()) c0?.optJSONArray("todos")
                else c0?.optJSONObject("days")?.optJSONObject(day)?.optJSONArray("t")
            arr0?.let { arr ->
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
