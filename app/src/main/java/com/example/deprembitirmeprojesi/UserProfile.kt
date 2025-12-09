package com.example.deprembitirmeprojesi

data class UserProfile(
    val fullName: String = "",
    val email: String = "",
    val role: String = "user", // Varsayılan rol eklendi
    val tckn: String = "",
    val birthDate: String = "",
    val bloodType: String = "",
    val gender: String = "",
    val disabilityStatus: String = "",
    val chronicIllness: String = "",
    val allergies: String = "",
    val regularMedication: String = "",
    val pregnancyStatus: String = "",
    val floorInfo: String = ""
)