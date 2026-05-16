package com.example.deprembitirmeprojesi.logic

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.abs

class SignalProcessor {
    private var gravity = floatArrayOf(0f, 0f, 0f)
    private val alpha = 0.8f 

    // STA/LTA Değişkenleri
    private var lta = 0.05f 
    private val ltaAlpha = 0.998f 
    private val staAlpha = 0.1f   
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

    fun calculateMagnitude(values: FloatArray): Float {
        return sqrt(values[0].pow(2) + values[1].pow(2) + values[2].pow(2))
    }

    fun calculateSTALTA(magnitude: Float): Float {
        sta = (1 - staAlpha) * sta + staAlpha * magnitude
        lta = (1 - ltaAlpha) * lta + ltaAlpha * magnitude
        
        if (lta < 0.01f) lta = 0.01f 
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

    /**
     * Ritmik hareket kontrolü (Koşma, adım atma gibi)
     * Testlerde yanıltıcı olmaması için aralığı genişlettik ve sadece bilgilendirme amaçlı kullanacağız.
     */
    fun isRhythmic(buffer: List<Float>): Boolean {
        if (buffer.size < 40) return false
        var peaks = 0
        for (i in 1 until buffer.size - 1) {
            // Test için sarsıntı eşiğini 2.0f yaptık (Çok hızlı sallamaları ayırmak için)
            if (buffer[i] > buffer[i-1] && buffer[i] > buffer[i+1] && buffer[i] > 2.0f) {
                peaks++
            }
        }
        // Çok düzenli bir ritim varsa true döner
        return peaks in 5..12
    }
}
