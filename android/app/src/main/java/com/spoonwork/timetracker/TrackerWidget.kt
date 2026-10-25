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
                refresh(context)
                render(context)
            }
            ACTION_NEXT_VIEW -> {
                val next = when (Data.view(context)) { "day" -> "week"; "week" -> "month"; else -> "day" }
                Data.prefs(context).edit().putString("view", next).apply()
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
                val from = minOf(gs, Data.weekStart(today))
                val to = maxOf(ge, week7)
                val j = Data.fetchSummary(ctx, today, from, to)
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

            val view = Data.view(context)
            val stats = when {
                cache != null && view == "month" -> {
                    val days = cache.optJSONObject("days")
                    var sum = 0
                    val prefix = today.substring(0, 7)
                    days?.keys()?.forEach { k -> if (k.startsWith(prefix)) sum += days.optJSONObject(k)?.optInt("w") ?: 0 }
                    "이번 달 작업 ${Data.hm(sum / 60)}" + (if (error != null) "  ⚠" else "")
                }
                cache != null && view == "week" -> {
                    val days = cache.optJSONObject("days")
                    val ws = Data.weekStart(today)
                    var sum = 0
                    for (i in 0..6) sum += days?.optJSONObject(Data.shift(ws, i))?.optInt("w") ?: 0
                    "이번 주 작업 ${Data.hm(sum / 60)}" + (if (error != null) "  ⚠" else "")
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
            val strip = drawStrip(cache?.optJSONArray("blocks"), dark)
            val mainColor = if (dark) Color.WHITE else Color.parseColor("#1F2937")
            val subColor = if (dark) Color.parseColor("#D1D5DB") else Color.parseColor("#6B7280")
            val alpha = Data.bgAlpha(context) * 255 / 100

            for (id in ids) {
                val rv = RemoteViews(context.packageName, R.layout.widget)
                rv.setInt(R.id.bg, "setColorFilter", if (dark) Color.parseColor("#111827") else Color.WHITE)
                rv.setInt(R.id.bg, "setImageAlpha", alpha)
                rv.setTextColor(R.id.title, mainColor)
                rv.setTextColor(R.id.stats, subColor)
                rv.setTextColor(R.id.btn_refresh, subColor)
                rv.setTextColor(R.id.empty, subColor)
                for (chip in intArrayOf(R.id.btn_sleep, R.id.btn_block, R.id.btn_plan, R.id.btn_todo)) {
                    rv.setInt(chip, "setBackgroundResource", if (dark) R.drawable.chip_bg_dark else R.drawable.chip_bg)
                    rv.setTextColor(chip, if (dark) Color.WHITE else Color.parseColor("#374151"))
                }
                val tc = Data.toCal(today)
                val title = when (view) {
                    "month" -> "${tc.get(java.util.Calendar.MONTH) + 1}월"
                    "week" -> "${tc.get(java.util.Calendar.MONTH) + 1}월 주간"
                    else -> Data.prettyDay(today).replace("월 ", "/").replace("일 ", " ")
                }
                rv.setTextViewText(R.id.title, "$title ▾")
                rv.setTextViewText(R.id.stats,
                    (if (loading && cache == null) "불러오는 중…" else stats) + if (hasUpdate) "  ⬆새 버전" else "")
                rv.setTextViewText(R.id.btn_refresh, if (loading) "⋯" else "↻")
                rv.setImageViewBitmap(R.id.strip, strip)

                rv.setOnClickPendingIntent(R.id.btn_sleep, quick(context, "sleep", 1))
                rv.setOnClickPendingIntent(R.id.btn_block, quick(context, "block", 2))
                rv.setOnClickPendingIntent(R.id.btn_plan, quick(context, "plan", 3))
                rv.setOnClickPendingIntent(R.id.btn_todo, quick(context, "todo", 4))
                rv.setOnClickPendingIntent(R.id.strip, quick(context, "block", 5))
                rv.setOnClickPendingIntent(
                    R.id.title,
                    PendingIntent.getBroadcast(
                        context, 1, Intent(context, TrackerWidget::class.java).setAction(ACTION_NEXT_VIEW),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )

                if (view == "day") {
                    rv.setViewVisibility(R.id.strip, android.view.View.VISIBLE)
                    rv.setViewVisibility(R.id.list, android.view.View.VISIBLE)
                    rv.setViewVisibility(R.id.big, android.view.View.GONE)
                } else {
                    rv.setViewVisibility(R.id.strip, android.view.View.GONE)
                    rv.setViewVisibility(R.id.list, android.view.View.GONE)
                    rv.setViewVisibility(R.id.empty, android.view.View.GONE)
                    rv.setViewVisibility(R.id.big, android.view.View.VISIBLE)
                    val opts = mgr.getAppWidgetOptions(id)
                    val dens = context.resources.displayMetrics.density
                    val wDp = maxOf(200, opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)) - 28
                    val hDp = maxOf(160, opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 340)) - 110
                    val scale = minOf(dens, 1000f / wDp)
                    val bw = (wDp * scale).toInt()
                    val bh = (maxOf(120, hDp) * scale).toInt()
                    val days = cache?.optJSONObject("days")
                    val bmp = if (view == "month") {
                        val (gs, ge) = Data.monthGrid(today)
                        Art.month(tc.get(java.util.Calendar.YEAR), tc.get(java.util.Calendar.MONTH), Data.fmt(java.util.Calendar.getInstance()),
                            days, Cal.events(context, gs, ge), dark, bw, bh)
                    } else {
                        val ws = Data.weekStart(today)
                        Art.week(ws, Data.fmt(java.util.Calendar.getInstance()), days,
                            Cal.events(context, ws, Data.shift(ws, 6)), dark, bw, bh)
                    }
                    rv.setImageViewBitmap(R.id.big, bmp)
                    rv.setOnClickPendingIntent(R.id.big, PendingIntent.getActivity(
                        context, 30, Intent(context, ActionActivity::class.java).putExtra("type", "calendar")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ))
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
                rv.setRemoteAdapter(R.id.list, svc)
                if (view == "day") rv.setEmptyView(R.id.list, R.id.empty)

                val template = PendingIntent.getActivity(
                    context, 10, Intent(context, ActionActivity::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                rv.setPendingIntentTemplate(R.id.list, template)

                mgr.updateAppWidget(id, rv)
            }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        private fun quick(context: Context, mode: String, req: Int): PendingIntent {
            val i = Intent(context, QuickActivity::class.java)
                .putExtra("mode", mode)
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
        fun drawStrip(blocks: JSONArray?, dark: Boolean = false): Bitmap {
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
            val now = Calendar.getInstance()
            val nowMin = Data.toGrid(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            p.color = Color.parseColor("#E85A5A")
            p.strokeWidth = 4f
            c.drawLine(nowMin * scale, 0f, nowMin * scale, barH, p)
            return bmp
        }
    }
}
