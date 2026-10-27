package com.spoonwork.timetracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** PC 스티커에 넣은 이미지·PDF: 시트에서 받아 폰에 저장해 두고 보여줌 */
object NoteFiles {
    private fun dir(c: Context) = File(c.filesDir, "notefiles").apply { mkdirs() }

    fun fid(att: JSONObject?): String =
        if (att != null && att.optBoolean("up")) att.optString("fid") else ""

    fun isPdf(att: JSONObject?) = att?.optString("kind") == "pdf"

    fun local(c: Context, att: JSONObject): File =
        File(dir(c), fid(att) + if (isPdf(att)) ".pdf" else ".jpg")

    /** 없으면 받아오기 (네트워크라서 백그라운드에서만 부를 것) */
    fun ensure(c: Context, att: JSONObject?): File? {
        if (att == null || fid(att).isEmpty()) return null
        val f = local(c, att)
        if (f.exists() && f.length() > 0) return f
        return try {
            val j = JSONObject(Data.httpGet(Data.url(c) + "?mode=file&id=" + URLEncoder.encode(fid(att), "UTF-8")))
            if (!j.optBoolean("ok")) return null
            val bytes = Base64.decode(j.optString("data"), Base64.DEFAULT)
            val tmp = File(f.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            f
        } catch (e: Exception) { null }
    }

    fun pageCount(f: File): Int = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (e: Exception) { 0 }

    /** 그림 한 장 (PDF면 page 쪽). maxW 픽셀 너비에 맞춤 */
    fun bitmap(c: Context, att: JSONObject?, maxW: Int, page: Int = att?.optInt("page", 0) ?: 0): Bitmap? {
        val f = ensure(c, att) ?: return null
        return try {
            if (isPdf(att)) {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        val pg = r.openPage(page.coerceIn(0, r.pageCount - 1))
                        val k = maxW.toFloat() / pg.width
                        val bmp = Bitmap.createBitmap(maxW, (pg.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pg.close()
                        bmp
                    }
                }
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= maxW) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
            }
        } catch (e: Exception) { null }
    }

    /** 위젯용 작은 정사각 썸네일 (모서리 둥글게) */
    fun thumb(c: Context, att: JSONObject?, px: Int): Bitmap? {
        val src = bitmap(c, att, px * 2, 0) ?: return null
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        cv.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), px * 0.18f, px * 0.18f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val sx = (src.width - side) / 2
        val sy = if (isPdf(att)) 0 else (src.height - side) / 2     // PDF는 위쪽부터
        cv.drawBitmap(src, android.graphics.Rect(sx, sy, sx + side, sy + side),
            android.graphics.Rect(0, 0, px, px), p)
        return out
    }
}
