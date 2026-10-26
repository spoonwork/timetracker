package com.spoonwork.timetracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** 스티커 메모 (PC 스티커와 같은 시트 'stickies') */
object Notes {
    // 키 → (이름, 바탕색, 머리줄색) — PC sticky.py 와 같음
    val COLORS = linkedMapOf(
        "yellow" to Triple("노랑", "#FFF4B8", "#F6D860"),
        "green" to Triple("초록", "#DDF3D3", "#A3D98F"),
        "pink" to Triple("분홍", "#FFDCEA", "#F2A7C3"),
        "purple" to Triple("보라", "#E9E0FF", "#BBA6F2"),
        "blue" to Triple("하늘", "#D8EDFF", "#90C7F3"),
        "gray" to Triple("회색", "#EFEFEF", "#C4C4C4"),
        "white" to Triple("흰색", "#FFFFFF", "#DDDDDD"),
    )

    fun bg(key: String?): Int = Color.parseColor((COLORS[key] ?: COLORS["yellow"]!!).second)
    fun head(key: String?): Int = Color.parseColor((COLORS[key] ?: COLORS["yellow"]!!).third)

    fun cache(c: Context): JSONObject =
        try { JSONObject(Data.prefs(c).getString("stickies", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun saveCache(c: Context, j: JSONObject) = Data.prefs(c).edit().putString("stickies", j.toString()).apply()

    fun fetch(c: Context): JSONObject {
        val st = Data.fetchAppData(c).optJSONObject("stickies") ?: JSONObject()
        saveCache(c, st)
        return st
    }

    fun patch(id: String, field: String?, v: Any?): JSONObject {
        val p = JSONArray().put(id)
        if (field != null) p.put(field)
        return JSONObject().put("t", "patch").put("k", "stickies").put("p", p).put("v", v ?: JSONObject.NULL)
    }

    /** 바꾼 걸 캐시에 먼저 반영하고 시트로 보냄 */
    fun send(c: Context, ops: JSONArray) {
        val j = cache(c)
        for (i in 0 until ops.length()) {
            val o = ops.getJSONObject(i)
            val p = o.getJSONArray("p")
            val id = p.getString(0)
            val v = o.opt("v")
            if (p.length() == 1) { if (v == null || v == JSONObject.NULL) j.remove(id) else j.put(id, v) }
            else (j.optJSONObject(id) ?: JSONObject().also { j.put(id, it) }).put(p.getString(1), v)
        }
        saveCache(c, j)
        val ctx = c.applicationContext
        Thread {
            try { Data.sendOps(ctx, ops) } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "메모 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    fun title(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    fun bodyOf(text: String): String {
        val lines = text.lines()
        val i = lines.indexOfFirst { it.isNotBlank() }
        return if (i < 0) "" else lines.drop(i + 1).joinToString("\n").trim('\n')
    }
}

/** Keep 처럼: 위에 '메모 작성…', 아래 두 줄 카드 */
class NotesActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var colL: LinearLayout
    private lateinit var colR: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private val main = Color.parseColor("#1F2937")
    private val sub = Color.parseColor("#6B7280")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#FAFAF8")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAF8"))
            setPadding(dp(14), dp(14), dp(14), 0)
        }
        // 위: 제목 + 새로고침
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "메모"; textSize = 22f; setTextColor(main); setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, -2, 1f))
        status = TextView(this).apply { textSize = 12f; setTextColor(sub); setPadding(0, 0, dp(10), 0) }
        top.addView(status)
        top.addView(TextView(this).apply {
            text = "↻"; textSize = 20f; setTextColor(sub); setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { load() }
        })
        root.addView(top)
        // 검색
        search = EditText(this).apply {
            hint = "메모 검색"
            textSize = 14f
            setSingleLine(true)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#EFEDE8")) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { show(Notes.cache(this@NotesActivity)) }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // 메모 작성…
        val add = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
            setOnClickListener { open(null) }
        }
        add.addView(TextView(this@NotesActivity).apply { text = "＋"; textSize = 20f; setTextColor(Color.parseColor("#F5B400")) })
        add.addView(TextView(this@NotesActivity).apply { text = "  메모 작성…"; textSize = 15f; setTextColor(sub) })
        root.addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        // 카드 두 줄
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colR = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(colL, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        cols.addView(colR, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        val sv = ScrollView(this).apply { isFillViewport = true }
        sv.addView(cols)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        show(Notes.cache(this))
        load()
    }

    private fun load() {
        status.text = "불러오는 중…"
        Thread {
            try {
                val j = Notes.fetch(this)
                runOnUiThread { status.text = ""; show(j) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "오프라인" }
            }
        }.start()
    }

    private fun show(all: JSONObject) {
        colL.removeAllViews(); colR.removeAllViews()
        val q = search.text.toString().trim()
        val list = all.keys().asSequence().mapNotNull { k -> all.optJSONObject(k)?.let { k to it } }
            .filter { q.isEmpty() || it.second.optString("text").contains(q, ignoreCase = true) }
            .sortedByDescending { it.second.optLong("updated", it.second.optLong("created", 0)) }
            .toList()
        if (list.isEmpty()) {
            colL.addView(TextView(this).apply {
                text = if (q.isEmpty()) "메모가 없어요" else "찾는 메모가 없어요"; setTextColor(sub); setPadding(dp(4), dp(20), 0, 0)
            })
            return
        }
        var hl = 0; var hr = 0
        for ((id, n) in list) {
            val card = card(id, n)
            val est = 3 + if (n.optBoolean("folded")) 0 else minOf(10, Notes.bodyOf(n.optString("text")).length / 14 + Notes.bodyOf(n.optString("text")).count { it == '\n' })
            if (hl <= hr) { colL.addView(card); hl += est } else { colR.addView(card); hr += est }
        }
    }

    private fun card(id: String, n: JSONObject): View {
        val text = n.optString("text")
        val folded = n.optBoolean("folded")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Notes.bg(n.optString("color")))
                setStroke(dp(1), Color.parseColor("#14000000"))
            }
            setOnClickListener { open(id) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            this.text = Notes.title(text).ifEmpty { "(빈 메모)" }
            textSize = 15f; setTextColor(main); setTypeface(typeface, Typeface.BOLD)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // 접기 ▾ / 펴기 ▸ (PC 스티커와 같이 움직임)
        head.addView(TextView(this).apply {
            this.text = if (folded) "▸" else "▾"
            textSize = 16f; setTextColor(sub); setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener {
                Notes.send(this@NotesActivity, JSONArray().put(Notes.patch(id, "folded", !folded)))
                show(Notes.cache(this@NotesActivity))
            }
        })
        box.addView(head)
        val body = Notes.bodyOf(text)
        if (!folded && body.isNotBlank()) {
            box.addView(TextView(this).apply {
                this.text = body; textSize = 14f; setTextColor(Color.parseColor("#374151"))
                maxLines = 12; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), dp(6), 0)
            })
        }
        return box
    }

    private fun open(id: String?) {
        startActivity(Intent(this, NoteEditActivity::class.java).putExtra("id", id ?: ""))
    }
}

/** 메모 쓰기 / 고치기 (첫 줄 = 제목) */
class NoteEditActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private var id = ""
    private var isNew = false
    private var color = "yellow"
    private var deleted = false
    private lateinit var edit: EditText
    private lateinit var root: LinearLayout
    private var original = ""
    private var originalColor = "yellow"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("id") ?: ""
        val n = if (id.isNotEmpty()) Notes.cache(this).optJSONObject(id) else null
        isNew = n == null
        if (isNew) id = "n" + System.currentTimeMillis() + (100..999).random()
        color = n?.optString("color")?.ifEmpty { "yellow" } ?: Data.prefs(this).getString("note_color", "yellow")!!
        original = n?.optString("text") ?: ""
        originalColor = color

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(Color.parseColor("#374151")); setPadding(dp(10), dp(4), dp(14), dp(4))
            setOnClickListener { finish() }
        })
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((k, v) in Notes.COLORS) {
            dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor(v.third)); setStroke(dp(1), Color.parseColor("#33000000"))
                }
                setOnClickListener { color = k; paint() }
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        }
        bar.addView(dots, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            text = "🗑"; textSize = 19f; setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                AlertDialog.Builder(this@NoteEditActivity).setMessage("이 메모를 지울까요?")
                    .setPositiveButton("지우기") { _, _ ->
                        deleted = true
                        if (!isNew) Notes.send(this@NoteEditActivity, JSONArray().put(Notes.patch(this@NoteEditActivity.id, null, null)))
                        finish()
                    }.setNegativeButton("취소", null).show()
            }
        })
        root.addView(bar)

        edit = EditText(this).apply {
            setText(original)
            hint = "첫 줄은 제목\n내용…"
            textSize = 16f
            gravity = Gravity.TOP
            background = null
            setTextColor(Color.parseColor("#1F2937"))
            setPadding(dp(20), dp(8), dp(20), dp(20))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { styleTitle(s) }
            })
        }
        styleTitle(edit.text)
        val sv = ScrollView(this).apply { isFillViewport = true }
        sv.addView(edit, LinearLayout.LayoutParams(-1, -1))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        paint()
        if (isNew) {
            edit.requestFocus()
            edit.postDelayed({
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, 0)
            }, 200)
        }
    }

    /** 첫 줄 굵고 크게 */
    private fun styleTitle(s: Editable?) {
        s ?: return
        s.getSpans(0, s.length, StyleSpan::class.java).forEach { s.removeSpan(it) }
        s.getSpans(0, s.length, RelativeSizeSpan::class.java).forEach { s.removeSpan(it) }
        val end = s.indexOf('\n').let { if (it < 0) s.length else it }
        if (end > 0) {
            s.setSpan(StyleSpan(Typeface.BOLD), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(RelativeSizeSpan(1.15f), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun paint() {
        root.setBackgroundColor(Notes.bg(color))
        window.statusBarColor = Notes.bg(color)
    }

    override fun onPause() {
        super.onPause()
        if (deleted) return
        val t = edit.text.toString()
        val ops = JSONArray()
        val now = System.currentTimeMillis()
        if (isNew) {
            if (t.isBlank()) return
            ops.put(Notes.patch(id, null, JSONObject().put("text", t).put("color", color)
                .put("folded", false).put("top", false).put("created", now).put("updated", now)))
            isNew = false
            Data.prefs(this).edit().putString("note_color", color).apply()
        } else {
            if (t != original) ops.put(Notes.patch(id, "text", t)).put(Notes.patch(id, "updated", now))
            if (color != originalColor) ops.put(Notes.patch(id, "color", color))
        }
        original = t; originalColor = color
        if (ops.length() > 0) Notes.send(this, ops)
    }
}
