package com.spoonwork.timetracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject

/** 빠른 메모 위젯 (삼성노트·킵 느낌). 흰 카드 + 메모 색 표시 3가지 모양 */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(context)
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            try { Notes.fetch(ctx) } catch (_: Exception) {}
            try { render(ctx) } catch (_: Exception) {}
            pending.finish()
        }.start()
    }

    companion object {
        // 모양: d1 왼쪽 끝 바 · d2 안쪽 둥근 바 · e3 왼쪽 위 작은 삼각
        val STYLES = linkedMapOf("d1" to "왼쪽 끝 바", "d2" to "안쪽 둥근 바", "e3" to "왼쪽 위 삼각")

        fun style(c: Context): String = Data.prefs(c).getString("note_style", "d1") ?: "d1"

        fun render(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, NoteWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                val rv = RemoteViews(context.packageName, R.layout.note_widget)
                val svc = Intent(context, NoteWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
                rv.setRemoteAdapter(R.id.nlist, svc)
                rv.setEmptyView(R.id.nlist, R.id.nempty)
                val open = Intent(context, NoteEditActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                rv.setPendingIntentTemplate(R.id.nlist, PendingIntent.getActivity(
                    context, 40, open, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                val add = Intent(context, NoteEditActivity::class.java).putExtra("id", "")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                rv.setOnClickPendingIntent(R.id.nadd, PendingIntent.getActivity(
                    context, 41, add, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                mgr.updateAppWidget(id, rv)
            }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.nlist)
        }
    }
}

class NoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        private var rows: List<Pair<String, JSONObject>> = emptyList()
        private var style = "d1"

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun onDataSetChanged() {
            style = NoteWidget.style(ctx)
            val all = Notes.cache(ctx)
            rows = all.keys().asSequence().mapNotNull { k -> all.optJSONObject(k)?.let { k to it } }
                .sortedWith(compareByDescending<Pair<String, JSONObject>> { it.second.optBoolean("fav") }
                    .thenByDescending { it.second.optLong("updated", it.second.optLong("created", 0)) })
                .toList()
        }

        override fun getCount() = rows.size
        override fun getViewTypeCount() = 3
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val layout = when (style) { "d2" -> R.layout.note_row_d2; "e3" -> R.layout.note_row_e3; else -> R.layout.note_row_d1 }
            val rv = RemoteViews(ctx.packageName, layout)
            val (id, n) = rows.getOrNull(position) ?: return rv
            val text = n.optString("text")
            val fav = n.optBoolean("fav")
            val title = Notes.attMark(n) + Notes.title(text).ifEmpty { "(빈 메모)" }
            rv.setTextViewText(R.id.ntitle, if (fav) "★ $title" else title)
            val body = if (n.optBoolean("folded")) "" else Notes.bodyOf(text).lines().filter { it.isNotBlank() }.joinToString("\n")
            rv.setTextViewText(R.id.nbody, body)
            rv.setViewVisibility(R.id.nbody, if (body.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            // 흰색 메모는 색 표시 없음
            val key = n.optString("color").ifEmpty { "yellow" }
            val noColor = key == "white"
            if (style == "d1") {
                rv.setInt(R.id.naccent, "setColorFilter", if (noColor) Color.WHITE else Notes.head(key))
            } else {
                rv.setViewVisibility(R.id.naccent, if (noColor) android.view.View.GONE else android.view.View.VISIBLE)
                if (!noColor) rv.setInt(R.id.naccent, "setColorFilter", Notes.head(key))
            }
            rv.setOnClickFillInIntent(R.id.ncard, Intent().putExtra("id", id))
            return rv
        }
    }
}
