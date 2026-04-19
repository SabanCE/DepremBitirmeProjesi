package com.example.deprembitirmeprojesi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "disaster_reports")
data class DisasterReport(
    @PrimaryKey val senderId: String,
    var rawMessage: String,
    var userProfile: String = "",
    var batteryLevel: String = "",
    var lastLocation: String = "",
    var isConnected: Boolean = false,
    var lastSeenTimestamp: Long,
    var isUploaded: Boolean = false,
    
    // Yeni Eklenen Detaylı Bilgiler
    var bloodType: String = "",
    var chronicIllness: String = "",
    var birthDate: String = "",
    var apartmentInfo: String = "",
    var floorInfo: String = "",
    var regularMedication: String = ""
)
