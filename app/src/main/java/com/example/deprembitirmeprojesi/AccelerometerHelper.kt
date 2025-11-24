package com.example.deprembitirmeprojesi
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

// Bu sınıf sensör verisini dinler ve MainActivity'e haber verir


class AccelerometerHelper(context: Context, private val listener: AccelerometerListener) : SensorEventListener {

    interface AccelerometerListener {
        fun onShakeDetected(force: Float) // Deprem/Sarsıntı algılandı
        fun onSensorChanged(x: Float, y: Float, z: Float) // Grafik için ham veri
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val x = it.values[0]
            val y = it.values[1]
            val z = it.values[2]

            // Yerçekimi ivmesini (g ~ 9.8) normalize etmek için:
            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH

            // Vektörel Büyüklük Hesaplama (Karekök(x^2 + y^2 + z^2))
            // 1.0f çıkarıyoruz ki telefon sabitken 0'a yakın olsun (Yerçekimini siliyoruz)
            val gForce = sqrt(gX * gX + gY * gY + gZ * gZ) - 1.0f

            listener.onSensorChanged(x, y, z) // Grafiğe veri gönder

            if (gForce > Constants.SHAKE_THRESHOLD) {
                listener.onShakeDetected(gForce) // Deprem uyarısı gönder
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Gerek yok
    }
}