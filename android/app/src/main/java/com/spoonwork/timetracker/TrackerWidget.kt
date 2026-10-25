package com.spoonwork.timetracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONArray
import java.util.Calendar

class TrackerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
        render(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                // ↻ 직접 누르면 작업 캘린더 색까지 새로 읽기
                Data.prefs(context).edit().putBoolean("fresh_next", true).apply()
                Toast.makeText(context.applicationContext, "동기화 중…", Toast.LENGTH_SHORT).show()
                refresh(context)
                render(context)
            }
            ACTION_SHIFT -> {
                val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val delta = intent.getIntExtra("delta", 0)
                val key = if (Data.viewFor(context, wid) == "week") "woff_$wid" else "moff_$wid"
                val pr = Data.prefs(context)
                pr.edit().putInt(key, pr.getInt(key, 0) + delta).apply()
                render(context)
                refresh(context)       // 그 달 기록 받아오기
            }
            ACTION_TOGGLE_WT -> {
                val pr = Data.prefs(context)
                pr.edit().putBoolean("cal_wt", !pr.getBoolean("cal_wt", true)).apply()
                render(context)
            }
            ACTION_PICK -> {
                // 달력에서 날짜 누름 → 이 위젯을 그 날 데일리로
                val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val ds = intent.getStringExtra("day") ?: return
                Data.prefs(context).edit().putString("view_$wid", "day").putString("sel_$wid", ds).apply()
                render(context)
            }
            ACTION_NEXT_VIEW -> {
                val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val pr = Data.prefs(context)
                if (pr.getString("sel_$wid", null) != null) {
                    // 날짜 보던 중이면 달력으로 돌아가기
                    pr.edit().remove("sel_$wid").putString("view_$wid", "month").apply()
                    render(context)
                    return
                }
                val next = when (Data.viewFor(context, wid)) { "day" -> "week"; "week" -> "month"; else -> "day" }
                Data.prefs(context).edit().putString("view_$wid", next)
                    .putInt("moff_$wid", 0).putInt("woff_$wid", 0).apply()
                render(context)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        render(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.spoonwork.timetracker.REFRESH"
        const val ACTION_NEXT_VIEW = "com.spoonwork.timetracker.NEXT_VIEW"
        const val ACTION_SHIFT = "com.spoonwork.timetracker.SHIFT"
        const val ACTION_PICK = "com.spoonwork.timetracker.PICK"
        const val ACTION_TOGGLE_WT = "com.spoonwork.timetracker.TOGGLE_WT"

        /** 시트에서 오늘 요약을 받아와 위젯 다시 그림 (네트워크 보장되는 작업으로 실행) */
        fun refresh(context: Context, pending: PendingResult? = null) {
            val ctx = context.applicationContext
            Data.prefs(ctx).edit().putLong("loading_at", System.currentTimeMillis()).apply()
            RefreshJob.schedule(ctx)
            pending?.finish()
        }

        /** 실제로 받아오기 (RefreshJob 안에서 호출) */
        fun fetchNow(ctx: Context) {
            try {
                Data.prefs(ctx).edit().remove("crash_widget").apply()
                val today = Data.trackerDay()
                val (gs, ge) = Data.monthGrid(today)
                val week7 = Data.shift(today, 7)
                var from = minOf(gs, Data.weekStart(today))
                var to = maxOf(ge, week7)
                // 위젯에서 다른 달/주를 보고 있으면 그 기간도 같이
                val pr0 = Data.prefs(ctx)
                val mgr0 = AppWidgetManager.getInstance(ctx)
                for (wid in mgr0.getAppWidgetIds(ComponentName(ctx, TrackerWidget::class.java))) {
                    val mo = pr0.getInt("moff_$wid", 0)
                    if (mo != 0) {
                        val (a, b) = Data.monthGridOffset(today, mo)
                        from = minOf(from, a); to = maxOf(to, b)
                    }
                    val wo = pr0.getInt("woff_$wid", 0)
                    if (wo != 0) {
                        val ws = Data.shift(Data.weekStart(today), 7 * wo)
                        from = minOf(from, ws); to = maxOf(to, Data.shift(ws, 6))
                    }
                }
                val fresh = pr0.getBoolean("fresh_next", false)
                val j = Data.fetchSummary(ctx, today, from, to, fresh)
                if (fresh) pr0.edit().remove("fresh_next").apply()
                val pr = Data.prefs(ctx)
                if (System.currentTimeMillis() - pr.getLong("latest_at", 0L) > 6 * 3600_000L) {
                    pr.edit().putInt("latest", Updater.latestVersion())
                        .putLong("latest_at", System.currentTimeMillis()).apply()
                }
                Data.saveCache(ctx, j)
                Data.prefs(ctx).edit().remove("error").apply()
            } catch (e: java.net.UnknownHostException) {
                Data.prefs(ctx).edit().putString("error", "인터넷 연결 안 됨").apply()
            } catch (e: java.net.SocketTimeoutException) {
                Data.prefs(ctx).edit().putString("error", "응답 늦음 · ↻ 다시").apply()
            } catch (e: Throwable) {
                Data.prefs(ctx).edit().putString("error", e.javaClass.simpleName + ": " + (e.message ?: "")).apply()
            } finally {
                Data.prefs(ctx).edit().putLong("loading_at", 0L).apply()
                render(ctx)
            }
        }

        fun render(context: Context) {
            try {
                renderUnsafe(context)
            } catch (e: Throwable) {
                Data.prefs(context).edit().putString("crash", "render: " + android.util.Log.getStackTraceString(e).take(1500)).apply()
            }
        }

        private fun renderUnsafe(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TrackerWidget::class.java))
            if (ids.isEmpty()) return

            val today = Data.trackerDay()
            val cache = Data.cache(context)?.takeIf { it.optString("date") == today }
            val prefs = Data.prefs(context)
            val error = prefs.getString("error", null)
            val loadingAt = prefs.getLong("loading_at", 0L)
            val loading = loadingAt > 0 && System.currentTimeMillis() - loadingAt < 60_000

            var curOff = 0
            fun statsFor(view: String): String = when {
                cache != null && view == "month" -> {
                    val days = cache.optJSONObject("days")
                    var sum = 0
                    val mc = Data.toCal(today).apply { set(java.util.Calendar.DAY_OF_MONTH, 1); add(java.util.Calendar.MONTH, curOff) }
                    val prefix = Data.fmt(mc).substring(0, 7)
                    days?.keys()?.forEach { k -> if (k.startsWith(prefix)) sum += days.optJSONObject(k)?.optInt("w") ?: 0 }
                    (if (curOff == 0) "이번 달 작업 " else "작업 ") + Data.hm(sum / 60) + (if (error != null) "  ⚠" else "")
                }
                cache != null && view == "week" -> {
                    val days = cache.optJSONObject("days")
                    val ws = Data.shift(Data.weekStart(today), 7 * curOff)
                    var sum = 0
                    for (i in 0..6) sum += days?.optJSONObject(Data.shift(ws, i))?.optInt("w") ?: 0
                    (if (curOff == 0) "이번 주 작업 " else "작업 ") + Data.hm(sum / 60) + (if (error != null) "  ⚠" else "")
                }
                cache != null -> {
                    val work = cache.optInt("workSec") / 60
                    val sleep = cache.optInt("sleepMin")
                    "작업 ${Data.hm(work)} · 수면 ${Data.hm(sleep)}" +
                        (if (error != null) "  ⚠" else "")
                }
                error != null -> "⚠ $error"
                else -> "불러오는 중…"
            }
            val hasUpdate = prefs.getInt("latest", -1) > Updater.currentVersion(context)
            val dark = Data.dark(context)
            val light = Data.lightText(context)
            val strip = drawStrip(cache?.optJSONArray("blocks"), light)
            Art.textOverride = Data.textColor(context)
            Art.fs = Data.fontScale(context)
            Art.showSheet = prefs.getBoolean("cal_wt", true)
            val mainColor = Data.textColor(context) ?: if (light) Color.WHITE else Color.parseColor("#1F2937")
            val subColor = if (light) Color.parseColor("#E5E7EB") else Color.parseColor("#6B7280")
            val alpha = Data.bgAlpha(context) * 255 / 100

            for (id in ids) {
                val view = Data.viewFor(context, id)
                curOff = if (view == "month") prefs.getInt("moff_$id", 0) else if (view == "week") prefs.getInt("woff_$id", 0) else 0
                val isDay = view == "day"
                val sel = if (isDay) Data.selDay(context, id)?.takeIf { it != today } else null
                val selDay = cache?.optJSONObject("days")?.optJSONObject(sel ?: "")
                val stats = if (sel != null) {
                    "작업 ${Data.hm((selDay?.optInt("w") ?: 0) / 60)} · 수면 ${Data.hm(selDay?.optInt("s") ?: 0)}"
                } else statsFor(view)
                // 달력은 목록이 없는 별도 레이아웃 (목록이 겹쳐 보이지 않게)
                val rv = RemoteViews(context.packageName, if (isDay) R.layout.widget else R.layout.widget_cal)
                rv.setInt(R.id.bg, "setColorFilter", if (dark) Color.parseColor("#111827") else Color.WHITE)
                rv.setInt(R.id.bg, "setImageAlpha", alpha)
                rv.setTextColor(R.id.title, mainColor)
                rv.setTextViewTextSize(R.id.title, android.util.TypedValue.COMPLEX_UNIT_SP, 16f * Art.fs)
                rv.setTextViewTextSize(R.id.stats, android.util.TypedValue.COMPLEX_UNIT_SP, 13f * Art.fs)
                rv.setTextColor(R.id.stats, subColor)
                rv.setTextColor(R.id.btn_refresh, subColor)
                if (isDay) {
                    rv.setTextColor(R.id.empty, subColor)
                    for (chip in intArrayOf(R.id.btn_sleep, R.id.btn_block, R.id.btn_plan, R.id.btn_todo)) {
                        rv.setInt(chip, "setBackgroundResource", if (light) R.drawable.chip_bg_dark else R.drawable.chip_bg)
                        rv.setTextColor(chip, if (light) Color.WHITE else Color.parseColor("#374151"))
                    }
                }
                val tc = Data.toCal(today)
                val title = when (view) {
                    "month" -> {
                        val m = Data.toCal(today).apply { set(java.util.Calendar.DAY_OF_MONTH, 1); add(java.util.Calendar.MONTH, prefs.getInt("moff_$id", 0)) }
                        if (m.get(java.util.Calendar.YEAR) != tc.get(java.util.Calendar.YEAR)) "${m.get(java.util.Calendar.YEAR)}.${m.get(java.util.Calendar.MONTH) + 1}월"
                        else "${m.get(java.util.Calendar.MONTH) + 1}월"
                    }
                    "week" -> {
                        val ws = Data.toCal(Data.shift(Data.weekStart(today), 7 * prefs.getInt("woff_$id", 0)))
                        "${ws.get(java.util.Calendar.MONTH) + 1}/${ws.get(java.util.Calendar.DAY_OF_MONTH)}~"
                    }
                    else -> if (sel != null) "◂ " + Data.prettyDay(sel).replace("월 ", "/").replace("일 ", " ")
                        else Data.prettyDay(today).replace("월 ", "/").replace("일 ", " ")
                }
                rv.setTextViewText(R.id.title, "$title ▾")
                rv.setTextViewText(R.id.stats,
                    (if (loading && cache == null) "불러오는 중…" else stats) + if (hasUpdate) "  ⬆새 버전" else "")
                rv.setTextViewText(R.id.btn_refresh, if (loading) "⋯" else "↻")
                if (isDay) {
                    rv.setImageViewBitmap(R.id.strip,
                        if (sel != null) drawStrip(selDay?.optJSONArray("b"), light, showNow = false) else strip)
                    val base = 100 + (id % 1000) * 8
                    rv.setOnClickPendingIntent(R.id.btn_sleep, quick(context, "sleep", base + 1, sel))
                    rv.setOnClickPendingIntent(R.id.btn_block, quick(context, "block", base + 2, sel))
                    rv.setOnClickPendingIntent(R.id.btn_plan, quick(context, "plan", base + 3, sel))
                    rv.setOnClickPendingIntent(R.id.btn_todo, quick(context, "todo", base + 4, sel))
                    rv.setOnClickPendingIntent(R.id.strip, quick(context, "block", base + 5, sel))
                }
                rv.setOnClickPendingIntent(
                    R.id.title,
                    PendingIntent.getBroadcast(
                        context, 1000 + id, Intent(context, TrackerWidget::class.java).setAction(ACTION_NEXT_VIEW)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )

                if (isDay) {
                    rv.setViewVisibility(R.id.big, android.view.View.GONE)
                } else {
                    renderCal(context, mgr, id, rv, view, today, cache, light)
                }

                val refreshIntent = Intent(context, TrackerWidget::class.java).setAction(ACTION_REFRESH)
                rv.setOnClickPendingIntent(
                    R.id.btn_refresh,
                    PendingIntent.getBroadcast(
                        context, 0, refreshIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )

                val svc = Intent(context, WidgetListService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
                // 달력 보기에서는 목록을 연결하지 않음 (연결하면 목록이 다시 보이게 됨)
                if (isDay) {
                    rv.setRemoteAdapter(R.id.list, svc)
                    rv.setEmptyView(R.id.list, R.id.empty)
                    val template = PendingIntent.getActivity(
                        context, 10, Intent(context, ActionActivity::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    rv.setPendingIntentTemplate(R.id.list, template)
                }

                mgr.updateAppWidget(id, rv)
            }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        /** 위클리·먼슬리: 머리말 그림 + 요일 + 칸 그림 + 날짜마다 누르는 칸 */
        private fun renderCal(
            context: Context, mgr: AppWidgetManager, id: Int, rv: RemoteViews, view: String,
            today: String, cache: org.json.JSONObject?, light: Boolean
        ) {
            val prefs = Data.prefs(context)
            val opts = mgr.getAppWidgetOptions(id)
            val dens = context.resources.displayMetrics.density
            val wDp = maxOf(200, opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)) - 24
            val scale = minOf(dens, 1000f / wDp)
            val bw = (wDp * scale).toInt()
            val nowDs = Data.fmt(java.util.Calendar.getInstance())
            val days = cache?.optJSONObject("days")
            val isMonth = view == "month"
            val off = prefs.getInt(if (isMonth) "moff_$id" else "woff_$id", 0)

            // 큰 시계 (이번 달 먼슬리에서만)
            val showClock = isMonth && prefs.getBoolean("clock", true)
            rv.setViewVisibility(R.id.clock_box, if (showClock) android.view.View.VISIBLE else android.view.View.GONE)
            val clockSp = Data.clockSize(context)
            if (showClock) {
                val cc = Data.clockColor(context)
                for (cid in intArrayOf(R.id.clock_h, R.id.clock_m, R.id.clock_date)) rv.setTextColor(cid, cc)
                rv.setTextViewTextSize(R.id.clock_h, android.util.TypedValue.COMPLEX_UNIT_SP, clockSp.toFloat())
                rv.setTextViewTextSize(R.id.clock_m, android.util.TypedValue.COMPLEX_UNIT_SP, clockSp.toFloat())
                rv.setTextViewTextSize(R.id.clock_date, android.util.TypedValue.COMPLEX_UNIT_SP, 15f * Art.fs)
                val week = arrayOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")
                val now = java.util.Calendar.getInstance()
                rv.setTextViewText(R.id.clock_date, "${now.get(java.util.Calendar.MONTH) + 1}월 ${now.get(java.util.Calendar.DAY_OF_MONTH)}일 " +
                    week[now.get(java.util.Calendar.DAY_OF_WEEK) - 1] + "  ·  " + Cal.monthGanji(nowDs) + "月 " + Cal.ganji(nowDs) + "日")
            }

            // 할 일 / 오늘 일정 (이번 달 먼슬리에서만)
            var headDp = 0f
            val maxH = maxOf(160, opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 340))
            if (isMonth && maxH > 330) {
                val hb = Art.header(today, days, Cal.events(context, today, today), cache, light, bw)
                rv.setImageViewBitmap(R.id.head_img, hb)
                rv.setViewVisibility(R.id.head_box, android.view.View.VISIBLE)
                headDp = hb.height / scale
                // 머리말 줄마다 누르기: 왼쪽 = 할 일 체크, 오른쪽 = 일정 열기
                val pkg = context.packageName
                val res = context.resources
                for (r in 0..6) rv.setViewVisibility(res.getIdentifier("hrow$r", "id", pkg),
                    if (r <= Art.lastRows) android.view.View.VISIBLE else android.view.View.GONE)
                for (r in 1..6) {
                    val la = res.getIdentifier("hla$r", "id", pkg)
                    val lb = res.getIdentifier("hlb$r", "id", pkg)
                    val lc = res.getIdentifier("hlc$r", "id", pkg)
                    val rr = res.getIdentifier("hr$r", "id", pkg)
                    val td = Art.lastTodos.getOrNull(r - 1)
                    if (td != null) {
                        // 체크박스 자리 = 체크, 글자 = 그 날 데일리로
                        val i = Intent(context, ActionActivity::class.java)
                        val wt = td.optBoolean("_wt")
                        if (wt) i.putExtra("type", "wt").putExtra("text", td.optString("text"))
                            .putExtra("day", today).putExtra("done", !td.optBoolean("done"))
                        else i.putExtra("type", "todo").putExtra("text", td.optString("text")).putExtra("day", today)
                        val toggle = PendingIntent.getActivity(context, 7000 + (id % 1000) * 16 + r, i,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        val daily = PendingIntent.getBroadcast(context, 7200 + (id % 1000) * 16 + r,
                            Intent(context, TrackerWidget::class.java).setAction(ACTION_PICK)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("day", today),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        val box = Art.lastBoxX[r - 1]
                        if (wt && box != null && android.os.Build.VERSION.SDK_INT >= 31) {
                            // 스프레드 것: [글자 → 데일리][체크박스 → 체크][나머지 → 데일리]
                            val toDp = 1f / scale
                            rv.setViewLayoutWidth(la, box.first * toDp, android.util.TypedValue.COMPLEX_UNIT_DIP)
                            rv.setViewLayoutWidth(lb, (box.second - box.first) * toDp, android.util.TypedValue.COMPLEX_UNIT_DIP)
                            rv.setOnClickPendingIntent(la, daily)
                            rv.setOnClickPendingIntent(lb, toggle)
                            rv.setOnClickPendingIntent(lc, daily)
                        } else {
                            if (android.os.Build.VERSION.SDK_INT >= 31) {
                                rv.setViewLayoutWidth(la, 36f, android.util.TypedValue.COMPLEX_UNIT_DIP)
                                rv.setViewLayoutWidth(lb, 0f, android.util.TypedValue.COMPLEX_UNIT_DIP)
                            }
                            rv.setOnClickPendingIntent(la, if (wt) daily else toggle)
                            rv.setOnClickPendingIntent(lb, if (wt) toggle else daily)
                            rv.setOnClickPendingIntent(lc, if (wt) toggle else daily)
                        }
                    }
                    val it = Art.lastItems.getOrNull(r - 1)
                    if (it != null) {
                        val i = Intent(context, ActionActivity::class.java)
                        if (it.eventId >= 0) i.putExtra("type", "gcal").putExtra("id", it.eventId)
                            .putExtra("begin", it.begin).putExtra("end", it.end2)
                        else i.putExtra("type", "plan").putExtra("hour", it.hour).putExtra("day", today)
                        rv.setOnClickPendingIntent(rr, PendingIntent.getActivity(context, 7100 + (id % 1000) * 16 + r, i,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
            } else rv.setViewVisibility(R.id.head_box, android.view.View.GONE)

            rv.setViewVisibility(R.id.wk_row, if (isMonth) android.view.View.VISIBLE else android.view.View.GONE)
            for (i in 0..6) {
                val wid = context.resources.getIdentifier("wk$i", "id", context.packageName)
                rv.setTextColor(wid, when (i) { 0 -> Color.parseColor("#E5484D"); 6 -> Color.parseColor("#3B82F6")
                    else -> Data.textColor(context) ?: if (light) Color.parseColor("#E5E7EB") else Color.parseColor("#9CA3AF") })
                rv.setTextViewTextSize(wid, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * Art.fs)
            }

            val clockDp = if (showClock) clockSp * 2 * 1.05f + 22f * Art.fs else 0f
            val gridDp = maxOf(120f, maxH - 56f - clockDp - headDp - (if (isMonth) 22f else 0f))
            val bh = (gridDp * scale).toInt()

            val cellDays: List<String>
            val bmp: Bitmap
            if (isMonth) {
                val m = Data.toCal(today).apply { set(java.util.Calendar.DAY_OF_MONTH, 1); add(java.util.Calendar.MONTH, off) }
                val (gs, ge) = Data.monthGridOffset(today, off)
                val n = ((Data.toCal(ge).timeInMillis - Data.toCal(gs).timeInMillis) / 86400000L).toInt() + 1
                cellDays = (0 until n).map { Data.shift(gs, it) }
                bmp = Art.month(m.get(java.util.Calendar.YEAR), m.get(java.util.Calendar.MONTH), nowDs,
                    days, Cal.events(context, gs, ge), light, bw, bh, cache, gridOnly = true)
            } else {
                val ws = Data.shift(Data.weekStart(today), 7 * off)
                cellDays = (0..6).map { Data.shift(ws, it) }
                bmp = Art.week(ws, nowDs, days, Cal.events(context, ws, Data.shift(ws, 6)), light, bw, bh,
                    cache?.optJSONObject("dayColors"))
            }
            rv.setImageViewBitmap(R.id.big, bmp)

            // 날짜 칸 누르기 → 그 날 일정 입력창
            val rows = (cellDays.size + 6) / 7
            for (r in 0..5) rv.setViewVisibility(context.resources.getIdentifier("r$r", "id", context.packageName),
                if (r < rows) android.view.View.VISIBLE else android.view.View.GONE)
            for (i in 0 until 42) {
                val cid = context.resources.getIdentifier("c$i", "id", context.packageName)
                if (i >= cellDays.size) continue
                // 그 날 데일리 위젯으로 바꾸기
                val it = Intent(context, TrackerWidget::class.java).setAction(ACTION_PICK)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("day", cellDays[i])
                rv.setOnClickPendingIntent(cid, PendingIntent.getBroadcast(context, 3000 + (id % 1000) * 64 + i, it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }

            // ‹ › 넘기기, ＋ 일정 추가
            for ((vid, delta) in listOf(R.id.btn_prev to -1, R.id.btn_next to 1)) {
                rv.setTextColor(vid, if (light) Color.WHITE else Color.parseColor("#6B7280"))
                rv.setOnClickPendingIntent(vid, PendingIntent.getBroadcast(context, 5000 + (id % 1000) * 4 + (delta + 1),
                    Intent(context, TrackerWidget::class.java).setAction(ACTION_SHIFT)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("delta", delta),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            rv.setTextColor(R.id.btn_add, if (light) Color.WHITE else Color.parseColor("#6B7280"))
            // 슾 아이콘: 달력에 스프레드 할 일 보이기 켜기/끄기
            val wtOn = prefs.getBoolean("cal_wt", true)
            rv.setInt(R.id.btn_wt, "setBackgroundResource", if (wtOn) R.drawable.sheet_on else R.drawable.sheet_off)
            rv.setTextColor(R.id.btn_wt, if (wtOn) Color.WHITE else Color.parseColor("#9CA3AF"))
            rv.setOnClickPendingIntent(R.id.btn_wt, PendingIntent.getBroadcast(context, 6000 + (id % 1000),
                Intent(context, TrackerWidget::class.java).setAction(ACTION_TOGGLE_WT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            rv.setOnClickPendingIntent(R.id.btn_add, quick(context, "plan", 6))
        }

        private fun quick(context: Context, mode: String, req: Int, day: String? = null): PendingIntent {
            val i = Intent(context, QuickActivity::class.java)
                .putExtra("mode", mode)
                .putExtra("day", day ?: "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return PendingIntent.getActivity(
                context, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun launch(context: Context): PendingIntent {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, 20, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /** 하루(5시~다음날 5시) 칠한 칸 띠 */
        fun drawStrip(blocks: JSONArray?, dark: Boolean = false, showNow: Boolean = true): Bitmap {
            val w = 960f
            val h = 96f
            val barH = 60f
            val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val scale = w / Data.DAY_MIN

            p.color = if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#EEF1F4")
            c.drawRoundRect(RectF(0f, 0f, w, barH), 12f, 12f, p)

            if (blocks != null) {
                for (i in 0 until blocks.length()) {
                    val b = blocks.optJSONObject(i) ?: continue
                    val s = b.optInt("startMin", -1)
                    val e = b.optInt("endMin", -1)
                    if (s < 0 || e <= s) continue
                    p.color = Data.color(b.optString("category"))
                    c.drawRect(s * scale, 0f, e * scale, barH, p)
                }
            }

            // 시간 눈금 + 글자 (5, 11, 17, 23시)
            p.color = Color.parseColor("#33000000")
            p.strokeWidth = 2f
            for (hr in 1 until 24) {
                val x = hr * 60 * scale
                c.drawLine(x, if (hr % 6 == 0) 0f else barH - 14f, x, barH, p)
            }
            p.color = if (dark) Color.parseColor("#D1D5DB") else Color.parseColor("#9CA3AF")
            p.textSize = 26f
            for (hr in 0 until 24 step 6) {
                val x = hr * 60 * scale
                p.textAlign = if (hr == 0) Paint.Align.LEFT else Paint.Align.CENTER
                c.drawText("${(hr + Data.DAY_START) % 24}", x + if (hr == 0) 4f else 0f, h - 6f, p)
            }

            // 지금 시각
            if (!showNow) return bmp
            val now = Calendar.getInstance()
            val nowMin = Data.toGrid(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            p.color = Color.parseColor("#E85A5A")
            p.strokeWidth = 4f
            c.drawLine(nowMin * scale, 0f, nowMin * scale, barH, p)
            return bmp
        }
    }
}
