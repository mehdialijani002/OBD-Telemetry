package com.example.obd_telemetry_app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A self-contained, car-style analog gauge (speedometer / tachometer).
 *
 * Renders a circular instrument-cluster gauge with a coloured progress arc,
 * major/minor tick marks with numeric labels, a redline danger zone, an
 * animated tapered needle, a brushed-metal centre hub and a digital readout.
 *
 * The needle smoothly animates between values, mimicking the inertia of a
 * real dashboard gauge.
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Geometry of the sweep (Android canvas: 0° = 3 o'clock, CW positive) ----
    private val startAngle = 135f
    private val sweepAngle = 270f

    // ---- Configurable properties (settable via XML or code) ----
    var maxValue = 220f
        set(value) { field = value; invalidate() }

    var redline = Float.MAX_VALUE
        set(value) { field = value; invalidate() }

    var majorTicks = 11
        set(value) { field = value; invalidate() }

    var minorTicks = 4
        set(value) { field = value; invalidate() }

    var unit = "km/h"
        set(value) { field = value; invalidate() }

    var label = "SPEED"
        set(value) { field = value; invalidate() }

    var labelDivisor = 1f
        set(value) { field = value; invalidate() }

    var accentColor = Color.parseColor("#00E5FF")
        set(value) { field = value; rebuildShaders(); invalidate() }

    /** The value currently rendered (animated). */
    private var displayValue = 0f

    /** The value the needle is animating toward. */
    private var targetValue = 0f

    private var needleAnimator: ValueAnimator? = null

    // ---- Palette ----
    private val colorFaceOuter = Color.parseColor("#1B1F26")
    private val colorFaceInner = Color.parseColor("#0B0E13")
    private val colorBezel = Color.parseColor("#2C333D")
    private val colorTrack = Color.parseColor("#23282F")
    private val colorTickMajor = Color.parseColor("#E8EDF2")
    private val colorTickMinor = Color.parseColor("#5A636E")
    private val colorRedline = Color.parseColor("#FF1744")
    private val colorText = Color.parseColor("#F5F7FA")
    private val colorTextDim = Color.parseColor("#7A828D")

    // ---- Paints ----
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = colorTrack
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val redlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = colorRedline
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTickMajor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needleTailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val hubCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#05070A")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextDim
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.25f
    }

    // ---- Cached geometry, recomputed in onSizeChanged ----
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private val arcRect = RectF()
    private val redlineRect = RectF()
    private var arcStrokeWidth = 0f

    private val needlePath = Path()

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.GaugeView)
            maxValue = a.getFloat(R.styleable.GaugeView_gaugeMaxValue, maxValue)
            redline = a.getFloat(R.styleable.GaugeView_gaugeRedline, redline)
            majorTicks = a.getInt(R.styleable.GaugeView_gaugeMajorTicks, majorTicks)
            minorTicks = a.getInt(R.styleable.GaugeView_gaugeMinorTicks, minorTicks)
            a.getString(R.styleable.GaugeView_gaugeUnit)?.let { u -> unit = u }
            a.getString(R.styleable.GaugeView_gaugeLabel)?.let { l -> label = l }
            labelDivisor = a.getFloat(R.styleable.GaugeView_gaugeLabelDivisor, labelDivisor)
            accentColor = a.getColor(R.styleable.GaugeView_gaugeAccent, accentColor)
            a.recycle()
        }
    }

    /**
     * Smoothly drive the needle to [value]. Values are clamped to [0, maxValue].
     */
    fun setValueAnimated(value: Float) {
        val clamped = value.coerceIn(0f, maxValue)
        targetValue = clamped
        needleAnimator?.cancel()
        needleAnimator = ValueAnimator.ofFloat(displayValue, clamped).apply {
            duration = 550
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                displayValue = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Whether the live value currently sits in the redline zone. */
    val isOverRedline: Boolean
        get() = targetValue >= redline

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force a square so the gauge is always perfectly circular.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (w == 0) h else if (h == 0) w else min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        val pad = w * 0.06f
        radius = min(w, h) / 2f - pad

        arcStrokeWidth = radius * 0.10f
        val inset = arcStrokeWidth / 2f + radius * 0.04f
        arcRect.set(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)

        val redInset = inset + arcStrokeWidth * 0.65f
        redlineRect.set(cx - radius + redInset, cy - radius + redInset, cx + radius - redInset, cy + radius - redInset)

        bezelPaint.strokeWidth = radius * 0.05f
        trackPaint.strokeWidth = arcStrokeWidth
        progressPaint.strokeWidth = arcStrokeWidth
        glowPaint.strokeWidth = arcStrokeWidth * 1.5f
        redlinePaint.strokeWidth = arcStrokeWidth * 0.32f
        hubRingPaint.strokeWidth = radius * 0.02f

        tickLabelPaint.textSize = radius * 0.125f
        valuePaint.textSize = radius * 0.34f
        unitPaint.textSize = radius * 0.11f
        labelPaint.textSize = radius * 0.10f

        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (radius <= 0f) return

        // Dished, dark instrument face.
        facePaint.shader = RadialGradient(
            cx, cy * 0.85f, radius,
            intArrayOf(colorFaceOuter, colorFaceInner),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )

        // Brushed-metal bezel ring.
        bezelPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.parseColor("#11151B"), colorBezel,
                Color.parseColor("#11151B"), colorBezel,
                Color.parseColor("#11151B")
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        )

        // Green -> amber -> red progress sweep, mapped onto the 270° arc and
        // rotated so the gradient origin lines up with the gauge start angle.
        val sweepColors = intArrayOf(
            accentColor,
            Color.parseColor("#00E676"),
            Color.parseColor("#FFC400"),
            colorRedline,
            colorRedline
        )
        val sweepStops = floatArrayOf(0f, 0.35f, 0.6f, 0.74f, 1f)
        val gradient = SweepGradient(cx, cy, sweepColors, sweepStops)
        val m = Matrix().apply { postRotate(startAngle, cx, cy) }
        gradient.setLocalMatrix(m)
        progressPaint.shader = gradient
        glowPaint.shader = gradient

        // Polished hub.
        hubPaint.shader = RadialGradient(
            cx - radius * 0.04f, cy - radius * 0.04f, radius * 0.22f,
            intArrayOf(Color.parseColor("#3A434F"), Color.parseColor("#10151B")),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )

        // Metallic needle gradient.
        needlePaint.shader = LinearGradient(
            cx, cy - radius, cx, cy,
            intArrayOf(accentColor, Color.WHITE),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        labelPaint.color = accentColor
    }

    private fun valueToAngle(value: Float): Float =
        startAngle + (value.coerceIn(0f, maxValue) / maxValue) * sweepAngle

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        // 1. Face + bezel
        canvas.drawCircle(cx, cy, radius, facePaint)
        canvas.drawCircle(cx, cy, radius - bezelPaint.strokeWidth / 2f, bezelPaint)

        // 2. Background track
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)

        // 3. Active progress arc (+ soft glow)
        val progressSweep = (displayValue / maxValue) * sweepAngle
        if (progressSweep > 0f) {
            glowPaint.alpha = 60
            canvas.drawArc(arcRect, startAngle, progressSweep, false, glowPaint)
            glowPaint.alpha = 255
            canvas.drawArc(arcRect, startAngle, progressSweep, false, progressPaint)
        }

        // 4. Redline band
        if (redline < maxValue) {
            val redStart = valueToAngle(redline)
            val redSweep = startAngle + sweepAngle - redStart
            canvas.drawArc(redlineRect, redStart, redSweep, false, redlinePaint)
        }

        // 5. Ticks + numeric labels
        drawTicks(canvas)

        // 6. Needle + hub
        drawNeedle(canvas)

        // 7. Digital readout
        drawReadout(canvas)
    }

    private fun drawTicks(canvas: Canvas) {
        val majorOuter = radius * 0.94f
        val majorInner = radius * 0.80f
        val minorOuter = radius * 0.94f
        val minorInner = radius * 0.86f
        val labelRadius = radius * 0.66f

        val totalSegments = majorTicks
        val minorPerSegment = minorTicks + 1

        for (i in 0..totalSegments) {
            val fraction = i.toFloat() / totalSegments
            val value = fraction * maxValue
            val angle = startAngle + fraction * sweepAngle
            val rad = Math.toRadians(angle.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val inRedline = value >= redline - 0.0001f
            tickPaint.color = if (inRedline) colorRedline else colorTickMajor
            tickPaint.strokeWidth = radius * 0.022f
            canvas.drawLine(
                cx + cosA * majorInner, cy + sinA * majorInner,
                cx + cosA * majorOuter, cy + sinA * majorOuter,
                tickPaint
            )

            // Numeric label — round to 1 decimal first to avoid float artefacts
            // (e.g. 60.00001 rendering as "60.0").
            val labelValue = Math.round(value / labelDivisor * 10f) / 10f
            val text = if (labelValue % 1f == 0f) labelValue.toInt().toString()
            else String.format("%.1f", labelValue)
            tickLabelPaint.color = if (inRedline) colorRedline else colorTickMajor
            val lx = cx + cosA * labelRadius
            val ly = cy + sinA * labelRadius - (tickLabelPaint.ascent() + tickLabelPaint.descent()) / 2f
            canvas.drawText(text, lx, ly, tickLabelPaint)

            // Minor ticks between this major and the next
            if (i < totalSegments) {
                for (j in 1 until minorPerSegment) {
                    val mFraction = (i + j.toFloat() / minorPerSegment) / totalSegments
                    val mValue = mFraction * maxValue
                    val mAngle = startAngle + mFraction * sweepAngle
                    val mRad = Math.toRadians(mAngle.toDouble())
                    val mCos = cos(mRad).toFloat()
                    val mSin = sin(mRad).toFloat()
                    tickPaint.color = if (mValue >= redline) colorRedline else colorTickMinor
                    tickPaint.strokeWidth = radius * 0.012f
                    canvas.drawLine(
                        cx + mCos * minorInner, cy + mSin * minorInner,
                        cx + mCos * minorOuter, cy + mSin * minorOuter,
                        tickPaint
                    )
                }
            }
        }
    }

    private fun drawNeedle(canvas: Canvas) {
        val angle = valueToAngle(displayValue)
        canvas.save()
        canvas.rotate(angle + 90f, cx, cy) // +90 so the path's "up" points along the angle

        val tipLen = radius * 0.82f
        val tailLen = radius * 0.18f
        val baseHalf = radius * 0.045f

        needlePath.reset()
        needlePath.moveTo(cx, cy - tipLen)              // sharp tip
        needlePath.lineTo(cx - baseHalf, cy + tailLen * 0.4f)
        needlePath.lineTo(cx + baseHalf, cy + tailLen * 0.4f)
        needlePath.close()
        canvas.drawPath(needlePath, needlePaint)

        // Counterweight tail
        needleTailPaint.color = Color.parseColor("#C8CED6")
        canvas.drawRoundRect(
            cx - baseHalf * 0.8f, cy, cx + baseHalf * 0.8f, cy + tailLen,
            baseHalf, baseHalf, needleTailPaint
        )
        canvas.restore()

        // Centre hub
        canvas.drawCircle(cx, cy, radius * 0.16f, hubPaint)
        hubRingPaint.color = accentColor
        canvas.drawCircle(cx, cy, radius * 0.16f, hubRingPaint)
        canvas.drawCircle(cx, cy, radius * 0.05f, hubCenterPaint)
    }

    private fun drawReadout(canvas: Canvas) {
        val over = isOverRedline

        // Caption (e.g. SPEED) — sits just below the hub.
        labelPaint.color = if (over) colorRedline else accentColor
        canvas.drawText(label, cx, cy + radius * 0.34f, labelPaint)

        // Big digital value, in the open lower segment of the dial.
        valuePaint.color = if (over) colorRedline else colorText
        canvas.drawText(displayValue.toInt().toString(), cx, cy + radius * 0.62f, valuePaint)

        // Unit
        canvas.drawText(unit, cx, cy + radius * 0.75f, unitPaint)
    }
}
