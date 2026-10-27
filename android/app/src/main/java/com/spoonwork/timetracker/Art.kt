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
        val time: String,       // "14:30" / "10시" / ""
        val eventId: Long = -1, // 구글 캘린더 일정이면 id
        val hour: Int = -1,     // 트래커 일정이면 시각
        val begin: Long = 0,
        val end2: Long = 0,
        val sheet: Boolean = false, // 작업 스프레드 칸
        val done: Boolean = false   // 체크됨 → 줄 긋기
    )

    val PLAN_COLOR: Int = Color.parseColor("#5BB5A2")
    /** 작업 스프레드 '슾' 줄 색 (분홍) */
    val SHEET_BAR: Int = Color.parseColor("#EA9999")
    val SHEET_CHECK: Int = Color.parseColor("#E06666")

    /** 마지막으로 그린 머리말의 줄들 (위젯에서 누를 때 씀) */
    var lastTodos: List<JSONObject> = emptyList()
    var lastItems: List<Item> = emptyList()
    var lastRows = 0
    /** 스프레드 할 일 체크박스 위치 (머리말 그림 px): 줄마다 [시작, 끝] */
    var lastBoxX: MutableMap<Int, Pair<Float, Float>> = mutableMapOf()

    /** 사용자 설정: 글자색(없으면 자동), 글자 크기 배율 */
    var textOverride: Int? = null
    var fs: Float = 1f
    var showSheet: Boolean = true
    var schedLink: JSONObject? = null   // 할 일 ↔ '📅 스케쥴' 연결      // 달력 날짜칸에 작업 스프레드 할 일 보이기
    private fun mainOf(dark: Boolean): Int = textOverride ?: if (dark) Color.WHITE else Color.parseColor("#1F2937")
    private val RED = Color.parseColor("#E5484D")
    private val BLUE = Color.parseColor("#3B82F6")
    private val WORK = Color.parseColor("#E85A5A")
    private val ACCENT = Color.parseColor("#10B981")
    /** 오늘 표시 색 (설정): 칸 색, 숫자 색 */
    var todayStyle: Pair<Int, Int> = Pair(Color.parseColor("#FFD84D"), Color.parseColor("#1F2937"))

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
            out.add(Item(e.title, color, ds.first(), ds.last(), if (e.allDay) 0L else e.begin, time,
                eventId = e.id, begin = e.begin, end2 = e.end))
        }
        if (days != null) {
            var d = fromDs
            var guard = 0
            while (d <= toDs && guard++ < 70) {
                // 작업 스프레드 '슾' 칸 (📅 스케쥴은 일정으로 이미 보이니 빼기)
                val wt = if (showSheet) days.optJSONObject(d)?.optJSONArray("wt") else null
                if (wt != null) for (j in 0 until wt.length()) {
                    val w = wt.optJSONObject(j) ?: continue
                    val text = w.optString("text")
                    if (text.isBlank() || text.startsWith("📅")) continue
                    val col = if (w.optBoolean("done")) Color.argb(120, 0xEA, 0x99, 0x99) else SHEET_BAR
                    out.add(Item(text, col, d, d,
                        Data.toCal(d).timeInMillis + 1L + j, "", sheet = true, done = w.optBoolean("done")))
                }
                // 📅 스케쥴로만 올린 내 할 일: 나한테는 내용 그대로 (스프레드 줄 색)
                val tl = days.optJSONObject(d)?.optJSONArray("t")
                val links = schedLink?.optJSONObject(d)
                if (tl != null && links != null && showSheet) for (j in 0 until tl.length()) {
                    val x = tl.optJSONObject(j) ?: continue
                    val text = x.optString("text")
                    if (!links.optBoolean(text)) continue
                    val dn = x.optBoolean("done")
                    out.add(Item(text, if (dn) Color.argb(120, 0xEA, 0x99, 0x99) else SHEET_BAR, d, d,
                        Data.toCal(d).timeInMillis + 500L + j, "", sheet = true, done = dn))
                }
                val p = days.optJSONObject(d)?.optJSONObject("p")
                if (p != null) {
                    val ks = p.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        val h = k.toIntOrNull() ?: continue
                        val text = p.optString(k)
                        if (text.isBlank()) continue
                        val base = Data.toCal(d).timeInMillis
                        out.add(Item(text, PLAN_COLOR, d, d, base + h * 3600_000L, hour = h, time =
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

    private fun pill(c: Canvas, p: Paint, r: RectF, color: Int, text: String, ts: Float, radius: Float, strike: Boolean = false) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(r, radius, radius, p)
        p.color = if (lum(color) > 0.62) Color.parseColor("#1F2937") else Color.WHITE
        p.textSize = ts
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT
        val pad = ts * 0.35f
        val t = fit(p, text, r.width() - pad * 2)
        p.isStrikeThruText = strike
        c.drawText(t, r.left + pad, r.centerY() + ts * 0.36f, p)
        p.isStrikeThruText = false
    }

    /** 시간 있는 일정: 삼성 캘린더처럼 왼쪽 색 바 + 글자 */
    private fun barItem(c: Canvas, p: Paint, r: RectF, color: Int, text: String, ts: Float, u: Float, textColor: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        val bw = 1.6f * u
        val bx = r.left + 2.5f * u          // 칸 안쪽으로 (옆 칸 일정과 안 붙게)
        c.drawRoundRect(RectF(bx, r.top + 0.8f * u, bx + bw, r.bottom - 0.8f * u), bw / 2, bw / 2, p)
        p.color = textColor
        if (Color.alpha(color) < 200) p.alpha = 140
        p.textSize = ts
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT
        val x = bx + bw + 1.6f * u
        c.drawText(fit(p, text, r.right - x), x, r.centerY() + ts * 0.36f, p)
        p.alpha = 255
    }

    /** 같은 색이 이어지는 날은 한 줄로 이어서 그림 */
    private fun colorRuns(c: Canvas, p: Paint, dayColors: JSONObject?, days: List<String>,
                          cw: Float, top: Float, bottom: Float, u: Float, dim: (String) -> Boolean) {
        if (dayColors == null) return
        var i = 0
        while (i < days.size) {
            val col = dayColors.optString(days[i], "")
            if (!col.startsWith("#")) { i++; continue }
            var j = i
            while (j + 1 < days.size && dayColors.optString(days[j + 1], "") == col) j++
            try {
                p.style = Paint.Style.FILL
                p.color = Color.parseColor(col)
                if (dim(days[i])) p.alpha = 90
                c.drawRoundRect(RectF(cw * i + 3 * u, top, cw * (j + 1) - 3 * u, bottom), u, u, p)
                p.alpha = 255
            } catch (_: Exception) {}
            i = j + 1
        }
    }

    private fun workMax(days: JSONObject?, ds: List<String>): Int {
        var m = 3600
        if (days != null) for (d in ds) m = maxOf(m, days.optJSONObject(d)?.optInt("w") ?: 0)
        return m
    }

    // ================= 먼슬리 =================
    fun month(
        year: Int, month0: Int, todayDs: String, days: JSONObject?, events: List<Cal.Ev>,
        dark: Boolean, W: Int, H: Int, cache: JSONObject? = null, gridOnly: Boolean = false
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = W / 340f
        val main = mainOf(dark)
        val sub = if (dark) Color.parseColor("#D1D5DB") else Color.parseColor("#9CA3AF")
        val faint = if (dark) Color.parseColor("#6B7280") else Color.parseColor("#D1D5DB")

        // 세로로 길면 위에 오늘 날짜 + 일정 + 할 일
        var top = 0f
        if (!gridOnly && H > W * 0.95f) {
            top = drawHeader(c, p, u, W, minOf(H * 0.30f, 170 * u), todayDs, todayDs, todayDs, days, events, cache, main, sub)
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
        val hh = if (gridOnly) 0f else 18 * u
        p.textSize = 9.5f * u * fs
        p.textAlign = Paint.Align.CENTER
        val cw = W / 7f
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        if (!gridOnly) for (i in 0..6) {
            p.color = when (i) { 0 -> RED; 6 -> BLUE; else -> sub }
            c.drawText(names[i], cw * i + cw / 2, hh * 0.7f, p)
        }

        val ch = (Hc - hh) / rows
        val maxW = workMax(days, cells.filter { inMonth(it) })
        val items = items(days, events, cells.first(), cells.last())
        val laneTop = 20.5f * u                     // 일정은 날짜 바로 아래부터
        val dayColors = cache?.optJSONObject("dayColors")
        val laneH = 13.5f * u * fs               // 일정 글자도 글자 크기 설정을 따름
        val maxLanes = maxOf(0, ((ch - laneTop - 2 * u) / laneH).toInt())

        for (r in 0 until rows) {
            val y = hh + r * ch
            val week = cells.subList(r * 7, r * 7 + 7)

            // 작업 캘린더 색 줄 (숫자 위, 같은 색은 이어서)
            colorRuns(c, p, dayColors, week, cw, y + 2 * u, y + 4.5f * u, u) { d -> !inMonth(d) }

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
                p.textSize = 11.5f * u * fs
                p.textAlign = Paint.Align.CENTER
                val cx = x + cw / 2
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = 11.5f * u * fs
                p.textAlign = Paint.Align.CENTER
                if (ds == todayDs) {
                    // 오늘: 노란 둥근 네모 (작업 막대·음력과 안 겹치게 위아래 짧게)
                    val tc = todayStyle
                    p.color = tc.first
                    val half = maxOf(p.measureText(dayNum) / 2 + 3 * u, 7 * u)
                    c.drawRoundRect(RectF(cx - 5 * u - half, y + 5.5f * u, cx - 5 * u + half, y + 18.8f * u), 3.5f * u, 3.5f * u, p)
                    numColor = tc.second
                }
                p.color = numColor
                c.drawText(dayNum, cx - 5 * u, y + 16.5f * u, p)
                // 일진 (숫자 오른쪽 작게)
                p.typeface = Typeface.DEFAULT
                p.textSize = 6.5f * u * fs
                p.textAlign = Paint.Align.LEFT
                p.color = if (cur) sub else faint
                // 음력: 삼성 캘린더처럼 일요일과 오늘만 (일진 아래에 작게)
                if (i == 0 || ds == todayDs) {
                    c.drawText(Cal.ganji(ds), cx + 4.5f * u, y + 11 * u, p)
                    p.textSize = 5.5f * u * fs
                    c.drawText(Cal.lunar(ds), cx + 4.5f * u, y + 17.5f * u, p)
                } else c.drawText(Cal.ganji(ds), cx + 4.5f * u, y + 16 * u, p)
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
                val ts = 9f * u * fs
                if (it.end < todayDs && it.sheet) {      // 지난 날 스프레드 할 일만: 색칠 없이 빨간 글자 + 줄
                    // 지난 날: 색칠 없이 빨간 글자 + 줄 긋기
                    p.color = RED
                    if (!inMonth(week[a]) && !inMonth(week[b])) p.alpha = 110
                    p.textSize = ts; p.textAlign = Paint.Align.LEFT; p.typeface = Typeface.DEFAULT
                    p.isStrikeThruText = true
                    val tx = rect.left + 1.5f * u
                    c.drawText(fit(p, it.title, rect.right - tx), tx, rect.centerY() + ts * 0.36f, p)
                    p.isStrikeThruText = false; p.alpha = 255
                } else if (it.time.isNotEmpty() && a == b && !it.sheet) barItem(c, p, rect, color, it.title, ts, u, main)
                else pill(c, p, rect, color, it.title, ts, 2.5f * u, it.done)
            }
            for (col in 0..6) {
                if (hidden[col] > 0) {
                    p.color = sub
                    p.textSize = 7 * u * fs
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

    /** 먼슬리 위쪽 (할 일 / 오늘 일정)만 따로 그린 그림 */
    /** 머리말 화살표 자리 (px): 할 일 ‹ 시작, 일정 ‹ 시작, 화살표 하나 너비 */
    var arrowTodoX = 0f
    var arrowEvX = 0f
    var arrowW = 0f

    private val WK = arrayOf("일", "월", "화", "수", "목", "금", "토")
    private fun shortDay(ds: String): String {
        val c = Data.toCal(ds)
        return "${c.get(java.util.Calendar.MONTH) + 1}/${c.get(java.util.Calendar.DAY_OF_MONTH)} ${WK[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]}"
    }

    fun header(todoDs: String, evDs: String, todayDs: String, days: JSONObject?, events: List<Cal.Ev>, cache: JSONObject?,
               dark: Boolean, W: Int): Bitmap {
        val u = W / 340f
        val maxH = (15 * u * fs * 7 + 2).toInt()
        val bmp = Bitmap.createBitmap(W, maxH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val main = mainOf(dark)
        val sub = if (dark) Color.parseColor("#D1D5DB") else Color.parseColor("#9CA3AF")
        val used = drawHeader(c, p, u, W, maxH.toFloat(), todoDs, evDs, todayDs, days, events, cache, main, sub)
        return Bitmap.createBitmap(bmp, 0, 0, W, minOf(maxH, maxOf(1, used.toInt())))
    }

    /** 먼슬리 위쪽: 큰 날짜 + 오늘 일정 + 할 일. 그린 높이를 돌려줌 */
    private fun drawHeader(
        c: Canvas, p: Paint, u: Float, W: Int, h: Float, todoDs: String, evDs: String, todayDs: String,
        days: JSONObject?, events: List<Cal.Ev>, cache: JSONObject?, main: Int, sub: Int
    ): Float {
        val pad = 8 * u
        val lineH = 15 * u * fs
        val colTop = lineH * 0.78f      // 줄 높이를 똑같이 (위젯에서 줄마다 누를 수 있게)
        // 내용 줄 수만큼만 높이를 씀 (빈 공간 없이)
        val todayItems = items(days, events, evDs, evDs).filter { it.start <= evDs && it.end >= evDs && !it.sheet }
        val todosArr = if (todoDs == todayDs) cache?.optJSONArray("todos")
            else days?.optJSONObject(todoDs)?.optJSONArray("t")
        val wtArr = days?.optJSONObject(todoDs)?.optJSONArray("wt")
        val list = ((0 until (wtArr?.length() ?: 0)).mapNotNull { wtArr?.optJSONObject(it) }
            .filter { !it.optString("text").startsWith("📅") }.map { JSONObject(it.toString()).put("_wt", true) } +
            (0 until (todosArr?.length() ?: 0)).mapNotNull { todosArr?.optJSONObject(it) })
            .filter { it.optString("text").isNotBlank() }
        val capLines = minOf(6, maxOf(1, (h / lineH).toInt() - 1))
        val maxLines = minOf(capLines, maxOf(1, todayItems.size, list.size))
        val usedH = lineH * (maxLines + 1)
        lastTodos = list.take(maxLines)
        lastItems = todayItems.take(maxLines)
        lastRows = maxLines
        val half = W / 2f
        // 날짜 넘기기 화살표 ‹ › (할 일 / 일정 따로)
        arrowW = 22 * u
        arrowTodoX = half - 2 * u - 2 * arrowW
        arrowEvX = W - pad - 2 * arrowW
        fun arrows(x0: Float) {
            p.typeface = Typeface.DEFAULT
            p.textSize = 13 * u * fs
            p.color = sub
            p.textAlign = Paint.Align.CENTER
            c.drawText("‹", x0 + arrowW * 0.5f, colTop, p)
            c.drawText("›", x0 + arrowW * 1.5f, colTop, p)
            p.textAlign = Paint.Align.LEFT
        }
        arrows(arrowTodoX)
        arrows(arrowEvX)

        // 왼쪽: 할 일
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 11 * u * fs
        p.color = main
        val done = list.count { it.optBoolean("done") }
        val todoTitle = if (todoDs == todayDs) "할 일" else "할 일 · " + shortDay(todoDs)
        c.drawText(todoTitle, pad, colTop, p)
        val tw = p.measureText(todoTitle)
        p.typeface = Typeface.DEFAULT
        p.textSize = 8.5f * u * fs
        p.color = if (list.isNotEmpty() && done == list.size) ACCENT else sub
        c.drawText(if (list.isNotEmpty() && done == list.size) "  ✓ 달성!" else "  $done/${list.size}",
            pad + tw, colTop, p)
        p.textSize = 10 * u * fs
        var y = colTop + lineH
        lastBoxX = mutableMapOf()
        list.take(maxLines).forEachIndexed { idx, t ->
            val d = t.optBoolean("done")
            val isWt = t.optBoolean("_wt")
            var x = pad
            if (isWt) {
                // 작업 스프레드 것은 왼쪽 분홍 막대 + 분홍 체크
                p.color = SHEET_BAR
                c.drawRoundRect(RectF(pad, y - 9 * u * fs, pad + 2.5f * u, y + 2 * u), u, u, p)
                x = pad + 6 * u
            }
            val box = if (d) "☑" else "☐"
            if (isWt) {
                // 스프레드 것: 글자 바로 뒤에 체크박스
                val bw = p.measureText(box)
                p.color = if (d) sub else main
                val shown = fit(p, t.optString("text"), half - x - bw - 12 * u)
                p.isStrikeThruText = d          // 체크하면 줄 긋기
                c.drawText(shown, x, y, p)
                p.isStrikeThruText = false
                val bx = x + p.measureText(shown) + 5 * u
                p.color = SHEET_CHECK
                c.drawText(box, bx, y, p)
                lastBoxX[idx] = Pair(bx - 3 * u, bx + bw + 3 * u)
            } else {
                p.color = if (d) sub else main
                c.drawText("$box ", x, y, p)
                val bw = p.measureText("$box ")
                c.drawText(fit(p, t.optString("text"), half - x - bw - 6 * u), x + bw, y, p)
            }
            y += lineH
        }
        if (list.isEmpty()) { p.color = sub; c.drawText("없음", pad, y, p) }

        // 오른쪽: 오늘 일정
        val rx = half + 4 * u
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 11 * u * fs
        p.color = main
        c.drawText(if (evDs == todayDs) "오늘 일정" else shortDay(evDs) + " 일정", rx, colTop, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = 10 * u * fs
        y = colTop + lineH
        todayItems.take(maxLines).forEach { it ->
            p.color = it.color
            c.drawRoundRect(RectF(rx, y - 9 * u, rx + 2.5f * u, y + 1.5f * u), u, u, p)
            p.color = main
            val label = (if (it.time.isNotEmpty()) it.time + "  " else "") + it.title
            c.drawText(fit(p, label, W - rx - pad - 6 * u), rx + 6 * u, y, p)
            y += lineH
        }
        if (todayItems.isEmpty()) { p.color = sub; c.drawText("없음", rx, y, p) }
        return usedH
    }

    // ================= 위클리 =================
    fun week(
        startDs: String, todayDs: String, days: JSONObject?, events: List<Cal.Ev>,
        dark: Boolean, W: Int, H: Int, dayColors: JSONObject? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = W / 340f
        val main = mainOf(dark)
        val sub = Color.parseColor("#9CA3AF")
        val ds7 = (0..6).map { Data.shift(startDs, it) }
        val cw = W / 7f
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        val maxW = workMax(days, ds7)
        val all = items(days, events, ds7.first(), ds7.last())

        colorRuns(c, p, dayColors, ds7, cw, 0.5f * u, 3 * u, u) { false }
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
            p.textSize = 8.5f * u * fs
            p.color = if (i == 0 || i == 6 || holiday) col else sub
            c.drawText(names[i], x + cw / 2, 11 * u, p)
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 14 * u * fs
            p.color = if (ds == todayDs) ACCENT else col
            c.drawText(Data.toCal(ds).get(Calendar.DAY_OF_MONTH).toString(), x + cw / 2, 28 * u, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 7 * u * fs
            p.color = sub
            c.drawText(Cal.ganji(ds), x + cw / 2, 38 * u, p)

            val day = days?.optJSONObject(ds)
            val w = day?.optInt("w") ?: 0
            if (w > 0) {
                p.color = WORK
                val len = (cw - 8 * u) * minOf(1f, w.toFloat() / maxW)
                c.drawRoundRect(RectF(x + 4 * u, 42 * u, x + 4 * u + maxOf(len, 2 * u), 44.5f * u), u, u, p)
                p.textSize = 6.5f * u * fs
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
                p.textSize = 6.3f * u * fs
                if (it.time.isNotEmpty()) c.drawText(it.time, r.left + 2.5f * u, r.top + 7 * u, p)
                p.textSize = 7.3f * u * fs
                c.drawText(fit(p, it.title, r.width() - 4 * u), r.left + 2.5f * u,
                    if (it.time.isNotEmpty()) r.top + 15 * u else r.centerY() + 2.6f * u, p)
                y += ih
                shown++
            }
            if (shown < list.size) {
                p.textAlign = Paint.Align.CENTER
                p.textSize = 7 * u * fs
                p.color = sub
                c.drawText("+${list.size - shown}", x + cw / 2, H - 2 * u, p)
            }
        }
        return bmp
    }
}
