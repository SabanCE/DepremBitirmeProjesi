package com.example.deprembitirmeprojesi.domain.model

data class SeismicEvent(
    val pWaveConfidence: Float,
    val sWaveConfidence: Float,
    val combinedConfidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AlertLevel {
    SAFE, LOW, MEDIUM, HIGH
}
