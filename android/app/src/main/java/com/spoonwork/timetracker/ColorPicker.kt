package com.spoonwork.timetracker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** 컬러 써클: 원에서 색(색상·채도) 고르고, 아래 막대로 밝기 */
class ColorWheel(context: Context, var onChange: (Float, Float) -> Unit) : View(context) {
    var hue = 0f
    var sat = 1f
    var value = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE
    }
    private val markOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 10f; color = Color.parseColor("#55000000")
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 12f
        val hues = intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED)
        val sweep = SweepGradient(cx, cy, hues, null)
        val radial = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(sweep, radial, PorterDuff.Mode.SRC_OVER)
        c.drawCircle(cx, cy, r, paint)
        // 밝기
        dim.color = Color.argb(((1f - value) * 255).toInt(), 0, 0, 0)
        c.drawCircle(cx, cy, r, dim)
        // 지금 고른 점 (SweepGradient는 시계방향이라 각도를 반대로)
        val ang = Math.toRadians((360f - hue).toDouble())
        val px = cx + (cos(ang) * sat * r).toFloat()
        val py = cy + (sin(ang) * sat * r).toFloat()
        c.drawCircle(px, py, 16f, markOut)
        c.drawCircle(px, py, 16f, mark)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 12f
        val dx = e.x - cx
        val dy = e.y - cy
        var a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (a < 0) a += 360f
        hue = (360f - a) % 360f
        sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
        invalidate()
        onChange(hue, sat)
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}

object ColorPicker {
    fun show(ctx: Context, start: Int, title: String, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(start, hsv)
        val dp = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val preview = View(ctx)
        val hex = EditText(ctx).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        var cur = start
        var typing = false
        fun update(fromHex: Boolean = false) {
            cur = Color.HSVToColor(hsv)
            preview.background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(cur) }
            if (!fromHex) {
                typing = true
                hex.setText(String.format("#%06X", 0xFFFFFF and cur))
                typing = false
            }
        }
        val wheel = ColorWheel(ctx) { h, s -> hsv[0] = h; hsv[1] = s; update() }
        wheel.hue = hsv[0]; wheel.sat = hsv[1]; wheel.value = hsv[2]
        box.addView(wheel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val bright = SeekBar(ctx).apply {
            max = 100
            progress = (hsv[2] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    hsv[2] = v / 100f; wheel.value = hsv[2]; wheel.invalidate(); update()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(bright)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        row.addView(preview, LinearLayout.LayoutParams((56 * dp).toInt(), (36 * dp).toInt()))
        row.addView(hex, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (12 * dp).toInt()
        })
        box.addView(row)
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (typing) return
                val t = s?.toString()?.trim() ?: return
                try {
                    val col = Color.parseColor(if (t.startsWith("#")) t else "#$t")
                    Color.colorToHSV(col, hsv)
                    wheel.hue = hsv[0]; wheel.sat = hsv[1]; wheel.value = hsv[2]; wheel.invalidate()
                    bright.progress = (hsv[2] * 100).toInt()
                    update(fromHex = true)
                } catch (_: Exception) {}
            }
        })
        update()
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("적용") { _, _ -> onPick(cur or 0xFF000000.toInt()) }
            .setNegativeButton("취소", null)
            .show()
    }
}
