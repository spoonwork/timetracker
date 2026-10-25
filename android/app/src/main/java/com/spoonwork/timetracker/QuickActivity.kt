package com.spoonwork.timetracker

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** 위젯 위에 뜨는 작은 입력창: 수면 / 기록 / 일정 / 할 일 */
class QuickActivity : Activity() {

    private var mode = "block"            // sleep, block, plan, todo
    private var cat = "작업"
    private var day = Data.trackerDay()
    private var sh = 1
    private var sm = 0
    private var eh = 8
    private var em = 0
    private var planHour = 10
    private var app: JSONObject? = null
    private var busy = false

    private lateinit var tabs: Map<String, TextView>
    private lateinit var dateBtn: TextView
    private lateinit var paneBlock: LinearLayout
    private lateinit var catViews: Map<String, TextView>
    private lateinit var startBtn: TextView
    private lateinit var endBtn: TextView
    private lateinit var panePlan: LinearLayout
    private lateinit var hourSpinner: Spinner
    private lateinit var planEdit: EditText
    private lateinit var paneTodo: LinearLayout
    private lateinit var todoEdit: EditText
    private lateinit var status: TextView
    private lateinit var listTitle: TextView
    private lateinit var listBox: LinearLayout

    private val textMain = Color.parseColor("#1F2937")
    private val textMuted = Color.parseColor("#6B7280")
    private val green = Color.parseColor("#10B981")
    private val line = Color.parseColor("#E5E7EB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContentView(buildUi())
        setMode(intent.getStringExtra("mode") ?: "block", intent.getIntExtra("hour", -1))
        load()
    }

    // ---------------- 화면 만들기 ----------------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(text: String, size: Float = 13f, color: Int = textMuted): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(textMain)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = bg(Color.TRANSPARENT, 10, line)
        setOnClickListener { onClick() }
    }

    private fun row(labelText: String, content: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(label(labelText), LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        // 탭
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val t = linkedMapOf("sleep" to "수면", "block" to "기록", "plan" to "일정", "todo" to "할 일")
        tabs = t.mapValues { (key, name) ->
            pill(name) { setMode(key, -1) }.also {
                it.textSize = 14f
                tabRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(3); marginEnd = dp(3) })
            }
        }
        root.addView(tabRow)

        // 날짜
        dateBtn = pill("") {
            val c = Data.toCal(day)
            DatePickerDialog(this, { _, y, m, d ->
                day = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                refreshUi()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        root.addView(row("날짜", dateBtn).apply { setPadding(0, dp(12), 0, dp(6)) })

        // 기록 / 수면
        paneBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cv = linkedMapOf<String, TextView>()
        Data.CATS.chunked(4).forEach { group ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { c ->
                val v = TextView(this).apply {
                    text = c
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(8))
                    setTextColor(if (c == "작업") Color.WHITE else textMain)
                    setOnClickListener { cat = c; refreshUi() }
                }
                cv[c] = v
                r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(4 - group.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            chips.addView(r)
        }
        catViews = cv
        paneBlock.addView(row("종류", chips))
        startBtn = pill("") {
            TimePickerDialog(this, { _, h, m -> sh = h; sm = m; refreshUi() }, sh, sm, true).show()
        }
        endBtn = pill("") {
            TimePickerDialog(this, { _, h, m -> eh = h; em = m; refreshUi() }, eh, em, true).show()
        }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("  ~  ", 15f, textMain))
            addView(endBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        paneBlock.addView(row("시간", times))
        paneBlock.addView(label("새벽 5시 전 시각은 그 날짜의 밤으로 들어가요", 11f).apply {
            setPadding(dp(52), 0, 0, 0)
        })
        root.addView(paneBlock)

        // 일정
        panePlan = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hourSpinner = Spinner(this)
        val hours = (Data.DAY_START until Data.DAY_START + 24).map { String.format(Locale.US, "%02d시", it % 24) }
        hourSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hours)
        hourSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (planHour != pos + Data.DAY_START) {
                    planHour = pos + Data.DAY_START
                    fillPlanText()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        panePlan.addView(row("시각", hourSpinner))
        planEdit = EditText(this).apply {
            hint = "비우고 저장하면 지워져요"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> save(); true }
        }
        panePlan.addView(row("내용", planEdit))
        root.addView(panePlan)

        // 할 일
        paneTodo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        todoEdit = EditText(this).apply {
            hint = "할 일 적고 저장"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> save(); true }
        }
        paneTodo.addView(row("할 일", todoEdit))
        root.addView(paneTodo)

        // 버튼
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        btns.addView(pill("닫기") { finish() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        val saveBtn = pill("저장") { save() }.apply {
            background = bg(green, 10)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        btns.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)

        status = label("", 12f, green).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)

        listTitle = label("", 12f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(listTitle)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
    }

    // ---------------- 상태 ----------------
    private fun setMode(m: String, hour: Int) {
        mode = m
        val p = Data.prefs(this)
        val now = Calendar.getInstance()
        when (m) {
            "sleep" -> {
                cat = "수면"
                day = Data.trackerDay(-1)          // 어젯밤
                sh = p.getInt("sleep_sh", 1); sm = p.getInt("sleep_sm", 0)
                eh = p.getInt("sleep_eh", 8); em = p.getInt("sleep_em", 0)
            }
            "block" -> {
                cat = p.getString("last_cat", "작업") ?: "작업"
                if (cat == "수면") cat = "작업"
                day = Data.trackerDay()
                eh = now.get(Calendar.HOUR_OF_DAY); em = now.get(Calendar.MINUTE) / 10 * 10
                val st = (now.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, -1) }
                sh = st.get(Calendar.HOUR_OF_DAY); sm = st.get(Calendar.MINUTE) / 10 * 10
            }
            "plan" -> {
                day = Data.trackerDay()
                val h = if (hour >= 0) hour else now.get(Calendar.HOUR_OF_DAY)
                planHour = if (h < Data.DAY_START) h + 24 else h
                hourSpinner.setSelection(planHour - Data.DAY_START)
                fillPlanText()
            }
            "todo" -> {
                day = Data.trackerDay()
                todoEdit.requestFocus()
            }
        }
        refreshUi()
    }

    private fun refreshUi() {
        tabs.forEach { (k, v) ->
            val on = k == mode
            v.background = bg(if (on) textMain else Color.TRANSPARENT, 10, if (on) textMain else line)
            v.setTextColor(if (on) Color.WHITE else textMuted)
        }
        dateBtn.text = Data.prettyDay(day) + if (mode == "sleep") "  밤" else ""
        val blockLike = mode == "sleep" || mode == "block"
        paneBlock.visibility = if (blockLike) View.VISIBLE else View.GONE
        panePlan.visibility = if (mode == "plan") View.VISIBLE else View.GONE
        paneTodo.visibility = if (mode == "todo") View.VISIBLE else View.GONE
        catViews.forEach { (c, v) ->
            v.background = bg(Data.color(c), 16, if (c == cat) textMain else null).apply {
                if (c == cat) setStroke(dp(2), textMain)
            }
            v.setTypeface(null, if (c == cat) Typeface.BOLD else Typeface.NORMAL)
        }
        startBtn.text = String.format(Locale.US, "%02d:%02d", sh, sm)
        endBtn.text = String.format(Locale.US, "%02d:%02d", eh, em)
        if (mode == "plan") fillPlanText()
        renderList()
    }

    private fun fillPlanText() {
        val plans = app?.optJSONObject("hourPlans")?.optJSONObject(day)
        planEdit.setText(plans?.optString(planHour.toString(), "") ?: "")
        planEdit.setSelection(planEdit.text.length)
    }

    // ---------------- 목록 (그 날 칠한 칸 / 일정 / 할 일) ----------------
    private fun listRow(lead: View?, text: String, onClick: (() -> Unit)?, onDelete: (() -> Unit)?): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            if (lead != null) addView(lead)
            addView(label(text, 14f, textMain).apply { setPadding(dp(8), 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (onDelete != null) {
                addView(label("✕", 15f).apply {
                    setPadding(dp(12), dp(2), dp(4), dp(2))
                    setOnClickListener { onDelete() }
                })
            }
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun renderList() {
        listBox.removeAllViews()
        val a = app
        if (a == null) {
            listTitle.text = ""
            return
        }
        when (mode) {
            "sleep", "block" -> {
                listTitle.text = "${Data.prettyDay(day)} 칠한 칸"
                val arr = a.optJSONObject("manualBlocks")?.optJSONArray(day) ?: JSONArray()
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .sortedBy { it.optInt("startMin") }
                if (items.isEmpty()) listBox.addView(label("아직 없어요"))
                items.forEach { b ->
                    val s = b.optInt("startMin")
                    val e = b.optInt("endMin")
                    val c = b.optString("category")
                    val dot = View(this).apply {
                        background = bg(Data.color(c), 3)
                        layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
                    }
                    listBox.addView(listRow(dot, "${Data.gridText(s)} ~ ${Data.gridText(e)}   $c", null) {
                        mutate("지웠어요") { x ->
                            val mb = x.optJSONObject("manualBlocks") ?: JSONObject()
                            Data.removeBlock(mb, day, s, e)
                            x.put("manualBlocks", mb)
                            listOf("manualBlocks")
                        }
                    })
                }
            }
            "plan" -> {
                listTitle.text = "${Data.prettyDay(day)} 일정 (누르면 고치기)"
                val plans = a.optJSONObject("hourPlans")?.optJSONObject(day)
                val hs = mutableListOf<Int>()
                plans?.keys()?.forEach { k -> if (plans.optString(k).isNotBlank()) k.toIntOrNull()?.let { hs.add(it) } }
                if (hs.isEmpty()) listBox.addView(label("아직 없어요"))
                hs.sorted().forEach { h ->
                    val lead = label(String.format(Locale.US, "%02d시", h % 24), 13f)
                    listBox.addView(listRow(lead, plans!!.optString(h.toString()), {
                        planHour = h
                        hourSpinner.setSelection(h - Data.DAY_START)
                        fillPlanText()
                    }, null))
                }
            }
            "todo" -> {
                listTitle.text = "${Data.prettyDay(day)} 할 일"
                val arr = a.optJSONObject("todos")?.optJSONArray(day) ?: JSONArray()
                if (arr.length() == 0) listBox.addView(label("아직 없어요"))
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val text = t.optString("text")
                    val lead = label(if (t.optBoolean("done")) "☑" else "☐", 16f, textMain)
                    listBox.addView(listRow(lead, text, null) {
                        mutate("지웠어요") { x ->
                            val todos = x.optJSONObject("todos") ?: JSONObject()
                            val src = todos.optJSONArray(day) ?: JSONArray()
                            val out = JSONArray()
                            var removed = false
                            for (j in 0 until src.length()) {
                                val o = src.optJSONObject(j) ?: continue
                                if (!removed && o.optString("text") == text) { removed = true; continue }
                                out.put(o)
                            }
                            todos.put(day, out)
                            x.put("todos", todos)
                            listOf("todos")
                        }
                    })
                }
            }
        }
    }

    // ---------------- 불러오기 / 저장 ----------------
    private fun load() {
        status.text = "불러오는 중…"
        Thread {
            try {
                val a = Data.fetchAppData(applicationContext)
                runOnUiThread {
                    app = a
                    status.text = ""
                    refreshUi()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "불러오기 실패: ${e.message}" }
            }
        }.start()
    }

    /** 최신 데이터를 받아 고친 뒤 바뀐 항목만 저장 */
    private fun mutate(done: String, change: (JSONObject) -> List<String>) {
        if (busy) return
        busy = true
        status.text = "저장 중…"
        val ctx = applicationContext
        Thread {
            try {
                val fresh = Data.fetchAppData(ctx)
                val keys = change(fresh)
                keys.forEach { k -> Data.saveKey(ctx, k, fresh.opt(k) ?: JSONObject()) }
                runOnUiThread {
                    app = fresh
                    busy = false
                    status.text = "$done ✓"
                    refreshUi()
                }
                TrackerWidget.refresh(ctx)
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = "저장 실패: ${e.message}"
                }
            }
        }.start()
    }

    private fun save() {
        when (mode) {
            "sleep", "block" -> {
                val c = cat
                val d = day
                val (a, b, x, y) = listOf(sh, sm, eh, em)
                val p = Data.prefs(this).edit()
                if (mode == "sleep") {
                    p.putInt("sleep_sh", a).putInt("sleep_sm", b).putInt("sleep_eh", x).putInt("sleep_em", y)
                } else p.putString("last_cat", c)
                p.apply()
                val msg = String.format(Locale.US, "%s %02d:%02d~%02d:%02d 저장했어요", c, a, b, x, y)
                mutate(msg) { fresh ->
                    val mb = fresh.optJSONObject("manualBlocks") ?: JSONObject()
                    Data.paintTimes(mb, d, a, b, x, y, c)
                    fresh.put("manualBlocks", mb)
                    listOf("manualBlocks")
                }
            }
            "plan" -> {
                val d = day
                val h = planHour
                val text = planEdit.text.toString()
                mutate(if (text.isBlank()) "일정 지웠어요" else "일정 저장했어요") { fresh ->
                    val hp = fresh.optJSONObject("hourPlans") ?: JSONObject()
                    val dayObj = hp.optJSONObject(d) ?: JSONObject()
                    if (text.isBlank()) dayObj.remove(h.toString()) else dayObj.put(h.toString(), text)
                    hp.put(d, dayObj)
                    fresh.put("hourPlans", hp)
                    listOf("hourPlans")
                }
            }
            "todo" -> {
                val text = todoEdit.text.toString().trim()
                if (text.isEmpty()) return
                val d = day
                todoEdit.setText("")
                mutate("할 일 추가했어요") { fresh ->
                    val todos = fresh.optJSONObject("todos") ?: JSONObject()
                    val arr = todos.optJSONArray(d) ?: JSONArray()
                    arr.put(JSONObject().put("text", text).put("done", false))
                    todos.put(d, arr)
                    fresh.put("todos", todos)
                    listOf("todos")
                }
            }
        }
    }
}
