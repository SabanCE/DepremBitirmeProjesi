package com.example.deprembitirmeprojesi

import android.annotation.SuppressLint
import android.app.Application
import android.location.Geocoder
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Arayüzün farklı durumlarını temsil eden kapalı sınıf (Sealed Class)
sealed class UiState {
    object Safe : UiState() // Güvende durumu
    data class ShakeDetected(val magnitude: Float) : UiState() // Sarsıntı algılandı, teyit bekleniyor
    data class Confirmed(val magnitude: Float, val nearbyDevices: Int) : UiState() // Deprem kesinleşti
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Veritabanı ve servis bağlantıları
    private val database = AppDatabase.getDatabase(application)
    private val firestore = FirebaseFirestore.getInstance()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private var confirmationListener: ListenerRegistration? = null

    // Acil Durum Moduna Geçiş İçin Sinyal
    private val _navigateToEmergencyMode = MutableLiveData<Boolean>()
    val navigateToEmergencyMode: LiveData<Boolean> get() = _navigateToEmergencyMode


    // Activity'nin dinleyeceği canlı veriler (LiveData)
    val earthquakeRecords: LiveData<List<EarthquakeRecord>> = database.earthquakeDao().getAllEarthquakes().asLiveData()
    val lastEarthquake: LiveData<EarthquakeRecord?> = database.earthquakeDao().getLastEarthquake().asLiveData()
    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> get() = _toastMessage

    // Arayüz Durumu için LiveData
    private val _uiState = MutableLiveData<UiState>(UiState.Safe) // Başlangıç durumu: Güvende
    val uiState: LiveData<UiState> get() = _uiState

    override fun onCleared() {
        super.onCleared()
        confirmationListener?.remove()
    }

    // BU KOD SADECE TEST İÇİNDİR!!!
    fun simulateEmergencyMode() {
        _toastMessage.postValue("Simülasyon: İnternet Yok! Acil Durum Moduna Geçiliyor...")
        _navigateToEmergencyMode.postValue(true)
    }

    // Activity, deprem olduğunu bu fonksiyonu çağırarak ViewModel'e bildirir.
    fun onEarthquakeDetected(magnitude: Float) {
        // Sarsıntı algılandığında arayüzü hemen "ShakeDetected" durumuna geçir.
        _uiState.postValue(UiState.ShakeDetected(magnitude))
        processEarthquakeData(magnitude)
    }

    @SuppressLint("MissingPermission")
    private fun processEarthquakeData(magnitude: Float) {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                handleLocationData(magnitude, location)
            }
            .addOnFailureListener { exception ->
                _toastMessage.postValue("❌ Konum alınamadı: ${exception.message}")
                handleLocationData(magnitude, null)
            }
    }

    private fun handleLocationData(magnitude: Float, location: Location?) {
        val context = getApplication<Application>().applicationContext
        val currentTime = System.currentTimeMillis()
        var latitude = 0.0
        var longitude = 0.0
        var addressText = Constants.LOCATION_NOT_AVAILABLE
        var city = Constants.UNKNOWN
        var district = Constants.UNKNOWN

        if (location != null) {
            latitude = location.latitude
            longitude = location.longitude
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val adr = addresses[0]
                    city = adr.adminArea ?: ""
                    district = adr.subAdminArea ?: adr.locality ?: ""
                    addressText = "$city / $district"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                addressText = Constants.ADDRESS_NOT_FOUND
            }
        } else {
            _toastMessage.postValue("Uyarı: Konum bilgisi alınamadı. Teyit başarısız olabilir.")
        }

        val newRecord = EarthquakeRecord(
            timestamp = currentTime,
            magnitude = magnitude,
            type = Constants.EARTHQUAKE_TYPE,
            address = addressText,
            latitude = latitude,
            longitude = longitude
        )

        sendToFirebase(newRecord, city, district)
    }

    private fun sendToFirebase(record: EarthquakeRecord, city: String, dist: String) {
        val sdf = SimpleDateFormat(Constants.DATE_FORMAT, Locale.getDefault())
        val dateStr = sdf.format(Date(record.timestamp))
        val currentUserId = Constants.DUMMY_USER_ID

        val alertData = hashMapOf(
            Constants.FIELD_USER_ID to currentUserId,
            Constants.FIELD_MAGNITUDE to record.magnitude,
            Constants.FIELD_LATITUDE to record.latitude,
            Constants.FIELD_LONGITUDE to record.longitude,
            Constants.FIELD_CITY to city,
            Constants.FIELD_DISTRICT to dist,
            Constants.FIELD_TIMESTAMP to record.timestamp,
            Constants.FIELD_DATETIME to dateStr,
            Constants.FIELD_STATUS to Constants.STATUS_ANALYSING,
            Constants.FIELD_NEARBY_DEVICES to 0
        )

        _toastMessage.postValue("✅ Sinyal Gönderildi. Çevresel teyit aranıyor...")

        firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).add(alertData)
            .addOnSuccessListener { documentReference ->
                listenForConfirmation(documentReference.id, record.magnitude)
                checkForNearbyAlerts(record, currentUserId, documentReference.id)
            }
            .addOnFailureListener {
                _toastMessage.postValue("❌ İnternet Yok! Acil Durum Moduna Geçiliyor...")
                _navigateToEmergencyMode.postValue(true)
            }
    }

    private fun listenForConfirmation(documentId: String, magnitude: Float) {
        val docRef = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(documentId)
        confirmationListener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                _toastMessage.postValue("Dinleme hatası: ${e.message}")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                if (snapshot.getString(Constants.FIELD_STATUS) == Constants.STATUS_EARTHQUAKE) {
                    val nearbyDevices = snapshot.getLong(Constants.FIELD_NEARBY_DEVICES)?.toInt() ?: 0
                    _uiState.postValue(UiState.Confirmed(magnitude, nearbyDevices))
                    confirmationListener?.remove()
                    confirmationListener = null
                }
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun checkForNearbyAlerts(recordToInsert: EarthquakeRecord, currentUserId: String, currentDocumentId: String) {
        val timeThreshold = System.currentTimeMillis() - Constants.TIME_THRESHOLD_MS

        firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS)
            .whereGreaterThan(Constants.FIELD_TIMESTAMP, timeThreshold)
            .get()
            .addOnSuccessListener { documents ->
                val nearbyAlertsToUpdate = mutableListOf<String>()
                var isConfirmed = false

                for (document in documents) {
                    val otherUserId = document.getString(Constants.FIELD_USER_ID) ?: ""
                    if (otherUserId == currentUserId) continue

                    val otherLat = document.getDouble(Constants.FIELD_LATITUDE) ?: 0.0
                    val otherLng = document.getDouble(Constants.FIELD_LONGITUDE) ?: 0.0

                    val distance = calculateDistance(recordToInsert.latitude, recordToInsert.longitude, otherLat, otherLng)
                    if (distance < Constants.DISTANCE_THRESHOLD_METERS && document.getString(Constants.FIELD_STATUS) == Constants.STATUS_ANALYSING) {
                        isConfirmed = true
                        nearbyAlertsToUpdate.add(document.id)
                    }
                }

                if (isConfirmed) {
                    nearbyAlertsToUpdate.add(currentDocumentId)

                    val batch = firestore.batch()
                    for (docId in nearbyAlertsToUpdate) {
                        val docRef = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(docId)
                        batch.update(docRef, Constants.FIELD_STATUS, Constants.STATUS_EARTHQUAKE)
                        batch.update(docRef, Constants.FIELD_NEARBY_DEVICES, nearbyAlertsToUpdate.size - 1)
                    }

                    batch.commit().addOnFailureListener { 
                        _toastMessage.postValue("Durum güncelleme başarısız: ${it.message}")
                    }

                    viewModelScope.launch {
                        database.earthquakeDao().insert(recordToInsert)
                    }
                } else {
                     // Teyit alınamadı, arayüzü tekrar "Güvende" durumuna geçir.
                    _uiState.postValue(UiState.Safe)
                    _toastMessage.postValue("⚠️ Teyit Alınamadı: Yakında başka sinyal yok.")
                }
            }
            .addOnFailureListener { _toastMessage.postValue("Sunucu teyidi alınamadı!") }
    }
}