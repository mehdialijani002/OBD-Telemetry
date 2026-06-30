package com.example.obd_telemetry_app

import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * Live OBD instrument cluster: an analog speedometer and tachometer driven by
 * telemetry pushed over MQTT.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var speedGauge: GaugeView
    private lateinit var rpmGauge: GaugeView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var footerStatusValue: TextView

    private var mediaPlayer: MediaPlayer? = null
    private val speedLimit = 120

    // Initial data source on launch; tap the status pill to switch at runtime.
    private val startWithSimulatedData = true
    private var simulating = false
    private var simulator: TelemetrySimulator? = null

    private val brokerUrl = "tcp://broker.hivemq.com:1883"
    private val topicSpeed = "automotive/obd/speed"
    // Legacy publishers used the "fuel" topic for the second gauge; we now treat
    // the second gauge as a tachometer and listen on the dedicated rpm topic
    // while keeping the old topic for backward compatibility.
    private val topicRpm = "automotive/obd/rpm"
    private val topicRpmLegacy = "automotive/obd/fuel"

    private var client: MqttClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speedGauge = findViewById(R.id.speedGauge)
        rpmGauge = findViewById(R.id.rpmGauge)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        footerStatusValue = findViewById(R.id.footerStatusValue)

        configureGauges()

        findViewById<View>(R.id.statusPill).setOnClickListener { toggleSource() }
        setSource(startWithSimulatedData)
    }

    /** Tap handler: flip between generated demo data and the live MQTT feed. */
    private fun toggleSource() {
        setSource(!simulating)
        val msg = if (simulating) R.string.toast_demo else R.string.toast_live
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Switch the active data source, tearing down whichever one was running. */
    private fun setSource(simulated: Boolean) {
        simulating = simulated
        stopSimulation()
        disconnectMqtt()
        resetGauges()
        if (simulated) {
            startSimulation()
        } else {
            setStatus(ConnectionState.CONNECTING)
            connectToMqttBroker()
        }
    }

    /** Drive the gauges from generated demo data instead of MQTT. */
    private fun startSimulation() {
        setStatus(ConnectionState.DEMO)
        simulator = TelemetrySimulator { speed, rpm ->
            onTelemetry(topicSpeed, speed)
            onTelemetry(topicRpm, rpm)
        }.also { it.start() }
    }

    private fun stopSimulation() {
        simulator?.stop()
        simulator = null
    }

    /** Reset both needles to zero and clear the over-limit warning. */
    private fun resetGauges() {
        speedGauge.setValueAnimated(0f)
        rpmGauge.setValueAnimated(0f)
        footerStatusValue.setText(R.string.footer_status_normal)
        footerStatusValue.setTextColor(getColor(R.color.cluster_green))
    }

    /** Set up the analog speedometer and tachometer scales, redlines and styling. */
    private fun configureGauges() {
        speedGauge.apply {
            maxValue = 220f
            redline = speedLimit.toFloat()
            majorTicks = 11
            minorTicks = 4
            unit = "km/h"
            label = getString(R.string.label_speed)
            labelDivisor = 1f
            accentColor = getColor(R.color.cluster_accent)
        }
        rpmGauge.apply {
            maxValue = 8000f
            redline = 6000f
            majorTicks = 8
            minorTicks = 4
            unit = "rpm"
            label = getString(R.string.label_rpm)
            labelDivisor = 1000f
            accentColor = getColor(R.color.cluster_accent_rpm)
        }
    }

    private enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, DEMO }

    private fun setStatus(state: ConnectionState) {
        val (textRes, color) = when (state) {
            ConnectionState.CONNECTING ->
                R.string.status_connecting to getColor(R.color.cluster_amber)
            ConnectionState.CONNECTED ->
                R.string.status_connected to getColor(R.color.cluster_green)
            ConnectionState.DISCONNECTED ->
                R.string.status_disconnected to getColor(R.color.cluster_red)
            ConnectionState.DEMO ->
                R.string.status_demo to getColor(R.color.cluster_accent)
        }
        statusText.setText(textRes)
        statusText.setTextColor(color)
        statusDot.background?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun connectToMqttBroker() {
        val clientId = "AndroidOBD_Receiver_${System.currentTimeMillis()}"
        val topics = arrayOf(topicSpeed, topicRpm, topicRpmLegacy)

        Thread {
            try {
                val mqttClient = MqttClient(brokerUrl, clientId, null)
                client = mqttClient
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 20
                    isAutomaticReconnect = true
                }

                mqttClient.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        runOnUiThread { if (!simulating) setStatus(ConnectionState.DISCONNECTED) }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val value = message?.toString()?.trim()?.toFloatOrNull() ?: return
                        runOnUiThread { if (!simulating) onTelemetry(topic, value) }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient.connect(options)
                mqttClient.subscribe(topics)
                runOnUiThread { if (!simulating) setStatus(ConnectionState.CONNECTED) }
            } catch (e: MqttException) {
                e.printStackTrace()
                runOnUiThread { if (!simulating) setStatus(ConnectionState.DISCONNECTED) }
            }
        }.start()
    }

    /** Close the MQTT client (network I/O, so off the main thread). */
    private fun disconnectMqtt() {
        val c = client ?: return
        client = null
        Thread {
            try {
                if (c.isConnected) c.disconnect()
                c.close()
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun onTelemetry(topic: String?, value: Float) {
        when (topic) {
            topicSpeed -> {
                speedGauge.setValueAnimated(value)
                if (value > speedLimit) {
                    footerStatusValue.setText(R.string.footer_status_warning)
                    footerStatusValue.setTextColor(getColor(R.color.cluster_red))
                    playWarningSound()
                } else {
                    footerStatusValue.setText(R.string.footer_status_normal)
                    footerStatusValue.setTextColor(getColor(R.color.cluster_green))
                }
            }
            topicRpm, topicRpmLegacy -> rpmGauge.setValueAnimated(value)
        }
    }

    private fun playWarningSound() {
        if (mediaPlayer == null || mediaPlayer?.isPlaying == false) {
            mediaPlayer = MediaPlayer.create(this, R.raw.beep)
            mediaPlayer?.start()
            mediaPlayer?.setOnCompletionListener {
                it.release()
                mediaPlayer = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSimulation()
        disconnectMqtt()
        mediaPlayer?.release()
    }
}
