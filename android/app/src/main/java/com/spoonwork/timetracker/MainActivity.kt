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

        // 업데이트 확인
        val upd = button("업데이트 확인 중…") {}
        root.addView(upd)
        Thread {
            val latest = Updater.latestVersion()
            val cur = Updater.currentVersion(this)
            p.edit().putInt("latest", latest).putLong("latest_at", System.currentTimeMillis()).apply()
            runOnUiThread {
                if (latest > cur) {
                    upd.text = "⬆ 새 버전 v$latest 업데이트"
                    upd.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#3B82F6"))
                    }
                    upd.setTextColor(Color.WHITE)
                    upd.setOnClickListener {
                        // 크롬으로 새 설치 파일 받기 → 받은 파일 눌러서 설치
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK_URL)))
                    }
                } else if (latest > 0) {
                    upd.text = "최신 버전이에요 ✓"
                } else {
                    upd.text = "업데이트 확인 실패"
                }
            }
        }.start()
        root.addView(text(
            "홈 화면을 길게 누르고 → 위젯 → '타임트래커'를 끌어다 놓으세요.\n" +
                "위젯의 ＋수면 / ＋기록 / ＋일정 / ＋할 일 을 누르면 작은 입력창이 떠요.\n" +
                "할 일을 누르면 체크, 일정을 누르면 고칠 수 있어요.",
            14f, Color.parseColor("#6B7280")
        ))
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
