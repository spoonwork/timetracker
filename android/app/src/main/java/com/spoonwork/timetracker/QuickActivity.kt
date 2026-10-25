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
    private var planHour = 10      // 5~28 (기록표 기준 시각)
    private var pm = 0             // 일정 시작 분
    private var peH = 11           // 일정 끝 (0~23시)
    private var peM = 0
    private var pendingCal: (() -> Unit)? = null
    private var editHour: Int? = null       // 고치는 중인 기존 일정의 시각
    private var app: JSONObject? = null
    private var touched = false

    private lateinit var tabs: Map<String, TextView>
    private lateinit var dateBtn: TextView
    private lateinit var paneBlock: LinearLayout
    private lateinit var catViews: Map<String, TextView>
    private lateinit var startBtn: TextView
    private lateinit var endBtn: TextView
    private lateinit var panePlan: LinearLayout
    private lateinit var planStartBtn: TextView
    private lateinit var planEndBtn: TextView
    private lateinit var gcalCheck: android.widget.CheckBox
    private lateinit var shareCheck: android.widget.CheckBox      // 일정: 작업 스프레드에 내용 공개
    private lateinit var wtCheck: android.widget.CheckBox         // 할 일: 작업 스프레드에도
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
                if (mode == "plan") fillPlanText()
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
        planStartBtn = pill("") {
            TimePickerDialog(this, { _, h, m ->
                val old = planHour
                planHour = if (h < Data.DAY_START) h + 24 else h
                pm = m
                // 끝 시간은 시작 + 1시간으로 맞춤
                peH = (h + 1) % 24; peM = m
                if (old != planHour && editHour == null) fillPlanText()
                refreshUi()
            }, planHour % 24, pm, true).show()
        }
        planEndBtn = pill("") {
            TimePickerDialog(this, { _, h, m -> peH = h; peM = m; refreshUi() }, peH, peM, true).show()
        }
        val ptimes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(planStartBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("  ~  ", 15f, textMain))
            addView(planEndBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panePlan.addView(row("시간", ptimes))
        planEdit = EditText(this).apply {
            hint = "비우고 저장하면 지워져요"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> save(); true }
        }
        panePlan.addView(row("내용", planEdit))
        gcalCheck = android.widget.CheckBox(this).apply {
            text = "구글 캘린더에도 추가"
            textSize = 14f
            isChecked = true
            setTextColor(textMain)
        }
        panePlan.addView(gcalCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(48) })
        shareCheck = android.widget.CheckBox(this).apply {
            text = "작업 스프레드에 내용 공개 (끄면 '📅 스케쥴'로만)"
            textSize = 13f
            isChecked = false
            setTextColor(textMain)
        }
        panePlan.addView(shareCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(48) })
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
        wtCheck = android.widget.CheckBox(this).apply {
            text = "작업 스프레드에도 (직원 공유)"
            textSize = 13f
            isChecked = false
            setTextColor(textMain)
        }
        paneTodo.addView(wtCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(48) })
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
                val dx = intent.getStringExtra("day")?.takeIf { it.isNotEmpty() }
                day = if (dx != null) Data.shift(dx, -1) else Data.trackerDay(-1)   // 그 전날 밤
                sh = p.getInt("sleep_sh", 1); sm = p.getInt("sleep_sm", 0)
                eh = p.getInt("sleep_eh", 8); em = p.getInt("sleep_em", 0)
            }
            "block" -> {
                cat = p.getString("last_cat", "작업") ?: "작업"
                if (cat == "수면") cat = "작업"
                day = intent.getStringExtra("day")?.takeIf { it.isNotEmpty() } ?: Data.trackerDay()
                eh = now.get(Calendar.HOUR_OF_DAY); em = now.get(Calendar.MINUTE) / 10 * 10
                val st = (now.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, -1) }
                sh = st.get(Calendar.HOUR_OF_DAY); sm = st.get(Calendar.MINUTE) / 10 * 10
            }
            "plan" -> {
                day = intent.getStringExtra("day")?.takeIf { it.isNotEmpty() } ?: Data.trackerDay()
                // 다른 날짜를 눌러 열었으면 10시로, 오늘이면 지금 시각으로
                val h = if (hour >= 0) hour else if (day != Data.trackerDay()) 10 else now.get(Calendar.HOUR_OF_DAY)
                planHour = if (h < Data.DAY_START) h + 24 else h
                pm = 0; peH = (planHour + 1) % 24; peM = 0
                fillPlanText()
            }
            "todo" -> {
                day = intent.getStringExtra("day")?.takeIf { it.isNotEmpty() } ?: Data.trackerDay()
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
        planStartBtn.text = String.format(Locale.US, "%02d:%02d", planHour % 24, pm)
        planEndBtn.text = String.format(Locale.US, "%02d:%02d", peH, peM)
        renderList()
    }

    private fun fillPlanText() {
        val plans = app?.optJSONObject("hourPlans")?.optJSONObject(day)
        val existing = plans?.optString(planHour.toString(), "") ?: ""
        planEdit.setText(existing)
        planEdit.setSelection(planEdit.text.length)
        editHour = if (existing.isBlank()) null else planHour
        val wl = if (existing.isBlank()) null else Data.prefs(this).getString("wlnk_${day}_$planHour", null)
        shareCheck.isChecked = wl != null && wl != "📅 스케쥴"
        val link = if (existing.isBlank()) null else Cal.link(applicationContext, day, planHour)
        if (link != null) {
            // 구글 캘린더에 넣었던 시간 그대로 불러오기
            val b = Calendar.getInstance().apply { timeInMillis = link.begin }
            val e = Calendar.getInstance().apply { timeInMillis = link.end }
            pm = b.get(Calendar.MINUTE); peH = e.get(Calendar.HOUR_OF_DAY); peM = e.get(Calendar.MINUTE)
            gcalCheck.text = "구글 캘린더와 연결됨 (끄면 캘린더에서 지움)"
            gcalCheck.isChecked = true
        } else {
            gcalCheck.text = "구글 캘린더에도 추가"
            gcalCheck.isChecked = existing.isBlank()
        }
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
                        commit("지움", JSONArray().put(Data.op("block_del", day).put("s", s).put("e", e)))
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
                        pm = 0; peH = (h + 1) % 24; peM = 0
                        fillPlanText()
                        refreshUi()
                    }) { deletePlan(h) })
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
                    val done = t.optBoolean("done")
                    listBox.addView(listRow(lead, text, {
                        commit(if (done) "체크 해제" else "체크",
                            JSONArray().put(Data.op("todo_set", day).put("text", text).put("done", !done)))
                    }) {
                        commit("지움", JSONArray().put(Data.op("todo_del", day).put("text", text)))
                    })
                }
            }
        }
    }

    // ---------------- 불러오기 / 저장 ----------------
    private fun load() {
        // 위젯에 있는 오늘 데이터로 먼저 보여주고, 최신 데이터는 뒤에서 받아옴
        if (app == null) {
            Data.appFromCache(applicationContext)?.let {
                app = it
                if (mode == "plan") fillPlanText()
                refreshUi()
            }
        }
        status.text = if (app == null) "불러오는 중…" else ""
        Thread {
            try {
                val a = Data.fetchAppData(applicationContext)
                runOnUiThread {
                    if (!touched) {          // 그 사이 고친 게 있으면 지금 화면 유지
                        app = a
                        status.text = ""
                        if (mode == "plan" && planEdit.text.isEmpty()) fillPlanText()
                        refreshUi()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "불러오기 실패: ${e.message}" }
            }
        }.start()
    }

    /** 화면·위젯에 바로 반영하고, 시트 저장은 뒤에서 (한 번 왕복) */
    private fun commit(done: String, ops: JSONArray) {
        touched = true
        val ctx = applicationContext
        app?.let { Data.applyOps(it, ops) }
        Data.applyOpsToCache(ctx, ops)
        TrackerWidget.render(ctx)
        status.text = "$done ✓"
        refreshUi()
        Thread {
            try {
                Data.sendOps(ctx, ops)
                TrackerWidget.refresh(ctx)
            } catch (e: Exception) {
                runOnUiThread { status.text = "시트 저장 실패: ${e.message}" }
                TrackerWidget.refresh(ctx)
            }
        }.start()
    }

    /** 캘린더 권한이 있으면 바로, 없으면 물어본 뒤 실행 */
    private fun withCal(action: () -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingCal = action
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_CALENDAR, android.Manifest.permission.READ_CALENDAR), 11)
        } else Thread { action() }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val act = pendingCal ?: return
        pendingCal = null
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Thread { act() }.start()
        } else status.text = "캘린더 권한이 없어서 구글 캘린더엔 반영 못 했어요"
    }

    private fun calDone(msg: String) {
        runOnUiThread { status.text = msg }
        TrackerWidget.render(applicationContext)
    }

    /** 일정 지우기 (구글 캘린더에 연결돼 있으면 거기서도) */
    private fun deletePlan(h: Int) {
        val d = day
        val delOps = JSONArray().put(Data.op("plan_set", d).put("h", h).put("text", ""))
        val wl = Data.prefs(this).getString("wlnk_${d}_$h", null)
        if (wl != null) {
            delOps.put(Data.op("wt_del", d).put("text", wl))
            Data.prefs(this).edit().remove("wlnk_${d}_$h").apply()
        }
        commit("일정 지움", delOps)
        if (editHour == h) { editHour = null; planEdit.setText("") }
        val link = Cal.link(applicationContext, d, h) ?: return
        withCal {
            Cal.delete(applicationContext, link.id)
            Cal.setLink(applicationContext, d, h, null)
            calDone("일정 지움 (구글 캘린더에서도) ✓")
        }
    }

    private fun save() {
        when (mode) {
            "sleep", "block" -> {
                val p = Data.prefs(this).edit()
                if (mode == "sleep") {
                    p.putInt("sleep_sh", sh).putInt("sleep_sm", sm).putInt("sleep_eh", eh).putInt("sleep_em", em)
                } else p.putString("last_cat", cat)
                p.apply()
                val msg = String.format(Locale.US, "%s %02d:%02d~%02d:%02d 저장", cat, sh, sm, eh, em)
                commit(msg, Data.paintOps(day, sh, sm, eh, em, cat))
            }
            "plan" -> {
                val text = planEdit.text.toString().trim()
                val d = day
                val newH = planHour
                val oldH = editHour
                if (text.isEmpty()) {
                    deletePlan(oldH ?: newH)
                    return
                }
                val ops = JSONArray()
                if (oldH != null && oldH != newH) ops.put(Data.op("plan_set", d).put("h", oldH).put("text", ""))
                ops.put(Data.op("plan_set", d).put("h", newH).put("text", text))
                val begin = Data.toCal(d).timeInMillis + newH * 3600_000L + pm * 60_000L
                var end = Data.toCal(d).timeInMillis +
                    (if (peH < Data.DAY_START) peH + 24 else peH) * 3600_000L + peM * 60_000L
                if (end <= begin) end = begin + 3600_000L
                val link = oldH?.let { Cal.link(applicationContext, d, it) }
                val want = gcalCheck.isChecked
                // 작업 스프레드 '슾' 칸: 공개면 내용, 아니면 '📅 스케쥴'
                val pr = Data.prefs(this)
                val sheetText = "📅 " + (if (shareCheck.isChecked)
                    String.format(Locale.US, "%02d:%02d ", newH % 24, pm) + text else "스케쥴")
                val oldSheet = oldH?.let { pr.getString("wlnk_${d}_$it", null) }
                if (oldSheet == null) ops.put(Data.op("wt_add", d).put("text", sheetText))
                else if (oldSheet != sheetText) ops.put(Data.op("wt_edit", d).put("old", oldSheet).put("text", sheetText))
                val ed = pr.edit()
                if (oldH != null) ed.remove("wlnk_${d}_$oldH")
                ed.putString("wlnk_${d}_$newH", sheetText).apply()
                commit("일정 저장", ops)
                editHour = newH
                val ctx = applicationContext
                when {
                    link != null && want -> withCal {
                        Cal.update(ctx, link.id, text, begin, end)
                        if (oldH != newH) Cal.setLink(ctx, d, oldH!!, null)
                        Cal.setLink(ctx, d, newH, Cal.Link(link.id, begin, end))
                        calDone("일정 저장 + 구글 캘린더 수정 ✓")
                    }
                    link != null && !want -> withCal {
                        Cal.delete(ctx, link.id)
                        Cal.setLink(ctx, d, oldH!!, null)
                        calDone("일정 저장 (구글 캘린더에선 지움) ✓")
                    }
                    link == null && want -> withCal {
                        val id = Cal.insert(ctx, text, begin, end)
                        if (id != null) {
                            Cal.setLink(ctx, d, newH, Cal.Link(id, begin, end))
                            calDone("일정 저장 + 구글 캘린더 추가 ✓")
                        } else calDone("구글 캘린더 추가 실패 (캘린더 계정 확인)")
                    }
                }
            }
            "todo" -> {
                val text = todoEdit.text.toString().trim()
                if (text.isEmpty()) return
                todoEdit.setText("")
                if (wtCheck.isChecked) commit("작업 스프레드에 추가", JSONArray().put(Data.op("wt_add", day).put("text", text)))
                else commit("할 일 추가", JSONArray().put(Data.op("todo_add", day).put("text", text)))
            }
        }
    }
}
