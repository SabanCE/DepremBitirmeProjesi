package com.example.deprembitirmeprojesi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "earthquake_records")
data class EarthquakeRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0, // ID Long olarak güncellendi
    val timestamp: Long,
    val magnitude: Float,
    var status: String, // 'type' alanı 'status' olarak değiştirildi
    val address: String = "Konum Alınıyor...",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)