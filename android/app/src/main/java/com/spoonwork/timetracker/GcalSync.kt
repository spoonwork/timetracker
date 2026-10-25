package com.spoonwork.timetracker

import org.json.JSONArray
import org.json.JSONObject

/**
 * 시트의 일정 ↔ 구글 캘린더 맞추기 (폰이 혼자 담당 → 중복 없음)
 * - 연결된 일정이 다른 기기(웹앱)에서 글자가 바뀜 → 캘린더 제목도 고침
 * - 연결된 일정이 시트에서 지워짐 → 캘린더에서도 지움
 * - 웹앱에서 새로 적은 일정(gcalWant 표시) → 캘린더에 추가하고 표시 지움
 */
object GcalSync {
    private const val GUARD_MS = 3 * 60_000L   // 방금 폰에서 고친 건 시트 반영 전일 수 있어서 잠깐 건드리지 않음

    /** 결과 한 줄 (↻ 눌렀을 때 알림용) */
    fun run(ctx: android.content.Context, j: JSONObject, from: String, to: String): String? {
        if (!Data.prefs(ctx).getBoolean("gcal", true)) return null
        if (!Cal.canWrite(ctx)) return "구글 캘린더 쓰기 권한이 없어요 · 앱 열고 ＋일정에서 캘린더 추가를 한 번 해주세요"
        val days = j.optJSONObject("days") ?: return null
        val now = System.currentTimeMillis()
        val today = Data.trackerDay()
        var changed = 0

        // 1) 연결된 일정 확인
        for ((d, h, l0) in Cal.allLinks(ctx)) {
            if (d < from || d > to || !days.has(d)) continue
            if (now - l0.at < GUARD_MS) continue
            // 예전 버전에서 연결한 건 제목을 안 적어둬서 캘린더에서 직접 읽어옴
            val l = if (l0.title.isEmpty()) l0.copy(title = Cal.eventTitle(ctx, l0.id) ?: "") else l0
            val text = days.optJSONObject(d)?.optJSONObject("p")?.optString(h.toString(), "")?.trim() ?: ""
            when {
                text.isEmpty() -> {
                    Cal.delete(ctx, l.id); Cal.setLink(ctx, d, h, null); changed++
                }
                l.title.isEmpty() -> Cal.setLink(ctx, d, h, l.copy(title = text, at = 0L))
                text != l.title -> {
                    if (Cal.update(ctx, l.id, text, l.begin, l.end)) Cal.setLink(ctx, d, h, l.copy(title = text, at = 0L))
                    else Cal.setLink(ctx, d, h, null)   // 캘린더에서 직접 지운 경우
                    changed++
                }
            }
        }

        // 2) 웹앱에서 새로 적은 일정 → 캘린더에 추가
        val clear = JSONArray()
        val keys = days.keys()
        while (keys.hasNext()) {
            val d = keys.next()
            val gw = days.optJSONObject(d)?.optJSONObject("gw") ?: continue
            val plans = days.optJSONObject(d)?.optJSONObject("p")
            val hs = gw.keys()
            while (hs.hasNext()) {
                val hk = hs.next()
                val h = hk.toIntOrNull()
                val text = plans?.optString(hk, "")?.trim() ?: ""
                if (h != null && text.isNotEmpty() && d >= today && Cal.link(ctx, d, h) == null) {
                    val begin = Data.toCal(d).timeInMillis +
                        (if (h < Data.DAY_START) h + 24 else h) * 3600_000L
                    val id = Cal.insert(ctx, text, begin, begin + 3600_000L)
                    if (id != null) { Cal.setLink(ctx, d, h, Cal.Link(id, begin, begin + 3600_000L, text, 0L)); changed++ }
                    else continue   // 실패하면 표시 남겨두고 다음에 다시
                }
                clear.put(JSONObject().put("t", "patch").put("k", "gcalWant").put("p", JSONArray().put(d).put(hk)).put("v", JSONObject.NULL))
            }
        }
        if (clear.length() > 0) {
            try { Data.sendOps(ctx, clear) } catch (_: Exception) {}
        }
        if (changed > 0) Data.prefs(ctx).edit().putLong("gsync_at", now).apply()
        return if (changed > 0) "구글 캘린더에 ${changed}개 반영 ✓" else null
    }
}
