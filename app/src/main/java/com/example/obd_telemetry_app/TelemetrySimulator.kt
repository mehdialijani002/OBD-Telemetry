package com.example.obd_telemetry_app

import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.random.Random

/**
 * Generates a believable stream of fake OBD telemetry for testing the UI
 * without a live MQTT feed.
 *
 * It runs a simple drive-cycle state machine — accelerate to a target speed,
 * cruise, then slow down and pick a new target — occasionally choosing a target
 * above the speed limit so the redline zone and over-limit warning get exercised.
 * RPM is derived from speed through a 5-gear model so the tachometer behaves like
 * a real one, dropping on each upshift.
 *
 * All callbacks are delivered on the main thread, so [onUpdate] may touch views
 * directly.
 */
class TelemetrySimulator(
    private val onUpdate: (speed: Float, rpm: Float) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val tickMs = 180L

    private var speed = 0f
    private var targetSpeed = 55f
    private var holdTicks = 0
    private var running = false

    // Upper speed of each gear; index = gear - 1.
    private val gearTops = floatArrayOf(30f, 55f, 85f, 115f, 220f)

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            onUpdate(speed, rpmFor(speed))
            handler.postDelayed(this, tickMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun step() {
        val reached = abs(speed - targetSpeed) < 1.5f
        if (reached && holdTicks > 0) {
            holdTicks--
        } else if (reached) {
            // Pick a new cruising target. ~1 in 4 runs goes over the 120 limit.
            targetSpeed = if (Random.nextInt(4) == 0) {
                Random.nextInt(125, 185).toFloat()
            } else {
                Random.nextInt(0, 115).toFloat()
            }
            holdTicks = Random.nextInt(8, 28)
        }

        // Ease toward the target — brake faster than we accelerate.
        val rate = if (targetSpeed > speed) 2.6f else 3.4f
        speed += (targetSpeed - speed).coerceIn(-rate, rate)
        // A little road noise.
        speed = (speed + Random.nextFloat() * 1.2f - 0.6f).coerceIn(0f, 220f)
    }

    /** Map road speed to engine RPM using a simple gear model. */
    private fun rpmFor(s: Float): Float {
        if (s < 1f) return 850f + Random.nextFloat() * 60f // idle
        var lower = 0f
        for (top in gearTops) {
            if (s < top || top == gearTops.last()) {
                val frac = ((s - lower) / (top - lower)).coerceIn(0f, 1f)
                val rpm = 1300f + frac * (6300f - 1300f)
                return (rpm + Random.nextFloat() * 120f - 60f).coerceIn(700f, 8000f)
            }
            lower = top
        }
        return 1300f
    }
}
