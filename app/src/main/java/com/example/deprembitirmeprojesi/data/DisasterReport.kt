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
    
    // Role-Based Mesh Alanları
    var role: String = "VICTIM", // VICTIM, AFAD, RELAY
    var status: String = "PENDING", // PENDING, CLAIMED, RESCUING, RESCUED
    var assignedToAfadId: String? = null,
    var priorityLevel: Int = 0, // 0-100 (Risk Score entegre edilecek)
    var version: Long = 0, // Her güncellemede artacak (Vector Clock basitleştirmesi)

    // Detaylı Bilgiler
    var bloodType: String = "",
    var chronicIllness: String = "",
    var birthDate: String = "",
    var apartmentInfo: String = "",
    var floorInfo: String = "",
    var regularMedication: String = ""
)
