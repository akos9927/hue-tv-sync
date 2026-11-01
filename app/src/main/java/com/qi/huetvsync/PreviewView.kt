package com.qi.huetvsync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** A small picture of the screen showing which part each light reads and the color it gets. */
class PreviewView(context: Context) : View(context) {
    private val screen = Paint().apply { color = Color.rgb(6, 7, 10) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(70, 76, 90)
    }
    private val bar = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val fill = Paint()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(140, 255, 255, 255)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 22f
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 163, 178)
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, screen)
        canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, frame)

        val engine = SyncState.engine
        if (engine == null) {
            canvas.drawText("Start syncing to see what each light reads", w / 2, h / 2 + 8f, hint)
            return
        }
        val regions = engine.regions
        val colors = SyncState.previewColors
        val ct = SyncState.cropTop
        val cl = SyncState.cropLeft
        if (ct > 0f) {
            canvas.drawRect(0f, 0f, w, ct * h, bar)
            canvas.drawRect(0f, h - ct * h, w, h, bar)
        }
        if (cl > 0f) {
            canvas.drawRect(0f, 0f, cl * w, h, bar)
            canvas.drawRect(w - cl * w, 0f, w, h, bar)
        }
        val cw = 1f - 2f * cl
        val ch = 1f - 2f * ct
        val tested = SyncState.testChannel
        regions.forEachIndexed { i, r ->
            val left = (cl + r.left * cw) * w
            val right = (cl + r.right * cw) * w
            val top = (ct + r.top * ch) * h
            val bottom = (ct + r.bottom * ch) * h
            fill.color = if (colors != null && colors.size >= i * 3 + 3) {
                Color.rgb(
                    (colors[i * 3] * 255).toInt().coerceIn(0, 255),
                    (colors[i * 3 + 1] * 255).toInt().coerceIn(0, 255),
                    (colors[i * 3 + 2] * 255).toInt().coerceIn(0, 255),
                )
            } else {
                Color.rgb(40, 44, 52)
            }
            if (tested >= 0) fill.color = if (i == tested) Color.WHITE else Color.rgb(20, 22, 28)
            canvas.drawRect(left, top, right, bottom, fill)
            canvas.drawRect(left, top, right, bottom, if (i == tested) highlight else outline)
            label.color = if (i == tested) Color.BLACK else Color.argb(220, 255, 255, 255)
            canvas.drawText(engine.area.channels[i].id.toString(), (left + right) / 2, (top + bottom) / 2 + 8f, label)
        }
    }
}
