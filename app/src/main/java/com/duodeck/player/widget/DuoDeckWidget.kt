package com.duodeck.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.widget.RemoteViews
import com.duodeck.player.MainActivity
import com.duodeck.player.R
import com.duodeck.player.skin.Skin
import com.duodeck.player.skin.Slot

/** Home-screen mini player, drawn in the current skin's colours. */
class DuoDeckWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.refresh(context)
    }
}

object WidgetUpdater {
    private const val PREFS = "widget"

    /** Called by the app whenever the skin changes. */
    fun saveSkin(context: Context, skin: Skin) {
        val e = prefs(context).edit()
        listOf(
            Slot.FRAME, Slot.FRAME_LIGHT, Slot.FRAME_DARK, Slot.TITLE_BAR, Slot.DISPLAY_BG, Slot.DISPLAY_TEXT,
            Slot.DISPLAY_DIM, Slot.BUTTON, Slot.BUTTON_TEXT, Slot.ACCENT,
        ).forEach { e.putInt(it.name, skin.color(it).toInt()) }
        e.apply()
        refresh(context)
    }

    /** Called by the player whenever the song or play/pause changes. */
    fun saveNowPlaying(context: Context, title: String?, artist: String?, playing: Boolean, canResume: Boolean) {
        val p = prefs(context)
        if (p.getString("title", null) == title && p.getString("artist", null) == artist &&
            p.getBoolean("playing", false) == playing && p.getBoolean("canResume", false) == canResume) return
        p.edit().putString("title", title).putString("artist", artist)
            .putBoolean("playing", playing).putBoolean("canResume", canResume).apply()
        refresh(context)
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refresh(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, DuoDeckWidget::class.java))
        if (ids.isEmpty()) return
        val p = prefs(context)
        fun col(s: Slot, def: Long) = p.getInt(s.name, def.toInt())
        val frame = col(Slot.FRAME, 0xFF3A3F4C)
        val light = col(Slot.FRAME_LIGHT, 0xFF6A7184)
        val dark = col(Slot.FRAME_DARK, 0xFF15171D)
        val stripe = col(Slot.TITLE_BAR, 0xFFFF8A3D)
        val dispBg = col(Slot.DISPLAY_BG, 0xFF05080A)
        val dispText = col(Slot.DISPLAY_TEXT, 0xFF3CFF6A)
        val dispDim = col(Slot.DISPLAY_DIM, 0xFF1D7A35)
        val button = col(Slot.BUTTON, 0xFFC9CED8)
        val buttonText = col(Slot.BUTTON_TEXT, 0xFF222222)
        val accent = col(Slot.ACCENT, 0xFF3CFF6A)

        val title = p.getString("title", null)
        val artist = p.getString("artist", null)
        val playing = p.getBoolean("playing", false)
        val canResume = p.getBoolean("canResume", false)

        val rv = RemoteViews(context.packageName, R.layout.widget_player)
        rv.setImageViewBitmap(R.id.widget_bg, panelBitmap(frame, light, dark, stripe))
        rv.setImageViewBitmap(R.id.widget_display_bg, displayBitmap(dispBg, light, dark))
        rv.setTextViewText(R.id.widget_title, (title ?: "DUO-DECK").uppercase())
        rv.setTextColor(R.id.widget_title, dispText)
        rv.setTextViewText(R.id.widget_artist, (artist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: if (title == null) "TAP TO OPEN" else "").uppercase())
        rv.setTextColor(R.id.widget_artist, dispDim)
        rv.setImageViewBitmap(R.id.widget_prev, buttonBitmap(Icon.PREV, button, buttonText, light, dark, accent, false))
        rv.setImageViewBitmap(R.id.widget_play, buttonBitmap(if (playing) Icon.PAUSE else Icon.PLAY, button, buttonText, light, dark, accent, playing))
        rv.setImageViewBitmap(R.id.widget_next, buttonBitmap(Icon.NEXT, button, buttonText, light, dark, accent, false))

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        rv.setOnClickPendingIntent(R.id.widget_display, open)
        rv.setOnClickPendingIntent(R.id.widget_bg, open)
        rv.setOnClickPendingIntent(R.id.widget_prev, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1))
        rv.setOnClickPendingIntent(R.id.widget_next, mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT, 2))
        // Nothing has ever played: the play button opens the app instead.
        rv.setOnClickPendingIntent(R.id.widget_play, if (canResume || playing) mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 3) else open)
        mgr.updateAppWidget(ids, rv)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mediaKey(context: Context, code: Int, req: Int): PendingIntent {
        val i = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, androidx.media3.session.MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
        return PendingIntent.getBroadcast(context, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---------------- drawing ----------------

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private fun panelBitmap(frame: Int, light: Int, dark: Int, stripe: Int): Bitmap {
        val w = 720; val h = 300; val r = 26f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(2f, 2f, w - 2f, h - 2f)
        p.color = dark; c.drawRoundRect(rect, r, r, p)
        val inner = RectF(6f, 6f, w - 6f, h - 6f)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), mix(frame, light, 0.35f), mix(frame, dark, 0.25f), Shader.TileMode.CLAMP)
        c.drawRoundRect(inner, r - 4, r - 4, p)
        p.shader = null
        // bevel: light top-left edge, dark bottom-right edge
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f
        p.color = mix(light, 0xFFFFFFFF.toInt(), 0.2f); c.drawLine(r, 8f, w - r, 8f, p); c.drawLine(8f, r, 8f, h - r, p)
        p.color = dark; c.drawLine(r, h - 8f, w - r, h - 8f, p); c.drawLine(w - 8f, r, w - 8f, h - r, p)
        p.style = Paint.Style.FILL
        // title-bar stripes across the top
        p.color = stripe
        for (i in 0 until 3) c.drawRect(30f, 16f + i * 6f, w - 30f, 18.5f + i * 6f, p)
        return bmp
    }

    private fun displayBitmap(bg: Int, light: Int, dark: Int): Bitmap {
        val w = 640; val h = 150; val r = 12f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = dark; c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        p.color = light; c.drawRoundRect(RectF(3f, 3f, w.toFloat(), h.toFloat()), r, r, p)
        p.color = bg; c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), r - 2, r - 2, p)
        // faint scanlines
        p.color = 0x14FFFFFF
        var y = 6f
        while (y < h - 4) { c.drawRect(5f, y, w - 5f, y + 1f, p); y += 4f }
        return bmp
    }

    private enum class Icon { PREV, PLAY, PAUSE, NEXT }

    private fun buttonBitmap(icon: Icon, face: Int, ink: Int, light: Int, dark: Int, accent: Int, lit: Boolean): Bitmap {
        val w = 220; val h = 120; val r = 18f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = dark; c.drawRoundRect(RectF(2f, 4f, w - 2f, h - 1f), r, r, p)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), mix(face, 0xFFFFFFFF.toInt(), 0.25f), mix(face, dark, 0.2f), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 6f), r, r, p)
        p.shader = null
        p.color = 0x55FFFFFF; c.drawRoundRect(RectF(r, 6f, w - r, 12f), 3f, 3f, p)
        // icon
        p.color = ink
        val cx = w / 2f; val cy = (h - 4) / 2f; val s = 26f
        fun tri(left: Float, pointRight: Boolean) {
            val path = Path()
            if (pointRight) { path.moveTo(left, cy - s); path.lineTo(left + s * 1.5f, cy); path.lineTo(left, cy + s) }
            else { path.moveTo(left + s * 1.5f, cy - s); path.lineTo(left, cy); path.lineTo(left + s * 1.5f, cy + s) }
            path.close(); c.drawPath(path, p)
        }
        when (icon) {
            Icon.PLAY -> tri(cx - s * 0.6f, true)
            Icon.PAUSE -> { c.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.2f, cy + s, p); c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.8f, cy + s, p) }
            Icon.PREV -> { c.drawRect(cx - s * 1.6f, cy - s, cx - s * 1.2f, cy + s, p); tri(cx - s * 1.2f, false); tri(cx + s * 0.1f, false) }
            Icon.NEXT -> { tri(cx - s * 1.6f, true); tri(cx - s * 0.3f, true); c.drawRect(cx + s * 1.2f, cy - s, cx + s * 1.6f, cy + s, p) }
        }
        // little status light on the play button
        if (icon == Icon.PLAY || icon == Icon.PAUSE) {
            val lx = 26f; val ly = 26f
            if (lit) { p.color = (accent and 0x00FFFFFF) or 0x66000000; c.drawCircle(lx, ly, 13f, p) }
            p.color = if (lit) accent else mix(dark, 0xFF000000.toInt(), 0.2f); c.drawCircle(lx, ly, 7f, p)
            p.color = 0xAAFFFFFF.toInt(); c.drawCircle(lx - 2f, ly - 2f, 2.5f, p)
        }
        return bmp
    }
}
