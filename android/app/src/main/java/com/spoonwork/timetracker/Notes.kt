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

/** 사용자가 넣은 서식 (제목 굵게와 구분) */
class UBold : StyleSpan(Typeface.BOLD)
class UStrike : android.text.style.StrikethroughSpan()
class UHigh : android.text.style.BackgroundColorSpan(Color.parseColor("#FFE066"))
class UItalic : StyleSpan(Typeface.ITALIC)
class UUnder : android.text.style.UnderlineSpan()
class UFore(val hex: String) : android.text.style.ForegroundColorSpan(Color.parseColor(hex))
class UBack(val hex: String) : android.text.style.BackgroundColorSpan(Color.parseColor(hex))

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

    fun saveCache(c: Context, j: JSONObject) {
        Data.prefs(c).edit().putString("stickies", j.toString()).apply()
        try { NoteWidget.render(c.applicationContext) } catch (_: Exception) {}   // 메모 위젯도 같이 새로
    }

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

    /** PC에서 넣은 이미지·PDF가 있으면 표시 (그림은 PC에서만 보임) */
    fun attMark(n: JSONObject): String {
        val a = n.optJSONObject("att") ?: return ""
        if (a.optBoolean("up")) return ""
        return if (a.optString("kind") == "pdf") "[PDF·PC에서 보기] " else "[이미지·PC에서 보기] "
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
            .sortedWith(compareByDescending<Pair<String, JSONObject>> { it.second.optBoolean("fav") }
                .thenByDescending { it.second.optLong("updated", it.second.optLong("created", 0)) })
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
                cornerRadius = dp(8).toFloat(); setColor(Notes.bg(n.optString("color")))
                setStroke(dp(1), Color.parseColor("#14000000"))
            }
            setOnClickListener { open(id) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            this.text = Notes.attMark(n) + Notes.title(text).ifEmpty { "(빈 메모)" }
            textSize = 15f; setTextColor(main); setTypeface(typeface, Typeface.BOLD)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val fav = n.optBoolean("fav")
        head.addView(TextView(this).apply {
            this.text = if (fav) "★" else "☆"
            textSize = 17f; setTextColor(if (fav) Color.parseColor("#C98A00") else Color.parseColor("#9CA3AF"))
            setPadding(dp(8), 0, dp(2), 0)
            setOnClickListener {
                Notes.send(this@NotesActivity, JSONArray().put(Notes.patch(id, "fav", !fav)))
                show(Notes.cache(this@NotesActivity))
            }
        })
        // 접기 ▾ / 펴기 ▸ (PC 스티커와 같이 움직임)
        head.addView(TextView(this).apply {
            this.text = if (folded) "▸" else "▾"
            textSize = 16f; setTextColor(sub); setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener {
                Notes.send(this@NotesActivity, JSONArray().put(Notes.patch(id, "folded", !folded)))
                show(Notes.cache(this@NotesActivity))
            }
        })
        val att = n.optJSONObject("att")
        if (NoteFiles.fid(att).isNotEmpty()) {
            val iv = android.widget.ImageView(this).apply {
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                maxHeight = dp(180)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat())
                    }
                }
            }
            box.addView(iv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            val w = resources.displayMetrics.widthPixels / 2
            Thread {
                val b = NoteFiles.bitmap(applicationContext, att, w)
                if (b != null) runOnUiThread { iv.setImageBitmap(b) }
            }.start()
        }
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
    private var fav = false
    private var origFav = false
    private var fs = 11
    private var origFs = 11
    private var origFmt = "[]"
    private lateinit var favBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("id") ?: ""
        val n = if (id.isNotEmpty()) Notes.cache(this).optJSONObject(id) else null
        isNew = n == null
        if (isNew) id = "n" + System.currentTimeMillis() + (100..999).random()
        color = n?.optString("color")?.ifEmpty { "yellow" } ?: Data.prefs(this).getString("note_color", "yellow")!!
        original = n?.optString("text") ?: ""
        originalColor = color
        fav = n?.optBoolean("fav") ?: false; origFav = fav
        fs = n?.optInt("fs", 11)?.takeIf { it > 0 } ?: 11; origFs = fs
        origFmt = (n?.optJSONArray("fmt") ?: JSONArray()).toString()

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
        favBtn = TextView(this).apply {
            textSize = 22f; setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { fav = !fav; paintFav() }
        }
        bar.addView(favBtn)
        paintFav()
        bar.addView(TextView(this).apply {
            text = "✕"; textSize = 14f; setTextColor(Color.parseColor("#6B7280")); setPadding(dp(10), dp(4), dp(10), dp(4))
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
        // 서식 막대 (PC 스티커와 같은 아이콘): 크기 − 11 + | B I U S | 글자색 | 배경색 | 지우기
        val fbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), dp(4))
        }
        val ink = Color.parseColor("#374151")
        fun fbtn(label: String, style: (TextView) -> Unit = {}, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(ink)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            style(this)
            setOnClickListener { onClick() }
        }.also { fbar.addView(it) }
        fun sep() = fbar.addView(View(this).apply { setBackgroundColor(Color.parseColor("#22000000")) },
            LinearLayout.LayoutParams(dp(1), dp(18)).apply { marginStart = dp(4); marginEnd = dp(4) })
        lateinit var sizeLbl: TextView
        fbtn("−") { fs = maxOf(8, fs - 1); applyFs(); sizeLbl.text = fs.toString() }
        sizeLbl = fbtn(fs.toString(), { it.textSize = 13f; it.setPadding(dp(2), 0, dp(2), 0) }) {}
        fbtn("+") { fs = minOf(28, fs + 1); applyFs(); sizeLbl.text = fs.toString() }
        sep()
        fbtn("B", { it.setTypeface(Typeface.DEFAULT, Typeface.BOLD) }) { toggleSpan { UBold() } }
        fbtn("I", { it.setTypeface(Typeface.SERIF, Typeface.ITALIC) }) { toggleSpan { UItalic() } }
        fbtn("U", { it.paintFlags = it.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG }) { toggleSpan { UUnder() } }
        fbtn("S", { it.paintFlags = it.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }) { toggleSpan { UStrike() } }
        sep()
        fbtn("T▾", { t ->
            t.setTypeface(Typeface.SERIF)
            val sp = android.text.SpannableString("T▾")
            sp.setSpan(android.text.style.UnderlineSpan(), 0, 1, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#E5484D")), 0, 1, 0)
            t.text = sp
        }) { pickColor(false) }
        fbtn("T▾", { t ->
            t.setTypeface(Typeface.SERIF)
            val sp = android.text.SpannableString("T▾")
            sp.setSpan(android.text.style.BackgroundColorSpan(Color.parseColor("#FFE066")), 0, 1, 0)
            t.text = sp
        }) { pickColor(true) }
        sep()
        fbtn("⌫", { it.textSize = 15f }) { clearSel() }
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(fbar)
        root.addView(hs)

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
        loadFmt(n?.optJSONArray("fmt"))
        applyFs()
        styleTitle(edit.text)
        val sv = ScrollView(this).apply { isFillViewport = true }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val att = n?.optJSONObject("att")
        if (NoteFiles.fid(att).isNotEmpty()) addAttView(inner, att!!)
        edit.minHeight = resources.displayMetrics.heightPixels / 2
        inner.addView(edit, LinearLayout.LayoutParams(-1, -2))
        sv.addView(inner, LinearLayout.LayoutParams(-1, -1))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        // 작성 / 수정 날짜
        val fmt = java.text.SimpleDateFormat("yyyy.M.d (E) HH:mm", java.util.Locale.KOREAN)
        val created = n?.optLong("created", 0L) ?: 0L
        val updated = n?.optLong("updated", 0L) ?: 0L
        root.addView(TextView(this).apply {
            text = if (n == null) "새 메모"
                else listOfNotNull(
                    if (created > 0) "작성 " + fmt.format(java.util.Date(created)) else null,
                    if (updated > 0) "수정 " + fmt.format(java.util.Date(updated)) else null
                ).joinToString("   ·   ")
            textSize = 11.5f
            setTextColor(Color.parseColor("#80000000"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(12))
        })
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
    /** PC에서 넣은 이미지·PDF 보기 (PDF는 ◀ ▶) */
    private fun addAttView(parent: LinearLayout, att: JSONObject) {
        val iv = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val msg = TextView(this).apply {
            text = "그림 받는 중…"; textSize = 12f; setTextColor(Color.parseColor("#80000000"))
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16))
        }
        parent.addView(msg)
        parent.addView(iv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(4)) })
        val pdf = NoteFiles.isPdf(att)
        var page = att.optInt("page", 0)
        var total = att.optInt("pages", 1)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE }
        val lab = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#5A4D00")); setPadding(dp(18), 0, dp(18), 0) }
        val w = resources.displayMetrics.widthPixels - dp(28)
        fun load() {
            lab.text = "${page + 1} / $total"
            Thread {
                val f = NoteFiles.ensure(applicationContext, att)
                if (pdf && f != null) total = NoteFiles.pageCount(f).coerceAtLeast(1)
                val b = NoteFiles.bitmap(applicationContext, att, w, page)
                runOnUiThread {
                    if (b != null) { iv.setImageBitmap(b); msg.visibility = View.GONE }
                    else msg.text = "그림을 못 받았어요 (인터넷 확인)"
                    lab.text = "${page + 1} / $total"
                    if (pdf) nav.visibility = View.VISIBLE
                }
            }.start()
        }
        fun navBtn(t: String, step: Int) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.parseColor("#5A4D00"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Notes.head(this@NoteEditActivity.color)) }
            setOnClickListener {
                val np = (page + step).coerceIn(0, total - 1)
                if (np != page) { page = np; load() }
            }
        }
        nav.addView(navBtn("◀", -1)); nav.addView(lab); nav.addView(navBtn("▶", 1))
        parent.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(6)) })
        load()
    }

    private fun styleTitle(s: Editable?) {
        s ?: return
        s.getSpans(0, s.length, StyleSpan::class.java).filter { it !is UBold && it !is UItalic }.forEach { s.removeSpan(it) }
        s.getSpans(0, s.length, RelativeSizeSpan::class.java).forEach { s.removeSpan(it) }
        val end = s.indexOf('\n').let { if (it < 0) s.length else it }
        if (end > 0) {
            s.setSpan(StyleSpan(Typeface.BOLD), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(RelativeSizeSpan(1.15f), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun paintFav() {
        favBtn.text = if (fav) "★" else "☆"
        favBtn.setTextColor(if (fav) Color.parseColor("#C98A00") else Color.parseColor("#6B7280"))
    }

    private fun applyFs() { edit.textSize = 16f * fs / 11f }

    // 글자 위치: 시트에는 '글자 수' 기준(PC와 같게), 폰은 UTF-16 기준이라 바꿔줌
    private fun toCp(s: String, i: Int) = s.codePointCount(0, i.coerceIn(0, s.length))
    private fun fromCp(s: String, cp: Int): Int =
        try { s.offsetByCodePoints(0, cp.coerceIn(0, s.codePointCount(0, s.length))) } catch (e: Exception) { s.length }

    private fun loadFmt(arr: JSONArray?) {
        arr ?: return
        val e = edit.text; val str = e.toString()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            val a = fromCp(str, r.optInt(0)); val b = fromCp(str, r.optInt(1))
            if (b <= a) continue
            val t = r.optString(2)
            val span: Any = try {
                when {
                    t == "b" -> UBold(); t == "s" -> UStrike(); t == "h" -> UHigh()
                    t == "i" -> UItalic(); t == "u" -> UUnder()
                    t.startsWith("fc#") -> UFore(t.substring(2))
                    t.startsWith("hc#") -> UBack(t.substring(2))
                    else -> continue
                }
            } catch (ex: Exception) { continue }
            e.setSpan(span, a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun collectFmt(): JSONArray {
        val e = edit.text; val str = e.toString()
        val out = JSONArray()
        val all = e.getSpans(0, e.length, Any::class.java).mapNotNull { sp ->
            val t = when (sp) {
                is UBold -> "b"; is UStrike -> "s"; is UHigh -> "h"; is UItalic -> "i"; is UUnder -> "u"
                is UFore -> "fc" + sp.hex; is UBack -> "hc" + sp.hex
                else -> null
            } ?: return@mapNotNull null
            val a = e.getSpanStart(sp); val b = e.getSpanEnd(sp)
            if (b > a) Triple(toCp(str, a), toCp(str, b), t) else null
        }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        all.forEach { out.put(JSONArray().put(it.first).put(it.second).put(it.third)) }
        return out
    }

    /** 고른 부분에 서식 넣기/빼기 */
    private fun <T : Any> toggleSpan(make: () -> T) {
        val e = edit.text
        val a = minOf(edit.selectionStart, edit.selectionEnd); val b = maxOf(edit.selectionStart, edit.selectionEnd)
        if (a < 0 || b <= a) { Toast.makeText(this, "글자를 먼저 골라주세요", Toast.LENGTH_SHORT).show(); return }
        val cls = make()::class.java
        val old = e.getSpans(a, b, cls).filter { e.getSpanStart(it) < b && e.getSpanEnd(it) > a }
        val covered = old.any { e.getSpanStart(it) <= a && e.getSpanEnd(it) >= b }
        // 겹치는 건 잘라내고 바깥 부분만 남김
        old.forEach {
            val s0 = e.getSpanStart(it); val e0 = e.getSpanEnd(it)
            e.removeSpan(it)
            if (s0 < a) e.setSpan(make(), s0, a, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e0 > b) e.setSpan(make(), b, e0, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!covered) e.setSpan(make(), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun selRange(): Pair<Int, Int>? {
        val a = minOf(edit.selectionStart, edit.selectionEnd); val b = maxOf(edit.selectionStart, edit.selectionEnd)
        if (a < 0 || b <= a) { Toast.makeText(this, "글자를 먼저 골라주세요", Toast.LENGTH_SHORT).show(); return null }
        return a to b
    }

    /** 고른 부분의 같은 종류 span 을 잘라내기 */
    private fun cutSpans(a: Int, b: Int, match: (Any) -> Boolean, remake: (Any) -> Any) {
        val e = edit.text
        e.getSpans(a, b, Any::class.java).filter(match).filter { e.getSpanStart(it) < b && e.getSpanEnd(it) > a }.forEach {
            val s0 = e.getSpanStart(it); val e0 = e.getSpanEnd(it)
            e.removeSpan(it)
            if (s0 < a) e.setSpan(remake(it), s0, a, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e0 > b) e.setSpan(remake(it), b, e0, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun copyOf(sp: Any): Any = when (sp) {
        is UFore -> UFore(sp.hex); is UBack -> UBack(sp.hex); is UHigh -> UHigh(); is UBold -> UBold()
        is UItalic -> UItalic(); is UUnder -> UUnder(); is UStrike -> UStrike(); else -> sp
    }

    private val TEXT_COLORS = listOf("#E5484D", "#F97316", "#D4A017", "#16A34A", "#2563EB", "#7C3AED", "#DB2777", "#6B7280")
    private val HIGH_COLORS = listOf("#FFE066", "#FFD6A5", "#CAFFBF", "#A0E7FF", "#E0C3FC", "#FFC6D9")

    /** 글자색(false) / 배경색(true) 고르기 */
    private fun pickColor(back: Boolean) {
        val (a, b) = selRange() ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        box.addView(row)
        val dlg = AlertDialog.Builder(this).setTitle(if (back) "배경색" else "글자색").setView(box)
            .setNegativeButton("색 없애기") { _, _ -> applyColor(a, b, back, null) }.create()
        for (c in if (back) HIGH_COLORS else TEXT_COLORS) {
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(c)); setStroke(dp(1), Color.parseColor("#22000000")) }
                setOnClickListener { applyColor(a, b, back, c); dlg.dismiss() }
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
        }
        dlg.show()
    }

    private fun applyColor(a: Int, b: Int, back: Boolean, hex: String?) {
        cutSpans(a, b, { if (back) (it is UBack || it is UHigh) else it is UFore }, ::copyOf)
        if (hex != null) edit.text.setSpan(if (back) UBack(hex) else UFore(hex), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun clearSel() {
        val (a, b) = selRange() ?: return
        cutSpans(a, b, { it is UBold || it is UItalic || it is UUnder || it is UStrike || it is UHigh || it is UFore || it is UBack }, ::copyOf)
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
                .put("folded", false).put("top", false).put("fav", fav).put("fs", fs).put("fmt", collectFmt())
                .put("created", now).put("updated", now)))
            isNew = false
            Data.prefs(this).edit().putString("note_color", color).apply()
        } else {
            val fmt = collectFmt()
            val fmtChanged = fmt.toString() != origFmt
            if (t != original) ops.put(Notes.patch(id, "text", t))
            if (fmtChanged) ops.put(Notes.patch(id, "fmt", fmt))
            if (t != original || fmtChanged) ops.put(Notes.patch(id, "updated", now))
            if (color != originalColor) ops.put(Notes.patch(id, "color", color))
            if (fav != origFav) ops.put(Notes.patch(id, "fav", fav))
            if (fs != origFs) ops.put(Notes.patch(id, "fs", fs))
            origFmt = fmt.toString()
        }
        original = t; originalColor = color; origFav = fav; origFs = fs
        if (ops.length() > 0) Notes.send(this, ops)
    }
}
