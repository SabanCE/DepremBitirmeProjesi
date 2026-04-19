package com.example.deprembitirmeprojesi.logic

class WaveDetector(private val processor: SignalProcessor) {

    /**
     * P-Wave: STA/LTA Oranı 4-8 arası, yüksek frekans (ZCR > 0.4)
     */
    fun detectPWave(window: List<Float>, staltaRatio: Float): Float {
        val zcr = processor.calculateZCR(window)
        val rms = processor.calculateRMS(window)
        
        // Sismik P-dalgası karakteristiği: Ani enerji artışı ama düşük genlik
        return if (staltaRatio in 4.0f..12.0f && zcr > 0.40f) {
            (staltaRatio / 12.0f * 0.5f + zcr * 0.5f).coerceIn(0f, 1f)
        } else 0f
    }

    /**
     * S-Wave: STA/LTA Oranı > 10, yüksek varyans
     */
    fun detectSWave(window: List<Float>, staltaRatio: Float, pWaveFound: Boolean): Float {
        val rms = processor.calculateRMS(window)
        val variance = processor.calculateVariance(window)

        // S-dalgası: Çok yüksek enerji artışı
        return if (staltaRatio > 8.0f || rms > 4.0f) {
            var confidence = (staltaRatio / 25.0f).coerceIn(0f, 1f)
            if (pWaveFound) confidence += 0.2f
            if (variance > 5.0f) confidence += 0.1f
            confidence.coerceIn(0f, 1f)
        } else 0f
    }
}
