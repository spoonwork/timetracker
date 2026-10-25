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
        try {
            c.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(6) == 0) continue
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
