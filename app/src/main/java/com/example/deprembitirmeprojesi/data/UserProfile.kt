package com.example.deprembitirmeprojesi.data

data class UserProfile(
    val fullName: String = "",
    val email: String = "",
    val role: String = "user", //personel için ayrı
    val tckn: String = "",
    val birthDate: String = "",
    val bloodType: String = "",
    val gender: String = "",
    val disabilityStatus: String = "",
    val chronicIllness: String = "",
    val allergies: String = "",
    val regularMedication: String = "",
    val pregnancyStatus: String = "",
    val apartmentInfo: String = "",
    val floorInfo: String = "",
    var status: String = "BOŞTA", // BOŞTA, YOLDA, ÇALIŞIYOR
    var needs: String = "YOK",
    var assignedLocation: String = "" // "lat,lng" formatında
)