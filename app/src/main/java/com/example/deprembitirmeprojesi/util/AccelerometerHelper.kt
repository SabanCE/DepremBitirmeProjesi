package com.example.deprembitirmeprojesi.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deprem karakteristiğini (pattern) tespit eden yardımcı sınıf.
 * Sadece eşik değerini değil; sarsıntının süresini, eksen çeşitliliğini ve sürekliliğini kontrol eder.
 */
class AccelerometerHelper(context: Context, private val listener: AccelerometerListener) : SensorEventListener {

    interface AccelerometerListener {
        fun onShakeDetected(force: Float) // Pattern doğrulandı (Deprem tespiti)
        fun onSensorChanged(x: Float, y: Float, z: Float) // Grafik için ham veri
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // --- Pattern Takip Değişkenleri ---
    private var shakeStartTime: Long = 0
    private var lastSignificantMoveTime: Long = 0
    private val activeAxes = mutableSetOf<Int>() // 0:X, 1:Y, 2:Z

    // --- Algoritma Parametreleri (Kritik Pattern Değerleri) ---
    private val MIN_SHAKE_DURATION = 1200L      // Sarsıntı en az 1.2 saniye sürmeli
    private val MAX_GAP_BETWEEN_SHAKES = 400L    // Sarsıntılar arası max boşluk
    private val MIN_AXES_COUNT = 2               // En az 2 eksende hareket olmalı
    private val SHAKE_THRESHOLD = 1.5f            // SENKRONİZE EDİLDİ: Servis ile aynı (1.5g)

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        resetPattern()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val x = it.values[0]
            val y = it.values[1]
            val z = it.values[2]

            // 1. Veriyi Normalize Et (Yerçekimi etkisini çıkar)
            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH
            
            // Vektörel büyüklükten 1.0 (sabit yerçekimi) çıkarıyoruz
            val gForce = sqrt(gX * gX + gY * gY + gZ * gZ) - 1.0f

            listener.onSensorChanged(x, y, z) // Grafik için ham veriyi gönder

            val currentTime = System.currentTimeMillis()

            // 2. Hareket Algılama ve Eksen Takibi
            if (abs(gForce) > SHAKE_THRESHOLD) {
                if (shakeStartTime == 0L) {
                    shakeStartTime = currentTime
                }
                lastSignificantMoveTime = currentTime

                // Hangi eksenlerde belirgin sapma var?
                if (abs(gX) > SHAKE_THRESHOLD / 2) activeAxes.add(0)
                if (abs(gY) > SHAKE_THRESHOLD / 2) activeAxes.add(1)
                if (abs(gZ) > SHAKE_THRESHOLD / 2) activeAxes.add(2)
            }

            // 3. Pattern Analizi
            if (shakeStartTime != 0L) {
                // Sarsıntı kesildi mi? (Eğer 500ms sarsıntı yoksa pattern bozulmuştur)
                if (currentTime - lastSignificantMoveTime > MAX_GAP_BETWEEN_SHAKES) {
                    resetPattern()
                    return
                }

                val duration = currentTime - shakeStartTime

                // DOĞRULAMA (DEPREM PATTERNİ):
                // - Süre 1.5 saniyeyi geçti mi?
                // - En az 2 farklı eksende (X, Y veya Z) hareket var mı?
                if (duration >= MIN_SHAKE_DURATION && activeAxes.size >= MIN_AXES_COUNT) {
                    listener.onShakeDetected(gForce)
                    
                    // Sürekli tetiklenmemesi için resetliyoruz
                    resetPattern()
                }
            }
        }
    }

    private fun resetPattern() {
        shakeStartTime = 0L
        lastSignificantMoveTime = 0L
        activeAxes.clear()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Kullanılmıyor
    }
}
