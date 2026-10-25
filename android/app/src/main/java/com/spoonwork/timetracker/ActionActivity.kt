package com.spoonwork.timetracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** 위젯 목록을 눌렀을 때: 할 일 체크 / 일정 고치기 (화면 없이 바로 처리) */
class ActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("type")) {
            "todo" -> toggleTodo(applicationContext, intent.getStringExtra("text") ?: "")
            "plan" -> startActivity(
                Intent(this, QuickActivity::class.java)
                    .putExtra("mode", "plan")
                    .putExtra("hour", intent.getIntExtra("hour", -1))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun toggleTodo(ctx: Context, text: String) {
            if (text.isEmpty()) return
            val day = Data.trackerDay()
            var newDone = true

            // 화면에 먼저 반영
            Data.cache(ctx)?.let { cache ->
                val arr = cache.optJSONArray("todos")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        if (t.optString("text") == text) {
                            newDone = !t.optBoolean("done")
                            t.put("done", newDone)
                            break
                        }
                    }
                    Data.saveCache(ctx, cache)
                    TrackerWidget.render(ctx)
                }
            }

            // 시트에 저장 (최신 데이터에 적용)
            Thread {
                try {
                    val app = Data.fetchAppData(ctx)
                    val todos = app.optJSONObject("todos") ?: JSONObject()
                    val arr = todos.optJSONArray(day) ?: JSONArray()
                    var planHour: String? = null
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        if (t.optString("text") == text) {
                            t.put("done", newDone)
                            val pk = t.optString("planKey", "")
                            if (pk.startsWith("__plan_")) planHour = pk.removePrefix("__plan_")
                            break
                        }
                    }
                    todos.put(day, arr)
                    Data.saveKey(ctx, "todos", todos)
                    // 일정 칸에서 만든 할 일이면 일정 체크도 같이
                    if (planHour != null) {
                        val checks = app.optJSONObject("hourChecks") ?: JSONObject()
                        val dayChecks = checks.optJSONObject(day) ?: JSONObject()
                        dayChecks.put(planHour, newDone)
                        checks.put(day, dayChecks)
                        Data.saveKey(ctx, "hourChecks", checks)
                    }
                    TrackerWidget.refresh(ctx)
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(ctx, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    TrackerWidget.refresh(ctx)
                }
            }.start()
        }
    }
}
