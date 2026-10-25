package com.spoonwork.timetracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** 위클리 / 먼슬리 달력 그림 */
object Art {

    /** 달력에 올라가는 일정 하나 (구글 캘린더 일정 또는 트래커 일정) */
    data class Item(
        val title: String,
        val color: Int,
        val start: String,      // 첫 날짜
        val end: String,        // 마지막 날짜 (포함)
        val order: Long,        // 같은 날 안에서 정렬
        val time: String        // "14:30" / "10시" / ""
    )

    val PLAN_COLOR: Int = Color.parseColor("#5BB5A2")
    private val RED = Color.parseColor("#E5484D")
    private val BLUE = Color.parseColor("#3B82F6")
    private val WORK = Color.parseColor("#E85A5A")
    private val ACCENT = Color.parseColor("#10B981")

    fun items(days: JSONObject?, events: List<Cal.Ev>, fromDs: String, toDs: String): List<Item> {
        val out = mutableListOf<Item>()
        for (e in events) {
            val ds = e.days()
            if (ds.isEmpty()) continue
            val c = Calendar.getInstance().apply { timeInMillis = e.begin }
            val time = if (e.allDay) "" else String.format(
                Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
            )
            val color = if (e.color == 0) Color.parseColor("#7986CB") else (e.color or 0xFF000000.toInt())
            out.add(Item(e.title, color, ds.first(), ds.last(), if (e.allDay) 0L else e.begin, time))
        }
        if (days != null) {
            var d = fromDs
            var guard = 0
            while (d <= toDs && guard++ < 70) {
                val p = days.optJSONObject(d)?.optJSONObject("p")
                if (p != null) {
                    val ks = p.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        val h = k.toIntOrNull() ?: continue
                        val text = p.optString(k)
                        if (text.isBlank()) continue
                        val base = Data.toCal(d).timeInMillis
                        out.add(Item(text, PLAN_COLOR, d, d, base + h * 3600_000L,
                            String.format(Locale.US, "%02d시", h % 24)))
                    }
                }
                d = Data.shift(d, 1)
            }
        }
        return out.sortedWith(compareBy<Item>({ it.start }, { if (it.end > it.start) 0 else 1 }, { it.order }))
    }

    private fun lum(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun fit(p: Paint, s: String, w: Float): String {
        if (w <= 0) return ""
        if (p.measureText(s) <= w) return s
        val n = p.breakText(s, true, w - p.measureText("…"), null)
        return if (n <= 0) "" else s.substring(0, n) + "…"
    }

    private fun pill(c: Canvas, p: Paint, r: RectF, color: Int, text: String, ts: Float, radius: Float) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(r, radius, radius, p)
        p.color = if (lum(color) > 0.62) Color.parseColor("#1F2937") else Color.WHITE
        p.textSize = ts
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT
        val pad = ts * 0.35f
        val t = fit(p, text, r.width() - pad * 2)
        c.drawText(t, r.left + pad, r.centerY() + ts * 0.36f, p)
    }

    private fun workMax(days: JSONObject?, ds: List<String>): Int {
        var m = 3600
        if (days != null) for (d in ds) m = maxOf(m, days.optJSONObject(d)?.optInt("w") ?: 0)
        return m
    }

    // ================= 먼슬리 =================
    fun month(
        year: Int, month0: Int, todayDs: String, days: JSONObject?, events: List<Cal.Ev>,
        dark: Boolean, W: Int, H: Int, cache: JSONObject? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = W / 340f
        val main = if (dark) Color.WHITE else Color.parseColor("#1F2937")
        val sub = if (dark) Color.parseColor("#D1D5DB") else Color.parseColor("#9CA3AF")
        val faint = if (dark) Color.parseColor("#6B7280") else Color.parseColor("#D1D5DB")

        // 세로로 길면 위에 오늘 날짜 + 일정 + 할 일
        var top = 0f
        if (H > W * 1.15f) {
            top = drawHeader(c, p, u, W, minOf(H * 0.30f, 170 * u), todayDs, days, events, cache, main, sub)
        }

        val first = Calendar.getInstance().apply { clear(); set(year, month0, 1) }
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1          // 일요일 시작
        val nDays = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (offset + nDays + 6) / 7
        val gridStart = Data.shift(Data.fmt(first), -offset)
        val cells = (0 until rows * 7).map { Data.shift(gridStart, it) }
        val inMonth = { ds: String -> Data.toCal(ds).get(Calendar.MONTH) == month0 }

        // 요일
        c.translate(0f, top)
        val Hc = H - top
        val hh = 18 * u
        p.textSize = 9.5f * u
        p.textAlign = Paint.Align.CENTER
        val cw = W / 7f
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        for (i in 0..6) {
            p.color = when (i) { 0 -> RED; 6 -> BLUE; else -> sub }
            c.drawText(names[i], cw * i + cw / 2, hh * 0.7f, p)
        }

        val ch = (Hc - hh) / rows
        val maxW = workMax(days, cells.filter { inMonth(it) })
        val items = items(days, events, cells.first(), cells.last())
        val laneTop = 24 * u
        val laneH = 11.5f * u
        val maxLanes = maxOf(0, ((ch - laneTop - 2 * u) / laneH).toInt())

        for (r in 0 until rows) {
            val y = hh + r * ch
            val week = cells.subList(r * 7, r * 7 + 7)

            // 날짜 칸
            for (i in 0..6) {
                val ds = week[i]
                val x = cw * i
                val cur = inMonth(ds)
                val holiday = Cal.HOLIDAYS.containsKey(ds)
                val dayNum = Data.toCal(ds).get(Calendar.DAY_OF_MONTH).toString()
                var numColor = when {
                    i == 0 || holiday -> RED
                    i == 6 -> BLUE
                    else -> main
                }
                if (!cur) numColor = faint
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = 11.5f * u
                p.textAlign = Paint.Align.CENTER
                val cx = x + cw / 2
                if (ds == todayDs) {
                    p.color = ACCENT
                    c.drawCircle(cx - 5 * u, y + 8.5f * u, 8 * u, p)
                    numColor = Color.WHITE
                }
                p.color = numColor
                c.drawText(dayNum, cx - 5 * u, y + 12.5f * u, p)
                // 일진 (숫자 오른쪽 작게)
                p.typeface = Typeface.DEFAULT
                p.textSize = 6.5f * u
                p.textAlign = Paint.Align.LEFT
                p.color = if (cur) sub else faint
                c.drawText(Cal.ganji(ds), cx + 4.5f * u, y + 12 * u, p)
                // 작업 막대 + 수면 막대
                val day = days?.optJSONObject(ds)
                val w = day?.optInt("w") ?: 0
                if (w > 0 && cur) {
                    p.color = WORK
                    val len = (cw - 6 * u) * minOf(1f, w.toFloat() / maxW)
                    c.drawRoundRect(RectF(x + 3 * u, y + 17 * u, x + 3 * u + maxOf(len, 2 * u), y + 19.5f * u), u, u, p)
                }
                val s = day?.optInt("s") ?: 0
                if (s > 0 && cur) {
                    p.color = Data.color("수면")
                    val len = (cw - 6 * u) * minOf(1f, s / 600f)
                    c.drawRoundRect(RectF(x + 3 * u, y + 20.5f * u, x + 3 * u + len, y + 22 * u), u, u, p)
                }
            }

            // 일정 (가로로 이어지는 막대)
            val lanes = Array(maxOf(maxLanes, 1)) { BooleanArray(7) }
            val hidden = IntArray(7)
            val wStart = week.first()
            val wEnd = week.last()
            for (it in items) {
                if (it.end < wStart || it.start > wEnd) continue
                val a = maxOf(0, week.indexOfFirst { d -> d >= it.start })
                var b = week.indexOfLast { d -> d <= it.end }
                if (b < a) b = a
                var lane = -1
                for (l in 0 until maxLanes) {
                    if ((a..b).all { col -> !lanes[l][col] }) { lane = l; break }
                }
                if (lane < 0) {
                    for (col in a..b) hidden[col]++
                    continue
                }
                for (col in a..b) lanes[lane][col] = true
                val top = y + laneTop + lane * laneH
                val rect = RectF(cw * a + 1.5f * u, top, cw * (b + 1) - 1.5f * u, top + laneH - 1.5f * u)
                var color = it.color
                if (!inMonth(week[a]) && !inMonth(week[b])) color = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
                pill(c, p, rect, color, it.title, 7.5f * u, 2.5f * u)
            }
            for (col in 0..6) {
                if (hidden[col] > 0) {
                    p.color = sub
                    p.textSize = 7 * u
                    p.textAlign = Paint.Align.RIGHT
                    c.drawText("+${hidden[col]}", cw * (col + 1) - 3 * u, y + ch - 1.5f * u, p)
                }
            }
            // 줄 구분
            p.color = if (dark) Color.parseColor("#22FFFFFF") else Color.parseColor("#11000000")
            p.strokeWidth = maxOf(1f, 0.6f * u)
            c.drawLine(0f, y, W.toFloat(), y, p)
        }
        return bmp
    }

    /** 먼슬리 위쪽: 큰 날짜 + 오늘 일정 + 할 일. 그린 높이를 돌려줌 */
    private fun drawHeader(
        c: Canvas, p: Paint, u: Float, W: Int, h: Float, todayDs: String, days: JSONObject?,
        events: List<Cal.Ev>, cache: JSONObject?, main: Int, sub: Int
    ): Float {
        val cal = Data.toCal(todayDs)
        val week = arrayOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        p.textSize = 34 * u
        p.color = main
        c.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), W / 2f, 34 * u, p)
        p.textSize = 9 * u
        p.color = sub
        c.drawText("${cal.get(Calendar.MONTH) + 1}월 ${cal.get(Calendar.DAY_OF_MONTH)}일 " +
            week[cal.get(Calendar.DAY_OF_WEEK) - 1] + "  ·  " + Cal.ganji(todayDs) + "日", W / 2f, 47 * u, p)

        val colTop = 60 * u
        val lineH = 12 * u
        val maxLines = maxOf(1, ((h - colTop - 4 * u) / lineH).toInt())
        val half = W / 2f
        p.textAlign = Paint.Align.LEFT

        // 왼쪽: 오늘 일정
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 9.5f * u
        p.color = main
        c.drawText("일정", 8 * u, colTop, p)
        val todayItems = items(days, events, todayDs, todayDs).filter { it.start <= todayDs && it.end >= todayDs }
        p.typeface = Typeface.DEFAULT
        p.textSize = 8.5f * u
        var y = colTop + lineH
        todayItems.take(maxLines).forEach { it ->
            p.color = it.color
            c.drawRoundRect(RectF(8 * u, y - 7.5f * u, 10.5f * u, y + 1.5f * u), u, u, p)
            p.color = main
            val label = (if (it.time.isNotEmpty()) it.time + "  " else "") + it.title
            c.drawText(fit(p, label, half - 22 * u), 14 * u, y, p)
            y += lineH
        }
        if (todayItems.isEmpty()) { p.color = sub; c.drawText("없음", 8 * u, y, p) }

        // 오른쪽: 할 일
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 9.5f * u
        p.color = main
        val todos = cache?.optJSONArray("todos")
        val list = (0 until (todos?.length() ?: 0)).mapNotNull { todos?.optJSONObject(it) }
            .filter { it.optString("text").isNotBlank() }
        val done = list.count { it.optBoolean("done") }
        c.drawText(if (list.isNotEmpty() && done == list.size) "할 일 ✓ 달성!" else "할 일  $done/${list.size}", half + 6 * u, colTop, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = 8.5f * u
        y = colTop + lineH
        list.take(maxLines).forEach { t ->
            val d = t.optBoolean("done")
            p.color = if (d) sub else main
            c.drawText(fit(p, (if (d) "☑ " else "☐ ") + t.optString("text"), half - 14 * u), half + 6 * u, y, p)
            y += lineH
        }
        return h
    }

    // ================= 위클리 =================
    fun week(
        startDs: String, todayDs: String, days: JSONObject?, events: List<Cal.Ev>,
        dark: Boolean, W: Int, H: Int
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = W / 340f
        val main = if (dark) Color.WHITE else Color.parseColor("#1F2937")
        val sub = Color.parseColor("#9CA3AF")
        val ds7 = (0..6).map { Data.shift(startDs, it) }
        val cw = W / 7f
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        val maxW = workMax(days, ds7)
        val all = items(days, events, ds7.first(), ds7.last())

        for (i in 0..6) {
            val ds = ds7[i]
            val x = cw * i
            val holiday = Cal.HOLIDAYS.containsKey(ds)
            val col = when { i == 0 || holiday -> RED; i == 6 -> BLUE; else -> main }
            // 오늘 칸 배경
            if (ds == todayDs) {
                p.color = if (dark) Color.parseColor("#1AFFFFFF") else Color.parseColor("#0F10B981")
                c.drawRoundRect(RectF(x + u, 0f, x + cw - u, H.toFloat()), 6 * u, 6 * u, p)
            }
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT
            p.textSize = 8.5f * u
            p.color = if (i == 0 || i == 6 || holiday) col else sub
            c.drawText(names[i], x + cw / 2, 11 * u, p)
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 14 * u
            p.color = if (ds == todayDs) ACCENT else col
            c.drawText(Data.toCal(ds).get(Calendar.DAY_OF_MONTH).toString(), x + cw / 2, 28 * u, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 7 * u
            p.color = sub
            c.drawText(Cal.ganji(ds), x + cw / 2, 38 * u, p)

            val day = days?.optJSONObject(ds)
            val w = day?.optInt("w") ?: 0
            if (w > 0) {
                p.color = WORK
                val len = (cw - 8 * u) * minOf(1f, w.toFloat() / maxW)
                c.drawRoundRect(RectF(x + 4 * u, 42 * u, x + 4 * u + maxOf(len, 2 * u), 44.5f * u), u, u, p)
                p.textSize = 6.5f * u
                p.color = sub
                c.drawText(Data.hm(w / 60), x + cw / 2, 52 * u, p)
            }

            // 그 날 일정
            var y = 56 * u
            val list = all.filter { it.start <= ds && it.end >= ds }
            val ih = 20 * u
            var shown = 0
            for (it in list) {
                if (y + ih > H - 9 * u && shown < list.size) break
                val r = RectF(x + 1.5f * u, y, x + cw - 1.5f * u, y + ih - 2 * u)
                p.color = it.color
                c.drawRoundRect(r, 3 * u, 3 * u, p)
                p.color = if (lum(it.color) > 0.62) Color.parseColor("#1F2937") else Color.WHITE
                p.textAlign = Paint.Align.LEFT
                p.textSize = 6.3f * u
                if (it.time.isNotEmpty()) c.drawText(it.time, r.left + 2.5f * u, r.top + 7 * u, p)
                p.textSize = 7.3f * u
                c.drawText(fit(p, it.title, r.width() - 4 * u), r.left + 2.5f * u,
                    if (it.time.isNotEmpty()) r.top + 15 * u else r.centerY() + 2.6f * u, p)
                y += ih
                shown++
            }
            if (shown < list.size) {
                p.textAlign = Paint.Align.CENTER
                p.textSize = 7 * u
                p.color = sub
                c.drawText("+${list.size - shown}", x + cw / 2, H - 2 * u, p)
            }
        }
        return bmp
    }
}
