package com.spoonwork.timetracker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** 앱 아이콘 눌렀을 때: 위젯 설정 화면 */
class MainActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int = Color.parseColor("#1F2937"), bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (primary) setColor(Color.parseColor("#10B981")) else {
                setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
        }
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#1F2937"))
        setOnClickListener { onClick() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        TrackerWidget.render(applicationContext)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Data.prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        val gap = { h: Int -> View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) } }

        root.addView(text("타임트래커 위젯  v${Updater.currentVersion(this)}", 22f, bold = true))
        root.addView(button("📝 스티커 메모 열기") {
            startActivity(Intent(this, NotesActivity::class.java))
        })

        // 업데이트 확인
        val upd = button("업데이트 확인 중…") {}
        root.addView(upd)
        fun checkUpdate() {
            upd.text = "업데이트 확인 중…"
            upd.setOnClickListener(null)
            Thread {
                val latest = Updater.latestVersion()
                val cur = Updater.currentVersion(this)
                if (latest > 0) p.edit().putInt("latest", latest).putLong("latest_at", System.currentTimeMillis()).apply()
                runOnUiThread {
                    if (latest > cur) {
                        upd.text = "⬆ 새 버전 v$latest 업데이트 (지금 v$cur)"
                        upd.background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#3B82F6"))
                        }
                        upd.setTextColor(Color.WHITE)
                        upd.setOnClickListener {
                            // 크롬으로 새 설치 파일 받기 → 받은 파일 눌러서 설치
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK_URL)))
                        }
                    } else {
                        upd.text = if (latest > 0) "최신 버전이에요 ✓ (v$cur) · 눌러서 다시 확인" else "업데이트 확인 실패 · 눌러서 다시 확인"
                        upd.setOnClickListener { checkUpdate() }
                    }
                }
            }.start()
        }
        checkUpdate()
        root.addView(text(
            "홈 화면을 길게 누르고 → 위젯 → '타임트래커'를 끌어다 놓으세요.\n" +
                "위젯의 ＋수면 / ＋기록 / ＋일정 / ＋할 일 을 누르면 작은 입력창이 떠요.\n" +
                "할 일을 누르면 체크, 일정을 누르면 고칠 수 있어요.",
            14f, Color.parseColor("#6B7280")
        ))
        root.addView(gap(16))

        // 메모 위젯 모양
        root.addView(text("메모 위젯 모양", 15f, bold = true))
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableMapOf<String, TextView>()
        fun paintChips() {
            val cur = NoteWidget.style(this)
            for ((k, v) in chips) {
                val on = k == cur
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    if (on) setColor(Color.parseColor("#1F2937")) else { setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor("#E5E7EB")) }
                }
                v.setTextColor(if (on) Color.WHITE else Color.parseColor("#374151"))
            }
        }
        for ((k, name) in NoteWidget.STYLES) {
            val t = TextView(this).apply {
                text = name; textSize = 13f; gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    p.edit().putString("note_style", k).apply()
                    paintChips(); NoteWidget.render(applicationContext)
                }
            }
            chips[k] = t
            styleRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        paintChips()
        root.addView(styleRow)
        root.addView(text("흰색 메모는 색 표시 없이 나와요", 12f, Color.parseColor("#9CA3AF")))
        root.addView(gap(16))

        // 비밀 키 (시트 연결 잠금)
        root.addView(text("비밀 키", 15f, bold = true))
        root.addView(android.widget.EditText(this).apply {
            hint = "웹앱·PC와 같은 키를 넣어주세요"
            setSingleLine(true)
            setText(p.getString("auth", ""))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val k = e?.toString()?.trim() ?: ""
                    p.edit().putString("auth", k).apply(); Data.authKey = k
                }
            })
        })
        root.addView(gap(16))

        // 배경 진하기
        val alphaLabel = text("배경 진하기  ${Data.bgAlpha(this)}%", 15f, bold = true)
        root.addView(alphaLabel)
        root.addView(SeekBar(this).apply {
            max = 100
            progress = Data.bgAlpha(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    alphaLabel.text = "배경 진하기  $v%"
                    p.edit().putInt("bg_alpha", v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { TrackerWidget.render(applicationContext) }
            })
        })
        root.addView(text("0%로 하면 배경 없이 글자만 보여요", 12f, Color.parseColor("#9CA3AF")))
        root.addView(gap(8))

        root.addView(Switch(this).apply {
            text = "어두운 배경 (밝은 글자)"
            textSize = 15f
            isChecked = Data.dark(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("dark", on).apply()
                TrackerWidget.render(applicationContext)
            }
        })
        root.addView(gap(8))

        // ---- 꾸미기: 색 고르기 ----
        val palette = listOf("#A8925A", "#1F2937", "#FFFFFF", "#6B7280", "#E85A5A", "#F59E0B",
            "#10B981", "#3B82F6", "#8B5CF6", "#EC4899")
        fun swatches(title: String, key: String, def: Int?, withAuto: Boolean) {
            root.addView(text(title, 15f, bold = true))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val cur = p.getInt(key, def ?: 0)
            val views = mutableListOf<Pair<View, Int>>()
            val all = (if (withAuto) listOf(0) else emptyList()) + palette.map { Color.parseColor(it) }
            fun paint() = views.forEach { (v, col) ->
                val on = p.getInt(key, def ?: 0) == col
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (col == 0) Color.parseColor("#F3F4F6") else col)
                    setStroke(dp(if (on) 3 else 1), if (on) Color.parseColor("#10B981") else Color.parseColor("#D1D5DB"))
                }
            }
            all.forEach { col ->
                val v = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 10f
                    text = if (col == 0) "자동" else ""
                    setOnClickListener {
                        p.edit().putInt(key, col).apply()
                        paint()
                        TrackerWidget.render(applicationContext)
                    }
                }
                views.add(v to col)
                row.addView(v, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
            }
            // 컬러 써클에서 직접 고르기
            val custom = TextView(this).apply {
                gravity = Gravity.CENTER
                text = "🎨"
                textSize = 15f
            }
            fun paintCustom() {
                val c0 = p.getInt(key, def ?: 0)
                val isCustom = c0 != 0 && all.none { it == c0 }
                custom.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isCustom) c0 else Color.WHITE)
                    setStroke(dp(if (isCustom) 3 else 1), if (isCustom) Color.parseColor("#10B981") else Color.parseColor("#D1D5DB"))
                }
            }
            custom.setOnClickListener {
                val start = p.getInt(key, def ?: 0).takeIf { it != 0 } ?: Color.parseColor("#A8925A")
                ColorPicker.show(this, start, title) { col ->
                    p.edit().putInt(key, col).apply()
                    paint(); paintCustom()
                    TrackerWidget.render(applicationContext)
                }
            }
            views.forEach { (v, _) -> v.setOnClickListener(null) }
            views.forEach { (v, col) -> v.setOnClickListener {
                p.edit().putInt(key, col).apply(); paint(); paintCustom()
                TrackerWidget.render(applicationContext)
            } }
            // 컬러 써클 버튼은 맨 앞에 (옆으로 밀지 않아도 보이게)
            row.addView(custom, 0, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
            paint(); paintCustom()
            root.addView(android.widget.HorizontalScrollView(this).apply { addView(row) })
            root.addView(gap(8))
        }
        fun slider(title: String, key: String, def: Int, min: Int, max: Int, unit: String) {
            val lab = text("$title  ${p.getInt(key, def)}$unit", 15f, bold = true)
            root.addView(lab)
            root.addView(SeekBar(this).apply {
                this.max = max - min
                progress = p.getInt(key, def) - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                        lab.text = "$title  ${v + min}$unit"
                        p.edit().putInt(key, v + min).apply()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { TrackerWidget.render(applicationContext) }
                })
            })
            root.addView(gap(6))
        }
        root.addView(text("꾸미기", 18f, bold = true))
        swatches("시계·날짜 색", "clock_color", Color.parseColor("#A8925A"), false)
        slider("시계 크기", "clock_sp", 66, 36, 96, "")
        swatches("글자색 (달력·목록)", "text_color", 0, true)
        slider("글자 크기", "font_pct", 100, 80, 130, "%")
        root.addView(gap(8))

        // 글자색
        root.addView(text("글자색", 15f, bold = true))
        val group = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val cur = p.getString("text", "auto")
        listOf("auto" to "자동", "dark" to "검정", "light" to "흰색").forEachIndexed { i, (key, label) ->
            group.addView(android.widget.RadioButton(this).apply {
                id = 900 + i
                text = label
                textSize = 15f
                isChecked = key == cur
                setOnClickListener {
                    p.edit().putString("text", key).apply()
                    TrackerWidget.render(applicationContext)
                }
            })
        }
        root.addView(group)
        root.addView(text("배경을 연하게 하고 배경화면이 어두우면 '흰색'을 고르세요", 12f, Color.parseColor("#9CA3AF")))
        root.addView(gap(8))

        root.addView(Switch(this).apply {
            text = "먼슬리 위젯에 큰 시계"
            textSize = 15f
            isChecked = p.getBoolean("clock", true)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("clock", on).apply()
                TrackerWidget.render(applicationContext)
            }
        })
        root.addView(Switch(this).apply {
            text = "달력 날짜칸에 작업 스프레드(슾) 할 일 보기"
            textSize = 15f
            isChecked = p.getBoolean("cal_wt", true)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("cal_wt", on).apply()
                TrackerWidget.render(applicationContext)
            }
        })
        root.addView(Switch(this).apply {
            text = "구글 캘린더 일정 같이 보기"
            textSize = 15f
            isChecked = Cal.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("gcal", on).apply()
                if (on && !Cal.permitted(this@MainActivity)) {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 7)
                }
                TrackerWidget.render(applicationContext)
            }
        })
        root.addView(text("위젯 위쪽 날짜를 누르면 데일리 → 위클리 → 먼슬리로 바뀌어요", 12f, Color.parseColor("#9CA3AF")))
        root.addView(gap(16))

        root.addView(button("위젯 새로고침", primary = true) { TrackerWidget.refresh(applicationContext) })
        root.addView(gap(8))
        root.addView(button("빠른 입력 열기") {
            startActivity(Intent(this, QuickActivity::class.java).putExtra("mode", "block"))
        })
        root.addView(gap(8))
        root.addView(button("웹 트래커 열기") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://spoonwork.github.io/timetracker/")))
        })
        root.addView(gap(24))

        // 연결 주소 (보통 안 건드려도 됨)
        root.addView(text("시트 연결 주소 (Apps Script)", 13f, Color.parseColor("#6B7280"), bold = true))
        val urlEdit = EditText(this).apply {
            setText(Data.url(this@MainActivity))
            textSize = 12f
            setSingleLine(false)
        }
        root.addView(urlEdit)
        root.addView(button("주소 저장") {
            p.edit().putString("url", urlEdit.text.toString().trim()).apply()
            TrackerWidget.refresh(applicationContext)
        })
        val crash = p.getString("crash", null)
        if (crash != null) {
            root.addView(text("멈춤 기록 (캡처해서 보내주세요)", 12f, Color.parseColor("#E85A5A"), bold = true))
            root.addView(text(crash, 10f, Color.parseColor("#6B7280")).apply { setTextIsSelectable(true) })
            root.addView(button("멈춤 기록 지우기") { p.edit().remove("crash").apply(); recreate() })
        }
        val err = p.getString("error", null)
        if (err != null) root.addView(text("마지막 오류: $err", 12f, Color.parseColor("#E85A5A")))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
    }
}
