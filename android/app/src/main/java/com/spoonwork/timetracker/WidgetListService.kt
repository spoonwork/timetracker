package com.spoonwork.timetracker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.util.Calendar
import java.util.Locale

/** 데일리 위젯 목록: 할 일 + 앞으로 7일 일정 (트래커 일정 + 구글 캘린더) */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private data class Row(
        val type: Int,              // 0 제목줄, 1 할 일, 2 일정
        val text: String,
        val done: Boolean = false,
        val time: String = "",
        val rel: String = "",
        val color: Int = Art.PLAN_COLOR,
        val hour: Int = -1,         // 트래커 일정이면 시각
        val day: String = "",
        val eventId: Long = -1,
        val begin: Long = 0,
        val end: Long = 0,
        val past: Boolean = false
    )

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            try {
                build()
            } catch (e: Throwable) {
                rows = listOf(Row(0, "목록 오류: ${e.javaClass.simpleName} ${e.message ?: ""}"))
            }
        }

        private fun hmText(ms: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }

        private fun relText(begin: Long, end: Long, now: Long): String {
            if (now >= end) return "지남"
            if (now >= begin) return "진행 중"
            val min = (begin - now) / 60000
            return when {
                min < 60 -> "${min}분 후"
                min < 24 * 60 -> "${min / 60}시간 후"
                else -> "${(min + 12 * 60) / (24 * 60)}일 후"
            }
        }

        private fun build() {
            val today = Data.trackerDay()
            val cache = Data.cache(ctx)?.takeIf { it.optString("date") == today }
            val out = mutableListOf<Row>()
            val now = System.currentTimeMillis()

            // 할 일
            val todos = cache?.optJSONArray("todos")
            val todoRows = mutableListOf<Row>()
            if (todos != null) {
                for (i in 0 until todos.length()) {
                    val t = todos.optJSONObject(i) ?: continue
                    val text = t.optString("text")
                    if (text.isBlank()) continue
                    todoRows.add(Row(1, text, t.optBoolean("done")))
                }
            }
            if (todoRows.isNotEmpty()) {
                val done = todoRows.count { it.done }
                out.add(Row(0, if (done == todoRows.size) "할 일 ✓ 목표 달성!" else "할 일  $done/${todoRows.size}"))
                out.addAll(todoRows)
            }

            // 일정: 오늘부터 7일
            val last = Data.shift(today, 6)
            val events = Cal.events(ctx, today, last)
            val days = cache?.optJSONObject("days")
            val checks = cache?.optJSONObject("checks")
            for (n in 0..6) {
                val d = Data.shift(today, n)
                val list = mutableListOf<Row>()
                // 트래커 일정 (PLAN)
                val plans = days?.optJSONObject(d)?.optJSONObject("p")
                    ?: if (n == 0) cache?.optJSONObject("plans") else null
                if (plans != null) {
                    val ks = plans.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        val h = k.toIntOrNull() ?: continue
                        val text = plans.optString(k)
                        if (text.isBlank()) continue
                        val b = Data.toCal(d).timeInMillis + h * 3600_000L
                        val done = n == 0 && (checks?.optBoolean(k) ?: false)
                        list.add(Row(2, text, done, String.format(Locale.US, "%02d시", h % 24),
                            if (done) "완료" else relText(b, b + 3600_000L, now), Art.PLAN_COLOR,
                            hour = h, day = d, begin = b, end = b + 3600_000L, past = done || now >= b + 3600_000L))
                    }
                }
                // 구글 캘린더
                for (e in events) {
                    if (!e.days().contains(d)) continue
                    val color = if (e.color == 0) Color.parseColor("#7986CB") else (e.color or 0xFF000000.toInt())
                    val time = if (e.allDay) "하루 종일" else "${hmText(e.begin)} ~ ${hmText(e.end)}"
                    val rel = if (e.allDay) (if (n == 0) "오늘" else "${n}일 후") else relText(e.begin, e.end, now)
                    list.add(Row(2, e.title, false, time, rel, color, eventId = e.id,
                        begin = if (e.allDay) Data.toCal(d).timeInMillis else e.begin, end = e.end,
                        past = !e.allDay && now >= e.end))
                }
                if (list.isEmpty()) continue
                val head = when (n) {
                    0 -> "오늘 · " + Data.prettyDay(d)
                    1 -> "내일 · " + Data.prettyDay(d)
                    else -> Data.prettyDay(d)
                }
                out.add(Row(0, head))
                out.addAll(list.sortedBy { it.begin })
            }
            rows = out
        }

        override fun getCount(): Int = rows.size
        override fun getViewTypeCount(): Int = 3
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews = try {
            view(position)
        } catch (e: Throwable) {
            RemoteViews(ctx.packageName, R.layout.row_header).apply {
                setTextViewText(R.id.text, "표시 오류: ${e.javaClass.simpleName}")
            }
        }

        private fun view(position: Int): RemoteViews {
            val r = rows.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.row_header)
            val dark = Data.lightText(ctx)
            val mainC = if (dark) Color.WHITE else Color.parseColor("#1F2937")
            val subC = if (dark) Color.parseColor("#D1D5DB") else Color.parseColor("#6B7280")
            val doneC = Color.parseColor("#9CA3AF")
            when (r.type) {
                0 -> return RemoteViews(ctx.packageName, R.layout.row_header).apply {
                    setTextViewText(R.id.text, r.text)
                }
                1 -> {
                    val rv = RemoteViews(ctx.packageName, R.layout.row_item)
                    rv.setTextColor(R.id.text, if (r.done) doneC else mainC)
                    rv.setTextViewText(R.id.text, r.text)
                    rv.setTextViewText(R.id.lead, if (r.done) "☑" else "☐")
                    rv.setTextColor(R.id.lead, if (r.done) Color.parseColor("#10B981") else mainC)
                    rv.setOnClickFillInIntent(R.id.row, Intent().putExtra("type", "todo").putExtra("text", r.text))
                    return rv
                }
                else -> {
                    val rv = RemoteViews(ctx.packageName, R.layout.row_event)
                    rv.setInt(R.id.bar, "setBackgroundColor", r.color)
                    rv.setTextViewText(R.id.title, r.text)
                    rv.setTextColor(R.id.title, if (r.past) doneC else mainC)
                    rv.setTextViewText(R.id.time, r.time)
                    rv.setTextColor(R.id.time, subC)
                    rv.setTextViewText(R.id.rel, r.rel)
                    rv.setTextColor(R.id.rel, subC)
                    val fill = Intent()
                    if (r.eventId >= 0) {
                        fill.putExtra("type", "gcal").putExtra("id", r.eventId)
                            .putExtra("begin", r.begin).putExtra("end", r.end)
                    } else {
                        fill.putExtra("type", "plan").putExtra("hour", r.hour).putExtra("day", r.day)
                    }
                    rv.setOnClickFillInIntent(R.id.row, fill)
                    return rv
                }
            }
        }
    }
}
