package com.spoonwork.timetracker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** 위젯 목록: 할 일 + 오늘 일정 */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private data class Row(
        val type: Int,          // 0 제목줄, 1 할 일, 2 일정
        val text: String,
        val done: Boolean = false,
        val hour: Int = -1
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

        private fun build() {
            val today = Data.trackerDay()
            val cache = Data.cache(ctx)?.takeIf { it.optString("date") == today }
            if (cache == null) {
                rows = emptyList(); return
            }
            val out = mutableListOf<Row>()

            val todos = cache.optJSONArray("todos")
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

            val plans = cache.optJSONObject("plans")
            val checks = cache.optJSONObject("checks")
            if (plans != null) {
                val planRows = mutableListOf<Row>()
                val keys = plans.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val h = k.toIntOrNull() ?: continue
                    val text = plans.optString(k)
                    if (text.isBlank()) continue
                    planRows.add(Row(2, text, checks?.optBoolean(k) ?: false, h))
                }
                if (planRows.isNotEmpty()) {
                    out.add(Row(0, "일정"))
                    out.addAll(planRows.sortedBy { it.hour })
                }
            }
            rows = out
        }

        override fun getCount(): Int = rows.size
        override fun getViewTypeCount(): Int = 2
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
            if (r.type == 0) {
                return RemoteViews(ctx.packageName, R.layout.row_header).apply {
                    setTextViewText(R.id.text, r.text)
                }
            }
            val dark = Data.dark(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.row_item)
            val textColor = when {
                r.done -> Color.parseColor("#9CA3AF")
                dark -> Color.WHITE
                else -> Color.parseColor("#1F2937")
            }
            rv.setTextColor(R.id.text, textColor)
            rv.setTextViewText(R.id.text, r.text)
            val fill = Intent()
            if (r.type == 1) {
                rv.setTextViewText(R.id.lead, if (r.done) "☑" else "☐")
                rv.setTextColor(R.id.lead, if (r.done) Color.parseColor("#10B981") else textColor)
                fill.putExtra("type", "todo").putExtra("text", r.text)
            } else {
                rv.setTextViewText(R.id.lead, String.format("%02d시", r.hour % 24))
                rv.setTextColor(R.id.lead, if (dark) Color.parseColor("#D1D5DB") else Color.parseColor("#6B7280"))
                fill.putExtra("type", "plan").putExtra("hour", r.hour)
            }
            rv.setOnClickFillInIntent(R.id.row, fill)
            return rv
        }
    }
}
