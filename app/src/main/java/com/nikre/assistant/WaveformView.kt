package com.nikre.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Nikre tinglayotganda ko'rinadigan, ovoz balandligiga qarab o'ynaydigan,
 * rang-barang chiziqli to'lqin effekti. Tayyor kutubxona emas — o'zimizniki.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barCount = 24
    private val barHeights = FloatArray(barCount) { 0.12f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var level = 0f
    private var hueShift = 0f
    private var animator: ValueAnimator? = null

    /** Ovoz balandligini (0..1) yangilaydi; animatsiya shunga qarab reaksiya beradi. */
    fun setLevel(newLevel: Float) {
        level = newLevel.coerceIn(0f, 1f)
    }

    fun start() {
        visibility = VISIBLE
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 90
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                hueShift = (hueShift + 6f) % 360f
                for (i in barHeights.indices) {
                    val target = 0.12f + level * Random.nextFloat() * 0.88f
                    barHeights[i] += (target - barHeights[i]) * 0.35f
                }
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val barWidth = w / barCount
        for (i in 0 until barCount) {
            val hue = (hueShift + i * (360f / barCount)) % 360f
            paint.color = Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.95f))
            val barH = (h * barHeights[i]).coerceAtLeast(4f)
            val left = i * barWidth + barWidth * 0.18f
            val right = (i + 1) * barWidth - barWidth * 0.18f
            val top = (h - barH) / 2f
            val bottom = (h + barH) / 2f
            val radius = barWidth * 0.25f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        }
    }
}
