package com.example.deprembitirmeprojesi

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "earthquakes")
data class EarthquakeRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val magnitude: Float,
    val type: String,
    val address: String = "Konum Alınıyor...",
    // Teyit mekanizması için enlem ve boylam bilgileri eklendi
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)