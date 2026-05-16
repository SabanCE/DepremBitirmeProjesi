package com.example.deprembitirmeprojesi.util

object Constants {
    const val TIME_THRESHOLD_MS = 15000L // Teyit için 15 saniye penceresi
    const val DISTANCE_THRESHOLD_METERS = 2000 // 2 km yarıçap

    const val DUMMY_USER_ID = "Kullanici_Anonim"
    const val FIRESTORE_COLLECTION_ALERTS = "earthquake_alerts"
    const val FIRESTORE_COLLECTION_USERS = "users"
    const val DATE_FORMAT = "dd/MM/yyyy HH:mm:ss"
    const val DATE_FORMAT_ADAPTER = "dd MMM HH:mm:ss"
    const val EARTHQUAKE_TYPE = "QUAKE"
    const val LOCATION_NOT_AVAILABLE = "Konum Yok"
    const val UNKNOWN = "Bilinmiyor"
    const val ADDRESS_NOT_FOUND = "Adres Bulunamadı"
    const val STATUS_ANALYSING = "ANALYSING"
    const val STATUS_EARTHQUAKE = "DEPREM"

    /**
     * Sarsıntı eşiği. Test için 2.5f daha idealdir.
     */
    const val SHAKE_THRESHOLD = 2.5f

    const val ROLE_PERSONNEL = "personel"
    const val ROLE_MUDUR = "mudur"

    // Firestore fields
    const val FIELD_USER_ID = "user_id"
    const val FIELD_MAGNITUDE = "magnitude"
    const val FIELD_LATITUDE = "latitude"
    const val FIELD_LONGITUDE = "longitude"
    const val FIELD_CITY = "city"
    const val FIELD_DISTRICT = "district"
    const val FIELD_TIMESTAMP = "timestamp"
    const val FIELD_DATETIME = "datetime"
    const val FIELD_STATUS = "status"
    const val FIELD_NEARBY_DEVICES = "nearby_devices"
    const val FIELD_RISK_SCORE = "risk_score"

    // Risk Levels
    const val LEVEL_LOW = "LOW"
    const val LEVEL_MEDIUM = "MEDIUM"
    const val LEVEL_HIGH = "HIGH"

    // Notification Channels
    const val CHANNEL_LOW = "channel_low_precision"
    const val CHANNEL_MEDIUM = "channel_medium_precision"
    const val CHANNEL_HIGH = "channel_high_precision"
}
