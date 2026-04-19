package com.example.deprembitirmeprojesi.logic

import kotlin.math.pow
import kotlin.math.sqrt

class SignalProcessor {
    private var gravity = floatArrayOf(0f, 0f, 0f)
    private val alpha = 0.8f 

    // STA/LTA Değişkenleri
    private var lta = 0.05f // Arka plan gürültü ortalaması (Long Term)
    private val ltaAlpha = 0.998f // LTA yavaş güncellenir
    private val staAlpha = 0.1f   // STA hızlı güncellenir
    private var sta = 0.05f

    fun removeGravity(values: FloatArray): FloatArray {
        gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2]

        return floatArrayOf(
            values[0] - gravity[0],
            values[1] - gravity[1],
            values[2] - gravity[2]
        )
    }

    // Gürültü ve Deprem Ayrımı için STA/LTA Oranı
    // Oran > 4.0 ise gerçek bir olay başlama ihtimali %90+ dır.
    fun calculateSTALTA(magnitude: Float): Float {
        // Arka plan gürültüsünü (LTA) ve anlık sarsıntıyı (STA) güncelle
        sta = (1 - staAlpha) * sta + staAlpha * magnitude
        lta = (1 - ltaAlpha) * lta + ltaAlpha * magnitude
        
        if (lta < 0.01f) lta = 0.01f // Bölme hatasını engelle
        return sta / lta
    }

    fun calculateRMS(buffer: List<Float>): Float {
        if (buffer.isEmpty()) return 0f
        val sumOfSquares = buffer.fold(0f) { acc, value -> acc + value.pow(2) }
        return sqrt(sumOfSquares / buffer.size)
    }

    fun calculateVariance(buffer: List<Float>): Float {
        if (buffer.size < 2) return 0f
        val avg = buffer.average().toFloat()
        return buffer.map { (it - avg).pow(2) }.average().toFloat()
    }
    
    fun calculateZCR(buffer: List<Float>): Float {
        var crossings = 0
        for (i in 1 until buffer.size) {
            if ((buffer[i] >= 0 && buffer[i-1] < 0) || (buffer[i] < 0 && buffer[i-1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / buffer.size
    }
}
