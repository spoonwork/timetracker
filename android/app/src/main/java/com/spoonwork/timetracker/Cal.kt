package com.spoonwork.timetracker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 폰에 동기화된 캘린더(구글 캘린더 등) 일정 읽기 + 일진/공휴일 */
object Cal {

    data class Ev(
        val id: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val color: Int
    ) {
        /** 이 일정이 걸쳐 있는 날짜들 (yyyy-MM-dd, 달력 날짜 기준) */
        fun days(): List<String> {
            val out = mutableListOf<String>()
            val c: Calendar
            val last: String
            if (allDay) {
                // 하루종일 일정은 UTC 자정 기준으로 저장됨
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = begin }
                val ue = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = end - 1 }
                c = Calendar.getInstance().apply {
                    clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                }
                last = String.format(
                    Locale.US, "%04d-%02d-%02d",
                    ue.get(Calendar.YEAR), ue.get(Calendar.MONTH) + 1, ue.get(Calendar.DAY_OF_MONTH)
                )
            } else {
                c = Calendar.getInstance().apply { timeInMillis = begin }
                val e = Calendar.getInstance().apply { timeInMillis = maxOf(begin, end - 1) }
                last = Data.fmt(e)
            }
            var guard = 0
            while (guard++ < 62) {
                val ds = Data.fmt(c)
                out.add(ds)
                if (ds >= last) break
                c.add(Calendar.DATE, 1)
            }
            return out
        }
    }

    fun permitted(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun enabled(c: Context): Boolean = Data.prefs(c).getBoolean("gcal", true) && permitted(c)

    /** fromDs ~ toDs(포함) 사이 일정 */
    fun events(c: Context, fromDs: String, toDs: String): List<Ev> {
        if (!enabled(c)) return emptyList()
        val from = Data.toCal(fromDs).timeInMillis
        val to = Data.toCal(Data.shift(toDs, 1)).timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - 24 * 3600 * 1000L)   // 하루종일 일정 시간대 여유
            ContentUris.appendId(it, to + 24 * 3600 * 1000L)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.VISIBLE
        )
        val out = mutableListOf<Ev>()
        val linked = linkedIds(c)       // 트래커 일정으로 이미 보이는 건 빼기 (두 번 안 보이게)
        try {
            c.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(6) == 0) continue
                    if (linked.contains(cur.getLong(0))) continue
                    val ev = Ev(
                        cur.getLong(0),
                        cur.getString(1) ?: "(제목 없음)",
                        cur.getLong(2),
                        cur.getLong(3),
                        cur.getInt(4) == 1,
                        cur.getInt(5)
                    )
                    val ds = ev.days()
                    if (ds.isEmpty() || ds.last() < fromDs || ds.first() > toDs) continue
                    out.add(ev)
                }
            }
        } catch (e: Exception) {
            // 권한 없거나 캘린더 없음
        }
        return out
    }

    /** 내 구글 캘린더(기본)에 일정 추가 */
    fun insert(c: Context, title: String, begin: Long, end: Long): Long? {
        return try {
            val calId = writableCalendar(c) ?: return null
            val v = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun update(c: Context, id: Long, title: String, begin: Long, end: Long): Boolean = try {
        val v = android.content.ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null) > 0
    } catch (e: Exception) { false }

    fun delete(c: Context, id: Long): Boolean = try {
        c.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    } catch (e: Exception) { false }

    // ---- 트래커 일정 ↔ 구글 캘린더 일정 연결 (폰에 저장) ----
    /** title: 연결할 때 일정 글자 (다른 기기에서 글자가 바뀌었는지 비교용), at: 연결/수정한 시각 */
    data class Link(val id: Long, val begin: Long, val end: Long,
                    val title: String = "", val at: Long = System.currentTimeMillis())

    private fun parseLink(s: String): Link? {
        val p = s.split("|", limit = 5)
        return try {
            Link(p[0].toLong(), p[1].toLong(), p[2].toLong(),
                if (p.size > 4) p[4] else "", if (p.size > 3) p[3].toLong() else 0L)
        } catch (e: Exception) { null }
    }

    fun link(c: Context, day: String, h: Int): Link? {
        val s = Data.prefs(c).getString("lnk_${day}_$h", null) ?: return null
        return parseLink(s)
    }

    /** 연결된 일정 전부: (날짜, 시) → Link */
    fun allLinks(c: Context): List<Triple<String, Int, Link>> =
        Data.prefs(c).all.entries.mapNotNull { (k, v) ->
            if (!k.startsWith("lnk_")) return@mapNotNull null
            val rest = k.removePrefix("lnk_")
            val i = rest.lastIndexOf('_')
            if (i < 0) return@mapNotNull null
            val h = rest.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            val l = (v as? String)?.let { parseLink(it) } ?: return@mapNotNull null
            Triple(rest.substring(0, i), h, l)
        }

    fun setLink(c: Context, day: String, h: Int, l: Link?) {
        val e = Data.prefs(c).edit()
        if (l == null) e.remove("lnk_${day}_$h")
        else e.putString("lnk_${day}_$h", "${l.id}|${l.begin}|${l.end}|${l.at}|${l.title}")
        e.apply()
    }

    fun canWrite(c: Context): Boolean =
        enabled(c) && c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun linkedIds(c: Context): Set<Long> =
        Data.prefs(c).all.filterKeys { it.startsWith("lnk_") }.values
            .mapNotNull { (it as? String)?.split("|")?.firstOrNull()?.toLongOrNull() }.toSet()

    private fun writableCalendar(c: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        var best: Long? = null
        var bestScore = -1
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                var score = 0
                if (cur.getInt(1) == 1) score += 4
                if (cur.getString(2) == "com.google") score += 2
                if (cur.getInt(4) == 1) score += 1
                if (score > bestScore) { bestScore = score; best = cur.getLong(0) }
            }
        }
        return best
    }

    // ---------------- 일진 (웹앱과 같은 계산) ----------------
    private val GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val JI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")

    fun ganji(ds: String): String {
        val base = Calendar.getInstance().apply { clear(); set(1900, 1, 20, 12, 0, 0) }
        val t = Data.toCal(ds).apply { set(Calendar.HOUR_OF_DAY, 12) }
        val diff = Math.round((t.timeInMillis - base.timeInMillis) / 86400000.0).toInt()
        val idx = ((diff % 60) + 60) % 60
        return GAN[idx % 10] + JI[idx % 12]
    }

    /** 월주 (절기 기준, 웹앱 getMonthGanji 와 같은 계산) */
    private val TERMS = arrayOf(intArrayOf(2, 4), intArrayOf(3, 6), intArrayOf(4, 5), intArrayOf(5, 6),
        intArrayOf(6, 6), intArrayOf(7, 7), intArrayOf(8, 8), intArrayOf(9, 8), intArrayOf(10, 8),
        intArrayOf(11, 8), intArrayOf(12, 7), intArrayOf(1, 6))
    private val MONTH_JI = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1)

    fun monthGanji(ds: String): String {
        val p = ds.split("-").map { it.toInt() }
        val y = p[0]; val m = p[1]; val d = p[2]
        val sajuYear = if (m < 2 || (m == 2 && d < 4)) y - 1 else y
        val yearGan = (((sajuYear - 1984) % 60 + 60) % 60) % 10
        // 지금 날짜가 몇 번째 절기 뒤인지 (입춘=0 … 대설=10, 소한=11)
        val term = when {
            (m == 1 && d >= 6) || (m == 2 && d < 4) -> 11
            m == 1 -> 10
            else -> (10 downTo 0).first { i -> m > TERMS[i][0] || (m == TERMS[i][0] && d >= TERMS[i][1]) }
        }
        val gan = ((yearGan * 2 + 2) % 10 + term) % 10
        return GAN[gan] + JI[MONTH_JI[term]]
    }

    // ---------------- 공휴일 ----------------
    val HOLIDAYS = mapOf(
        "2025-12-25" to "크리스마스",
        "2026-01-01" to "신정", "2026-02-16" to "설날 연휴", "2026-02-17" to "설날",
        "2026-02-18" to "설날 연휴", "2026-03-01" to "삼일절", "2026-03-02" to "대체공휴일",
        "2026-05-05" to "어린이날", "2026-05-24" to "석가탄신일", "2026-05-25" to "대체공휴일", "2026-06-03" to "지방선거",
        "2026-06-06" to "현충일", "2026-08-15" to "광복절", "2026-08-17" to "대체공휴일",
        "2026-09-24" to "추석 연휴", "2026-09-25" to "추석", "2026-09-26" to "추석 연휴",
        "2026-10-03" to "개천절", "2026-10-05" to "대체공휴일", "2026-10-09" to "한글날",
        "2026-12-25" to "크리스마스",
        "2027-01-01" to "신정", "2027-02-06" to "설날 연휴", "2027-02-07" to "설날",
        "2027-02-08" to "설날 연휴", "2027-02-09" to "대체공휴일", "2027-03-01" to "삼일절",
        "2027-05-05" to "어린이날", "2027-05-13" to "석가탄신일", "2027-06-06" to "현충일",
        "2027-08-15" to "광복절", "2027-08-16" to "대체공휴일", "2027-09-14" to "추석 연휴",
        "2027-09-15" to "추석", "2027-09-16" to "추석 연휴", "2027-10-03" to "개천절",
        "2027-10-04" to "대체공휴일", "2027-10-09" to "한글날", "2027-10-11" to "대체공휴일",
        "2027-12-25" to "크리스마스", "2027-12-27" to "대체공휴일"
    )
}
