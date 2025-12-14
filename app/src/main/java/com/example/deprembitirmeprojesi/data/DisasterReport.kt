package com.example.deprembitirmeprojesi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reports")
data class DisasterReport(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderId: String,       // Depremzede ID'si (veya EndpointID)
    val rawMessage: String,     // Gelen ham mesaj (KIMLIK: Ahmet...)
    val receivedTimestamp: Long, // Ne zaman aldık?
    val isUploaded: Boolean = false // Firebase'e gitti mi?
)