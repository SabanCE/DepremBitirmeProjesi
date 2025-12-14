package com.example.deprembitirmeprojesi

/**
 * Proje genelinde kullanılan sabit değerleri içeren bir nesne.
 * Bu, sabitlerin tek bir yerden yönetilmesini sağlar ve kod tekrarını önler.
 */
object Constants {
    /**
     * Yakınlardaki diğer deprem uyarılarını ararken kullanılacak zaman eşiği (milisaniye cinsinden).
     * Bir sarsıntının ne kadar süreyle "yeni" kabul edileceğini belirtir.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val TIME_THRESHOLD_MS = 30000L

    /**
     * Bir deprem teyidi için diğer uyarıların olması gereken maksimum mesafe (metre cinsinden).
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val DISTANCE_THRESHOLD_METERS = 5000

    /**
     * Geçici, sabit kullanıcı kimliği. Gerçek bir kullanıcı sistemi entegre edilene kadar kullanılır.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val DUMMY_USER_ID = "Kullanici_123"

    /**
     * Deprem uyarı verilerinin saklandığı Firestore koleksiyonunun adı.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val FIRESTORE_COLLECTION_ALERTS = "earthquake_alerts"

    /**
     * Kullanıcı profillerinin saklandığı Firestore koleksiyonunun adı.
     * Kullanıldığı Yer: `RegisterActivity.kt`, `LoginActivity.kt`, `ProfileActivity.kt`
     */
    const val FIRESTORE_COLLECTION_USERS = "users"

    /**
     * Firestore'a kaydedilecek olan, insanlar tarafından okunabilir tarih ve saat metninin formatı.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val DATE_FORMAT = "dd/MM/yyyy HH:mm:ss"

    /**
     * Geçmiş depremlerin listelendiği RecyclerView'de tarihlerin gösterileceği format.
     * Kullanıldığı Yer: `EarthquakeAdapter.kt`
     */
    const val DATE_FORMAT_ADAPTER = "dd MMM HH:mm:ss"

    /**
     * Yerel veritabanına kaydedilen sarsıntı kaydının türü.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val EARTHQUAKE_TYPE = "QUAKE"

    /**
     * Cihazın coğrafi konumu alınamadığında adres bilgisi yerine gösterilecek varsayılan metin.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val LOCATION_NOT_AVAILABLE = "Konum Yok"

    /**
     * Adresin şehir veya ilçe gibi bir parçası eksik olduğunda kullanılacak varsayılan metin.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val UNKNOWN = "Bilinmiyor"

    /**
     * Konum koordinatları adrese dönüştürülemediğinde gösterilecek hata metni.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val ADDRESS_NOT_FOUND = "Adres Bulunamadı"

    /**
     * Firestore'a gönderilen deprem uyarısının analiz durumunu belirtir.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val STATUS_ANALYSING = "ANALYSING"

    /**
     * Teyit edilmiş deprem durumunu belirtir.
     * Kullanıldığı Yer: `MainViewModel.kt`
     */
    const val STATUS_EARTHQUAKE = "DEPREM"

    /**
     * Bir sarsıntının "deprem" olarak algılanması için gereken minimum G-kuvveti eşiği.
     * Kullanıldığı Yer: `AccelerometerHelper.kt`
     */
    const val SHAKE_THRESHOLD = 2.5f

    /**
     * Acil durum personeli rolünü tanımlayan sabit.
     * Kullanıldığı Yer: `LoginActivity.kt`
     */
    const val ROLE_PERSONNEL = "personel"

    /**
     * Firestore veritabanındaki 'earthquake_alerts' koleksiyonunda kullanılan döküman alan adları.
     * Bu sabitler, kod içinde alan adlarını manuel olarak yazarken oluşabilecek hataları önler.
     */
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
}
