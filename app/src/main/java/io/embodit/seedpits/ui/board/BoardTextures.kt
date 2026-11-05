package io.embodit.seedpits.ui.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.embodit.seedpits.R

/**
 * Canvas-painted textures for everything flat in the volume: highlight rings / dots / cross (unlit, transparent),
 * the digit tiles (ink on light wood, Noto Sans bold) and the seat plate. Text reads upright on a plane laid
 * flat with pitch −90° (texture top = far side); `tex F` on the debug channel paints the D10 test glyph.
 */
class BoardTextures(context: Context) {
    private val typeface: Typeface = ResourcesCompat.getFont(context, R.font.noto_sans_bold) ?: Typeface.DEFAULT_BOLD

    fun ring(color: Int, size: Int = 256, stroke: Float = 0.10f, soft: Boolean = false, dashed: Boolean = false, alpha: Int = 255): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha; style = Paint.Style.STROKE; strokeWidth = size * stroke }
        if (dashed) p.pathEffect = DashPathEffect(floatArrayOf(size * 0.10f, size * 0.07f), 0f)
        val r = size * (0.5f - stroke / 2 - 0.01f)
        if (soft) {
            // A glow: a radial falloff around the ring radius.
            val g = Paint(Paint.ANTI_ALIAS_FLAG)
            g.shader = RadialGradient(size / 2f, size / 2f, size / 2f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(color, alpha), Color.TRANSPARENT),
                floatArrayOf(0f, 0.62f, 0.84f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(size / 2f, size / 2f, size / 2f, g)
        } else {
            c.drawCircle(size / 2f, size / 2f, r, p)
        }
        return bmp
    }

    fun dot(color: Int, size: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(size / 2f, size / 2f, size * 0.42f, p)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; alpha = 200; style = Paint.Style.STROKE; strokeWidth = size * 0.08f }
        c.drawCircle(size / 2f, size / 2f, size * 0.42f, rim)
        return bmp
    }

    fun cross(color: Int, size: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = size * 0.14f; strokeCap = Paint.Cap.ROUND }
        val a = size * 0.2f; val b = size * 0.8f
        c.drawLine(a, a, b, b, p); c.drawLine(b, a, a, b, p)
        return bmp
    }

    /** A digit tile: ink on light wood with a thin darker rim. [text] is the number (or "F↑" for the D10 test). */
    fun digit(text: String, w: Int = 192, h: Int = 128, background: Int = TILE_BG, ink: Int = INK): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.18f, h * 0.18f, bg)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TILE_RIM; style = Paint.Style.STROKE; strokeWidth = h * 0.05f }
        c.drawRoundRect(RectF(h * 0.03f, h * 0.03f, w - h * 0.03f, h - h * 0.03f), h * 0.16f, h * 0.16f, rim)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = this@BoardTextures.typeface; textAlign = Paint.Align.CENTER; textSize = h * 0.78f }
        val fm = p.fontMetrics
        c.drawText(text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }

    /** Seat plate: a light card with a colour dot and one line of text. */
    fun plate(text: String, dot: Int, w: Int = 640, h: Int = 224): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_BG }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.5f, h * 0.5f, bg)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TILE_RIM; style = Paint.Style.STROKE; strokeWidth = h * 0.04f }
        c.drawRoundRect(RectF(h * 0.02f, h * 0.02f, w - h * 0.02f, h - h * 0.02f), h * 0.48f, h * 0.48f, rim)
        val d = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot }
        c.drawCircle(h * 0.55f, h / 2f, h * 0.17f, d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = this@BoardTextures.typeface; textSize = h * 0.42f }
        var size = h * 0.42f
        val maxW = w - h * 1.0f - h * 0.35f
        while (p.measureText(text) > maxW && size > h * 0.2f) { size -= 2f; p.textSize = size }
        val fm = p.fontMetrics
        c.drawText(text, h * 0.95f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        val INK = Color.parseColor("#1B1F23")
        val TILE_BG = Color.parseColor("#E8C9A0")
        val TILE_RIM = Color.parseColor("#B08A5E")
        val PLATE_BG = Color.parseColor("#F5ECDF")
        val BLUE = Color.parseColor("#3F7FD9")
        val AMBER = Color.parseColor("#E0A040")
        val ORANGE = Color.parseColor("#D8842A")
        val RED = Color.parseColor("#C8423F")
        val OCHRE = Color.parseColor("#A0522D")
        val SLATE = Color.parseColor("#5B7A86")
        val LIT = Color.parseColor("#FFE6B8")
        val SHADE = Color.parseColor("#2A1A0C")
    }
}
