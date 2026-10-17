package com.padbridge.wear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.padbridge.wear.R
import com.padbridge.wear.input.InputHub
import com.padbridge.wear.protocol.Buttons
import com.padbridge.wear.protocol.PadState

/** Live schematic of one pad: shows every button, both sticks and both triggers. */
class PadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var state: PadState? = null
    /** Slot to show; -1 = whichever pad sent the last update. */
    var slot = -1

    private val idle = context.getColor(R.color.pad_idle)
    private val on = context.getColor(R.color.accent)
    private val body = context.getColor(R.color.surface)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()

    private val listener: (PadState) -> Unit = { s ->
        if (slot < 0 || s.slot == slot) {
            state = if (s.connected) s else null
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        InputHub.stateListeners.add(listener)
        state = InputHub.pads().firstOrNull { slot < 0 || it.slot == slot }?.tracker?.snapshot()
    }

    override fun onDetachedFromWindow() {
        InputHub.stateListeners.remove(listener)
        super.onDetachedFromWindow()
    }

    private fun color(pressed: Boolean) = if (pressed) on else idle

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = state
        val b = s?.buttons ?: 0
        fun p(bit: Int) = b and bit != 0
        val u = w / 100f // layout unit

        // Triggers (bars with analog fill) and shoulders.
        drawTrigger(c, 8 * u, 0f, 26 * u, 6 * u, (s?.lt ?: 0) / 32767f)
        drawTrigger(c, w - 34 * u, 0f, 26 * u, 6 * u, (s?.rt ?: 0) / 32767f)
        fill.color = color(p(Buttons.L1)); r.set(6 * u, 8 * u, 34 * u, 14 * u); c.drawRoundRect(r, 3 * u, 3 * u, fill)
        fill.color = color(p(Buttons.R1)); r.set(w - 34 * u, 8 * u, w - 6 * u, 14 * u); c.drawRoundRect(r, 3 * u, 3 * u, fill)

        // Body.
        fill.color = body
        r.set(2 * u, 16 * u, w - 2 * u, h - 2 * u)
        c.drawRoundRect(r, 14 * u, 14 * u, fill)
        if (s == null) {
            stroke.color = idle; stroke.strokeWidth = u
            c.drawRoundRect(r, 14 * u, 14 * u, stroke)
        }

        val midY = 16 * u + (h - 18 * u) * 0.36f

        // D-pad.
        val dx = 24 * u
        val arm = 6.5f * u
        val half = 3.2f * u
        fill.color = color(p(Buttons.DPAD_UP)); c.drawRect(dx - half, midY - 2 * arm, dx + half, midY - half, fill)
        fill.color = color(p(Buttons.DPAD_DOWN)); c.drawRect(dx - half, midY + half, dx + half, midY + 2 * arm, fill)
        fill.color = color(p(Buttons.DPAD_LEFT)); c.drawRect(dx - 2 * arm, midY - half, dx - half, midY + half, fill)
        fill.color = color(p(Buttons.DPAD_RIGHT)); c.drawRect(dx + half, midY - half, dx + 2 * arm, midY + half, fill)

        // Face buttons, Xbox positions.
        val fx = w - 24 * u
        val sp = 7.5f * u
        val br = 4.2f * u
        fill.color = color(p(Buttons.Y)); c.drawCircle(fx, midY - sp, br, fill)
        fill.color = color(p(Buttons.A)); c.drawCircle(fx, midY + sp, br, fill)
        fill.color = color(p(Buttons.X)); c.drawCircle(fx - sp, midY, br, fill)
        fill.color = color(p(Buttons.B)); c.drawCircle(fx + sp, midY, br, fill)

        // Select / Home / Start.
        fill.color = color(p(Buttons.SELECT)); r.set(38 * u, midY - 2 * u, 46 * u, midY + 2 * u); c.drawRoundRect(r, 2 * u, 2 * u, fill)
        fill.color = color(p(Buttons.START)); r.set(54 * u, midY - 2 * u, 62 * u, midY + 2 * u); c.drawRoundRect(r, 2 * u, 2 * u, fill)
        fill.color = color(p(Buttons.HOME)); c.drawCircle(50 * u, midY - 7 * u, 2.6f * u, fill)

        // Sticks.
        val sy = 16 * u + (h - 18 * u) * 0.74f
        drawStick(c, 37 * u, sy, 9 * u, s?.lx ?: 0, s?.ly ?: 0, p(Buttons.THUMB_L))
        drawStick(c, 63 * u, sy, 9 * u, s?.rx ?: 0, s?.ry ?: 0, p(Buttons.THUMB_R))
    }

    private fun drawTrigger(c: Canvas, x: Float, y: Float, w: Float, h: Float, v: Float) {
        fill.color = idle
        r.set(x, y, x + w, y + h)
        c.drawRoundRect(r, h / 2, h / 2, fill)
        if (v > 0.01f) {
            fill.color = on
            r.set(x, y, x + w * v.coerceIn(0f, 1f), y + h)
            c.drawRoundRect(r, h / 2, h / 2, fill)
        }
    }

    private fun drawStick(c: Canvas, cx: Float, cy: Float, rad: Float, x: Int, y: Int, pressed: Boolean) {
        stroke.color = idle
        stroke.strokeWidth = rad * 0.18f
        c.drawCircle(cx, cy, rad, stroke)
        val moved = x != 0 || y != 0
        fill.color = if (pressed || moved) on else idle
        c.drawCircle(cx + x / 32767f * rad * 0.6f, cy + y / 32767f * rad * 0.6f, rad * 0.45f, fill)
    }
}
