package com.example.deprembitirmeprojesi.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.EarthquakeRecord
import com.example.deprembitirmeprojesi.util.Constants
import com.example.deprembitirmeprojesi.worker.AlertCleanupWorker
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class UiState {
    object Safe : UiState()
    object Analysing : UiState()
    data class RiskDetected(val score: Double, val level: String) : UiState()
    data class Confirmed(val magnitude: Float, val nearby: Int) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel_Debug"
    private val database = AppDatabase.getDatabase(application)
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private var confirmationListener: ListenerRegistration? = null
    private val workManager = WorkManager.getInstance(application)
    private val notificationHelper = com.example.deprembitirmeprojesi.util.NotificationHelper(application)

    private var lastShakeTimestamp = 0L
    private val SHAKE_COOLDOWN_MS = 60000L // 1 dakika içinde tekrar veri gönderme barajı

    private val _navigateToEmergencyMode = MutableLiveData<Boolean>()
    val navigateToEmergencyMode: LiveData<Boolean> get() = _navigateToEmergencyMode

    val earthquakeRecords: LiveData<List<EarthquakeRecord>> = database.earthquakeDao().getAllEarthquakes().asLiveData()
    
    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> get() = _toastMessage

    private val _uiState = MutableLiveData<UiState>(UiState.Safe)
    val uiState: LiveData<UiState> get() = _uiState

    init {
        _navigateToEmergencyMode.value = false
    }

    override fun onCleared() {
        super.onCleared()
        confirmationListener?.remove()
    }

    fun onNavigationToEmergencyModeComplete() {
        _navigateToEmergencyMode.value = false
    }

    fun onEarthquakeDetected(magnitude: Float) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastShakeTimestamp < SHAKE_COOLDOWN_MS) return
        
        // 5.0f altındaki küçük titreşimleri analiz bile etme (Hassasiyeti düşürdük)
        if (magnitude < 5.0f) {
            Log.d(TAG, "Önemsiz sarsıntı: $magnitude, işlem iptal.")
            return
        }

        lastShakeTimestamp = currentTime
        Log.d(TAG, "Sarsıntı Algılandı! Şiddet: $magnitude")
        
        _uiState.postValue(UiState.Analysing)
        processEarthquakeData(magnitude)
    }

    @SuppressLint("MissingPermission")
    private fun processEarthquakeData(magnitude: Float) {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                handleLocationData(magnitude, location)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Konum hatası: ${e.message}")
                handleLocationData(magnitude, null)
            }
    }

    private fun handleLocationData(magnitude: Float, location: Location?) {
        val context = getApplication<Application>().applicationContext
        val currentTime = System.currentTimeMillis()
        val latitude = location?.latitude ?: 0.0
        val longitude = location?.longitude ?: 0.0
        var addressText = Constants.LOCATION_NOT_AVAILABLE
        var city = Constants.UNKNOWN
        var district = Constants.UNKNOWN

        if (location != null) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val adr = addresses[0]
                    city = adr.adminArea ?: ""
                    district = adr.subAdminArea ?: adr.locality ?: ""
                    addressText = "$city / $district"
                }
            } catch (e: Exception) { 
                addressText = Constants.ADDRESS_NOT_FOUND 
            }
        }

        val tempRecord = EarthquakeRecord(
            timestamp = currentTime,
            magnitude = magnitude,
            status = Constants.STATUS_ANALYSING,
            address = addressText,
            latitude = latitude,
            longitude = longitude
        )

        // Yerel DB'ye yazmıyoruz, sadece Firebase'e gönderiyoruz
        sendToFirebase(tempRecord, city, district)
    }

    private fun sendToFirebase(record: EarthquakeRecord, city: String, dist: String) {
        val sdf = SimpleDateFormat(Constants.DATE_FORMAT, Locale.getDefault())
        val currentUserId = auth.currentUser?.uid ?: Constants.DUMMY_USER_ID

        val alertData = hashMapOf(
            Constants.FIELD_USER_ID to currentUserId,
            Constants.FIELD_MAGNITUDE to record.magnitude,
            Constants.FIELD_LATITUDE to record.latitude,
            Constants.FIELD_LONGITUDE to record.longitude,
            Constants.FIELD_CITY to city,
            Constants.FIELD_DISTRICT to dist,
            Constants.FIELD_TIMESTAMP to record.timestamp,
            Constants.FIELD_DATETIME to sdf.format(Date(record.timestamp)),
            Constants.FIELD_STATUS to Constants.STATUS_ANALYSING,
            Constants.FIELD_NEARBY_DEVICES to 0,
            "address_text" to record.address
        )

        firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).add(alertData)
            .addOnSuccessListener { docRef ->
                Log.d(TAG, "✅ Geçici sinyal Firebase'e yazıldı. ID: ${docRef.id}")
                
                // CleanupWorker'a sadece Firebase Doc ID gönderiyoruz
                scheduleFirebaseCleanup(docRef.id)
                
                listenForConfirmation(docRef.id)
                checkForNearbyAlerts(record, currentUserId, docRef.id)
            }
    }

    private fun listenForConfirmation(documentId: String) {
        confirmationListener?.remove()
        val docRef = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(documentId)
        confirmationListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Listen error: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val status = snapshot.getString(Constants.FIELD_STATUS)
                Log.d(TAG, "Firebase Update: Status = $status")
                
                if (status == Constants.STATUS_EARTHQUAKE) {
                    Log.d(TAG, "!!! DEPREM ONAYLANDI !!! Yerel DB'ye kaydediliyor.")
                    
                    val magnitude = snapshot.getDouble(Constants.FIELD_MAGNITUDE)?.toFloat() ?: 0f
                    val nearby = snapshot.getLong(Constants.FIELD_NEARBY_DEVICES)?.toInt() ?: 0
                    val timestamp = snapshot.getLong(Constants.FIELD_TIMESTAMP) ?: System.currentTimeMillis()
                    val lat = snapshot.getDouble(Constants.FIELD_LATITUDE) ?: 0.0
                    val lng = snapshot.getDouble(Constants.FIELD_LONGITUDE) ?: 0.0
                    val address = snapshot.getString("address_text") ?: Constants.UNKNOWN

                    viewModelScope.launch {
                        val confirmedRecord = EarthquakeRecord(
                            timestamp = timestamp,
                            magnitude = magnitude,
                            status = Constants.STATUS_EARTHQUAKE,
                            address = address,
                            latitude = lat,
                            longitude = lng
                        )
                        database.earthquakeDao().insert(confirmedRecord)
                    }

                    _uiState.postValue(UiState.Confirmed(magnitude, nearby))
                    notificationHelper.sendConfirmedNotification(magnitude, nearby)
                    _navigateToEmergencyMode.postValue(true)
                    
                    confirmationListener?.remove()
                }
            } else {
                Log.d(TAG, "Analiz verisi silindi veya bulunamadı. Durum: Güvende")
                _uiState.postValue(UiState.Safe)
                confirmationListener?.remove()
            }
        }
    }

    private fun checkForNearbyAlerts(myRecord: EarthquakeRecord, myId: String, myDocId: String) {
        val timeLimit = System.currentTimeMillis() - Constants.TIME_THRESHOLD_MS

        firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS)
            .whereGreaterThan(Constants.FIELD_TIMESTAMP, timeLimit)
            .get()
            .addOnSuccessListener { documents ->
                val nearbyDocs = mutableListOf<String>()
                
                for (doc in documents) {
                    val otherId = doc.getString(Constants.FIELD_USER_ID) ?: ""
                    if (otherId == myId) continue 

                    val lat = doc.getDouble(Constants.FIELD_LATITUDE) ?: 0.0
                    val lng = doc.getDouble(Constants.FIELD_LONGITUDE) ?: 0.0
                    
                    val results = FloatArray(1)
                    Location.distanceBetween(myRecord.latitude, myRecord.longitude, lat, lng, results)
                    
                    if (results[0] < Constants.DISTANCE_THRESHOLD_METERS) {
                        nearbyDocs.add(doc.id)
                    }
                }

                if (nearbyDocs.isNotEmpty()) {
                    Log.d(TAG, "Mesafe teyidi başarılı. Status güncelleniyor.")
                    nearbyDocs.add(myDocId)
                    val batch = firestore.batch()
                    nearbyDocs.forEach { id ->
                        val ref = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(id)
                        batch.update(ref, Constants.FIELD_STATUS, Constants.STATUS_EARTHQUAKE)
                        batch.update(ref, Constants.FIELD_NEARBY_DEVICES, nearbyDocs.size - 1)
                    }
                    batch.commit()
                } else {
                    Log.d(TAG, "Teyit henüz yok.")
                }
            }
    }

    private fun scheduleFirebaseCleanup(fireDocId: String) {
        val request = OneTimeWorkRequestBuilder<AlertCleanupWorker>()
            .setInitialDelay(45, TimeUnit.SECONDS)
            .setInputData(workDataOf("KEY_FIREBASE_DOC_ID" to fireDocId))
            .build()
        workManager.enqueue(request)
    }
}
