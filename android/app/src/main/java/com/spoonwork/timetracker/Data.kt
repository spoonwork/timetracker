package com.spoonwork.timetracker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

/** 웹앱(Apps Script)과 같은 데이터를 읽고 쓰는 곳 */
object Data {
    const val DEFAULT_URL =
        "https://script.google.com/macros/s/AKfycbzKAlhhA02RpnXPVrTLIDrfpQooM2OGlV4jQaNgzrG4tZav9ir84K8nxsr6saQVPT_d/exec"
    const val DAY_START = 5          // 기록표 하루 시작 (새벽 5시)
    const val DAY_MIN = 24 * 60

    /** 빠른 입력에서 고를 수 있는 종류 (웹앱 팔레트와 같은 순서) */
    val CATS = listOf("수면", "작업", "식사", "운동", "이동", "외출", "병원", "힐링", "휴식", "관리", "세무")

    val COLORS = mapOf(
        "카톡" to "#FAE100", "유튜브" to "#962525", "SU" to "#1C71A9", "distraction" to "#962525",
        "distraction_comm" to "#AEC0CA", "PR" to "#888888", "XLS" to "#42A248", "HWP" to "#3A8F40",
        "Word" to "#2E7D32", "식사" to "#FDF6C8", "수면" to "#C1C1C1", "운동" to "#FDF89A",
        "이동" to "#8ea7c2", "외출" to "#DCC8E0", "병원" to "#F0C8C8", "출근" to "#DDE5C8",
        "퇴근" to "#DDE5C8", "작업" to "#E85A5A", "힐링" to "#F5C6D9", "휴식" to "#C9E2C9",
        "관리" to "#D9F6F2", "그림" to "#E85A5A", "3D" to "#E87D0D", "3D배경" to "#005F9E",
        "AI" to "#5E5E5E", "코딩" to "#D27F6E", "문서" to "#CE776D", "세무" to "#D5B3BA",
        "텀블벅" to "#D6336C", "인보이스" to "#B83227", "나스" to "#F07167"
    )

    fun color(cat: String?): Int = try {
        Color.parseColor(COLORS[cat] ?: "#D1D5DB")
    } catch (e: Exception) {
        Color.LTGRAY
    }

    // ---------------- 저장소 ----------------
    @Volatile var authKey: String = ""
    fun prefs(c: Context): SharedPreferences {
        val sp = c.getSharedPreferences("tt", Context.MODE_PRIVATE)
        authKey = sp.getString("auth", "") ?: ""
        return sp
    }
    // 시트 주소로 가는 요청에만 비밀 키를 붙임
    private fun withAuthUrl(u: String): String =
        if (authKey.isEmpty() || !u.contains("script.google")) u
        else u + (if (u.contains("?")) "&" else "?") + "auth=" + java.net.URLEncoder.encode(authKey, "UTF-8")
    private fun withAuthBody(u: String, body: String): String {
        if (authKey.isEmpty() || !u.contains("script.google")) return body
        return try { org.json.JSONObject(body).put("auth", authKey).toString() } catch (e: Exception) { body }
    }

    fun url(c: Context): String = prefs(c).getString("url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    fun cache(c: Context): JSONObject? =
        prefs(c).getString("cache", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    /** 위젯 모양: 배경 진하기(0~100), 어두운 테마 */
    fun bgAlpha(c: Context): Int = prefs(c).getInt("bg_alpha", 95)
    fun dark(c: Context): Boolean = prefs(c).getBoolean("dark", false)

    /** 글자색: auto(배경 따라) / dark(검정) / light(흰색) */
    fun lightText(c: Context): Boolean = when (prefs(c).getString("text", "auto")) {
        "light" -> true
        "dark" -> false
        else -> dark(c)
    }

    /** 꾸미기: 글자색(0=자동), 시계색, 글자 크기 배율, 시계 크기(sp) */
    fun textColor(c: Context): Int? = prefs(c).getInt("text_color", 0).takeIf { it != 0 }
    fun clockColor(c: Context): Int = prefs(c).getInt("clock_color", Color.parseColor("#A8925A"))
    fun fontScale(c: Context): Float = prefs(c).getInt("font_pct", 100) / 100f
    fun clockSize(c: Context): Int = prefs(c).getInt("clock_sp", 66)
    // 시계 글꼴: 폰에 들어있는 글꼴만 (위젯은 글꼴 파일을 못 씀)
    val CLOCK_FONTS = linkedMapOf(
        "basic" to "기본", "light" to "얇게", "thin" to "아주 얇게", "cond" to "좁게",
        "black" to "아주 굵게", "serif" to "명조", "mono" to "고정폭", "hand" to "손글씨"
    )
    fun clockFont(c: Context): String = prefs(c).getString("clock_font", "basic") ?: "basic"
    fun clockLayout(c: Context): Int = when (clockFont(c)) {
        "light" -> R.layout.clock_light; "thin" -> R.layout.clock_thin; "cond" -> R.layout.clock_cond
        "black" -> R.layout.clock_black; "serif" -> R.layout.clock_serif; "mono" -> R.layout.clock_mono
        "hand" -> R.layout.clock_hand; else -> R.layout.clock_basic
    }

    /** 달력에서 눌러 들어온 날짜 (없으면 오늘) */
    fun selDay(c: Context, id: Int): String? = prefs(c).getString("sel_$id", null)

    /** 위젯 보기: day / week / month */
    fun view(c: Context): String = prefs(c).getString("view", "day") ?: "day"

    /** 위젯마다 따로 보기 저장 (달력 위젯 + 데일리 위젯 따로 둘 수 있게) */
    fun viewFor(c: Context, id: Int): String = prefs(c).getString("view_$id", null) ?: view(c)

    /** 이번 달 달력 첫 칸(일요일) ~ 마지막 칸 */
    fun monthGrid(today: String): Pair<String, String> {
        val first = toCal(today).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1
        val n = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (offset + n + 6) / 7
        val start = shift(fmt(first), -offset)
        return start to shift(start, rows * 7 - 1)
    }

    /** off 달 뒤(앞)의 달력 첫 칸 ~ 마지막 칸 */
    fun monthGridOffset(today: String, off: Int): Pair<String, String> {
        val first = toCal(today).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, off) }
        return monthGrid(fmt(first))
    }

    /** 이번 주 일요일 */
    fun weekStart(today: String): String = shift(today, -(toCal(today).get(Calendar.DAY_OF_WEEK) - 1))

    fun saveCache(c: Context, j: JSONObject) {
        prefs(c).edit().putString("cache", j.toString()).apply()
    }

    // ---------------- 날짜 ----------------
    /** 기록표 기준 날짜 (새벽 5시 전이면 전날) */
    fun trackerDay(offset: Int = 0): String {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < DAY_START) cal.add(Calendar.DATE, -1)
        cal.add(Calendar.DATE, offset)
        return fmt(cal)
    }

    fun fmt(cal: Calendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )

    fun toCal(ds: String): Calendar {
        val p = ds.split("-").map { it.toInt() }
        return Calendar.getInstance().apply {
            clear()
            set(p[0], p[1] - 1, p[2])
        }
    }

    fun shift(ds: String, n: Int): String = fmt(toCal(ds).apply { add(Calendar.DATE, n) })

    fun prettyDay(ds: String): String {
        val cal = toCal(ds)
        val w = arrayOf("일", "월", "화", "수", "목", "금", "토")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return "${cal.get(Calendar.MONTH) + 1}월 ${cal.get(Calendar.DAY_OF_MONTH)}일 ($w)"
    }

    /** 시각(0~23시) → 5시 기준 분 */
    fun toGrid(h: Int, m: Int): Int = ((if (h < DAY_START) h + 24 else h) - DAY_START) * 60 + m

    /** 5시 기준 분 → "HH:MM" */
    fun gridText(min: Int): String {
        val h = (min / 60 + DAY_START) % 24
        return String.format(Locale.US, "%02d:%02d", h, min % 60)
    }

    fun hm(minutes: Int): String = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

    // ---------------- 통신 ----------------
    private fun open(u: String): HttpURLConnection =
        (URL(u).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20000
            readTimeout = 40000
        }

    fun httpGet(start: String): String {
        var u = withAuthUrl(start)
        repeat(6) {
            val c = open(u)
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: throw IOException("redirect")
                c.disconnect()
            } else {
                if (code >= 400) throw IOException("HTTP $code")
                return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
        throw IOException("too many redirects")
    }

    fun httpPost(u: String, body: String): String {
        val c = open(u)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
        c.outputStream.use { it.write(withAuthBody(u, body).toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: throw IOException("redirect")
            c.disconnect()
            return httpGet(loc)
        }
        if (code >= 400) throw IOException("HTTP $code")
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 위젯용 오늘 요약 (Apps Script mode=widget) */
    fun fetchSummary(c: Context, date: String, from: String = date, to: String = date, fresh: Boolean = false): JSONObject {
        val j = JSONObject(httpGet(url(c) + "?mode=widget&date=" + URLEncoder.encode(date, "UTF-8") +
            "&from=" + from + "&to=" + to + (if (fresh) "&fresh=1" else "")))
        if (!j.optBoolean("ok")) throw IOException(j.optString("error", "오류"))
        if (!j.has("blocks")) throw IOException("Apps Script 새 버전 배포 필요")
        return j
    }

    /** 앱 데이터 전체 (수정하기 직전에 최신으로 받아옴) */
    fun fetchAppData(c: Context): JSONObject {
        val j = JSONObject(httpGet(url(c) + "?mode=appdata"))
        if (!j.optBoolean("ok")) throw IOException(j.optString("error", "오류"))
        return j.optJSONObject("appData") ?: JSONObject()
    }

    fun saveKey(c: Context, key: String, value: Any) {
        val body = JSONObject().put("key", key).put("value", value).toString()
        val r = JSONObject(httpPost(url(c), body))
        if (!r.optBoolean("ok")) throw IOException(r.optString("error", "저장 실패"))
    }

    // ---------------- 한 번에 고치기 (Apps Script ops) ----------------
    fun op(type: String, day: String): JSONObject = JSONObject().put("t", type).put("d", day)

    // ---- 할 일 ↔ 작업 스프레드 '📅 스케쥴' 연결 (내용은 숨김) ----
    const val SCHED = "📅 스케쥴"
    private fun tlnkKey(day: String, text: String) = "tlnk_${day}_$text"
    fun schedLinked(c: Context, day: String, text: String) = prefs(c).getBoolean(tlnkKey(day, text), false)
    fun setSchedLink(c: Context, day: String, text: String, on: Boolean) {
        val e = prefs(c).edit()
        if (on) e.putBoolean(tlnkKey(day, text), true) else e.remove(tlnkKey(day, text))
        e.apply()
    }
    /** 할 일 체크/지우기/고치기 때 연결된 '📅 스케쥴' 칸도 같이 */
    fun schedOpsFor(c: Context, o: JSONObject): JSONObject? {
        val day = o.optString("d"); val t = o.optString("t")
        val text = if (t == "todo_edit") o.optString("old") else o.optString("text")
        if (!schedLinked(c, day, text)) return null
        return when (t) {
            "todo_set" -> op("wt_set", day).put("text", SCHED).put("done", o.optBoolean("done"))
            "todo_del" -> { setSchedLink(c, day, text, false); op("wt_del", day).put("text", SCHED) }
            "todo_edit" -> { setSchedLink(c, day, text, false); setSchedLink(c, day, o.optString("text"), true); null }
            else -> null
        }
    }

    /** 시트에 바로 적용 (한 번 왕복) */
    fun sendOps(c: Context, ops: JSONArray) {
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            if (o.optString("t").startsWith("todo_")) schedOpsFor(c, o)?.let { ops.put(it) }
        }
        val r = JSONObject(httpPost(url(c), JSONObject().put("ops", ops).toString()))
        if (!r.optBoolean("ok")) {
            val err = r.optString("error", "저장 실패")
            throw IOException(if (err.contains("No key")) "Apps Script 새 버전 배포 필요" else err)
        }
    }

    private fun objOf(app: JSONObject, k: String): JSONObject =
        app.optJSONObject(k) ?: JSONObject().also { app.put(k, it) }

    /** 같은 고치기를 폰에 있는 데이터에도 바로 적용 (화면 즉시 반영용) */
    fun applyOps(app: JSONObject, ops: JSONArray) {
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            val d = o.optString("d")
            when (o.optString("t")) {
                "todo_add" -> {
                    val td = objOf(app, "todos")
                    val arr = td.optJSONArray(d) ?: JSONArray().also { td.put(d, it) }
                    arr.put(JSONObject().put("text", o.optString("text")).put("done", false))
                }
                "todo_del", "todo_set" -> {
                    val td = objOf(app, "todos")
                    val arr = td.optJSONArray(d) ?: continue
                    val out = JSONArray()
                    var hit = false
                    for (j in 0 until arr.length()) {
                        val x = arr.optJSONObject(j) ?: continue
                        if (!hit && x.optString("text") == o.optString("text")) {
                            hit = true
                            if (o.optString("t") == "todo_del") continue
                            x.put("done", o.optBoolean("done"))
                        }
                        out.put(x)
                    }
                    td.put(d, out)
                }
                "todo_edit" -> {
                    val td = objOf(app, "todos")
                    val arr = td.optJSONArray(d) ?: JSONArray().also { td.put(d, it) }
                    var hit = false
                    for (j in 0 until arr.length()) {
                        val x = arr.optJSONObject(j) ?: continue
                        if (x.optString("text") == o.optString("old")) { x.put("text", o.optString("text")); hit = true; break }
                    }
                    if (!hit) arr.put(JSONObject().put("text", o.optString("text")).put("done", false))
                }
                "plan_set" -> {
                    val hp = objOf(app, "hourPlans")
                    val day = hp.optJSONObject(d) ?: JSONObject().also { hp.put(d, it) }
                    val h = o.optInt("h").toString()
                    val text = o.optString("text")
                    if (text.isBlank()) day.remove(h) else day.put(h, text)
                }
                "paint" -> paintRange(objOf(app, "manualBlocks"), d, o.optInt("s"), o.optInt("e"), o.optString("cat"))
                "block_del" -> removeBlock(objOf(app, "manualBlocks"), d, o.optInt("s"), o.optInt("e"))
            }
        }
    }

    /** 칠하기 → ops (새벽 5시 넘어가면 다음 날로 나눔) */
    /** 수면: 'ds 밤'에 자서 다음 날 아침에 깸. 18시~새벽 5시 = 그날 밤, 5시~18시 = 다음 날 아침 */
    fun sleepOps(ds: String, sh: Int, sm: Int, eh: Int, em: Int): JSONArray {
        fun abs(h: Int, m: Int): Int = toGrid(h, m) + if (h in DAY_START until 18) DAY_MIN else 0
        val s = abs(sh, sm)
        var e = abs(eh, em)
        if (e <= s) e += DAY_MIN
        val ops = JSONArray()
        var a = s
        while (a < e) {
            val dayIdx = a / DAY_MIN
            val b = minOf(e, (dayIdx + 1) * DAY_MIN)
            ops.put(op("paint", shift(ds, dayIdx)).put("s", a - dayIdx * DAY_MIN).put("e", b - dayIdx * DAY_MIN).put("cat", "수면"))
            a = b
        }
        return ops
    }

    fun paintOps(ds: String, sh: Int, sm: Int, eh: Int, em: Int, cat: String): JSONArray {
        val s = toGrid(sh, sm)
        val e = toGrid(eh, em)
        val ops = JSONArray()
        if (e > s) ops.put(op("paint", ds).put("s", s).put("e", e).put("cat", cat))
        else {
            ops.put(op("paint", ds).put("s", s).put("e", DAY_MIN).put("cat", cat))
            if (e > 0) ops.put(op("paint", shift(ds, 1)).put("s", 0).put("e", e).put("cat", cat))
        }
        return ops
    }

    /** 위젯 캐시로 입력창 목록을 바로 보여주기 위한 앱데이터 모양 */
    fun appFromCache(c: Context): JSONObject? {
        val cache = cache(c) ?: return null
        return appShape(cache)
    }

    private fun appShape(cache: JSONObject): JSONObject {
        val day = cache.optString("date")
        val app = JSONObject()
        app.put("todos", JSONObject().put(day, JSONArray((cache.optJSONArray("todos") ?: JSONArray()).toString())))
        app.put("hourPlans", JSONObject().put(day, JSONObject((cache.optJSONObject("plans") ?: JSONObject()).toString())))
        app.put("manualBlocks", JSONObject().put(day, JSONArray((cache.optJSONArray("blocks") ?: JSONArray()).toString())))
        val days = cache.optJSONObject("days")
        if (days != null) {
            val hp = app.getJSONObject("hourPlans")
            val ks = days.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                if (k == day) continue
                days.optJSONObject(k)?.optJSONObject("p")?.let { hp.put(k, JSONObject(it.toString())) }
            }
        }
        return app
    }

    /** 위젯 캐시(오늘 요약)에 바로 반영 */
    fun applyOpsToCache(c: Context, ops: JSONArray) {
        val cache = cache(c) ?: return
        val day = cache.optString("date")
        val days = cache.optJSONObject("days")
        val app = appShape(cache)
        applyOps(app, ops)
        // 작업 스프레드(슾) 줄도 화면에 바로
        if (days != null) for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            val t = o.optString("t")
            if (!t.startsWith("wt_")) continue
            val d = o.optString("d")
            val item = days.optJSONObject(d) ?: JSONObject().also { days.put(d, it) }
            val arr = item.optJSONArray("wt") ?: JSONArray().also { item.put("wt", it) }
            val out = JSONArray()
            var hit = false
            for (j in 0 until arr.length()) {
                val x = arr.optJSONObject(j) ?: continue
                val same = x.optString("text") == (if (t == "wt_edit") o.optString("old") else o.optString("text"))
                if (!hit && same) {
                    hit = true
                    when (t) {
                        "wt_del" -> continue
                        "wt_set" -> x.put("done", o.optBoolean("done"))
                        "wt_edit" -> x.put("text", o.optString("text"))
                    }
                }
                out.put(x)
            }
            if (t == "wt_add" || (t == "wt_edit" && !hit)) out.put(JSONObject().put("text", o.optString("text")).put("done", false))
            item.put("wt", out)
        }
        cache.put("todos", app.getJSONObject("todos").optJSONArray(day) ?: JSONArray())
        cache.put("plans", app.getJSONObject("hourPlans").optJSONObject(day) ?: JSONObject())
        cache.put("blocks", app.getJSONObject("manualBlocks").optJSONArray(day) ?: JSONArray())
        if (days != null) {
            val hp = app.getJSONObject("hourPlans")
            val ks = hp.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                val item = days.optJSONObject(k) ?: JSONObject().also { days.put(k, it) }
                item.put("p", hp.optJSONObject(k) ?: JSONObject())
            }
        }
        saveCache(c, cache)
    }

    // ---------------- 칸 칠하기 ----------------
    /** ds 날짜의 [s, e) 구간을 cat 으로 칠함 (겹치는 칸은 덮어씀) */
    fun paintRange(mb: JSONObject, ds: String, s: Int, e: Int, cat: String) {
        if (e <= s) return
        val arr = mb.optJSONArray(ds) ?: JSONArray()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val bs = b.optInt("startMin", -1)
            val be = b.optInt("endMin", -1)
            if (bs < 0 || be <= bs) continue
            if (be <= s || bs >= e) {
                out.add(b); continue
            }
            if (bs < s) out.add(JSONObject(b.toString()).put("endMin", s))
            if (be > e) out.add(JSONObject(b.toString()).put("startMin", e))
        }
        out.add(JSONObject().put("startMin", s).put("endMin", e).put("category", cat))
        out.sortBy { it.optInt("startMin") }
        val merged = mutableListOf<JSONObject>()
        for (b in out) {
            val last = merged.lastOrNull()
            if (last != null && last.optInt("endMin") == b.optInt("startMin") &&
                last.optString("category") == b.optString("category")
            ) {
                last.put("endMin", b.optInt("endMin"))
            } else merged.add(JSONObject(b.toString()))
        }
        mb.put(ds, JSONArray(merged))
    }

    /** 시작~끝 시각으로 칠하기. 새벽 5시를 넘어가면 다음 날로 나눔 */
    fun paintTimes(mb: JSONObject, ds: String, sh: Int, sm: Int, eh: Int, em: Int, cat: String) {
        val s = toGrid(sh, sm)
        val e = toGrid(eh, em)
        if (e > s) paintRange(mb, ds, s, e, cat)
        else {
            paintRange(mb, ds, s, DAY_MIN, cat)
            if (e > 0) paintRange(mb, shift(ds, 1), 0, e, cat)
        }
    }

    fun removeBlock(mb: JSONObject, ds: String, s: Int, e: Int) {
        val arr = mb.optJSONArray(ds) ?: return
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            if (b.optInt("startMin") == s && b.optInt("endMin") == e) continue
            out.put(b)
        }
        mb.put(ds, out)
    }
}
