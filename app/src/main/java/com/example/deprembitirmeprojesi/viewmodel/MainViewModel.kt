package com.example.deprembitirmeprojesi.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.EarthquakeRecord
import com.example.deprembitirmeprojesi.util.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch

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
    private var statusListener: ListenerRegistration? = null
    
    private val _uiState = MutableLiveData<UiState>(UiState.Safe)
    val uiState: LiveData<UiState> get() = _uiState

    private val _navigateToEmergencyMode = MutableLiveData<Boolean>(false)
    val navigateToEmergencyMode: LiveData<Boolean> get() = _navigateToEmergencyMode

    val earthquakeRecords: LiveData<List<EarthquakeRecord>> = database.earthquakeDao().getAllEarthquakes().asLiveData()

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> get() = _toastMessage

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val resetToSafeRunnable = Runnable { 
        if (_uiState.value is UiState.Analysing) {
            Log.d(TAG, "Zaman aşımı: Analiz durduruldu, güvenli moda dönülüyor.")
            _uiState.postValue(UiState.Safe)
        }
    }

    init {
        startGeneralStatusListener()
    }

    private fun startGeneralStatusListener() {
        statusListener?.remove()
        
        val androidId = android.provider.Settings.Secure.getString(
            getApplication<Application>().contentResolver, 
            android.provider.Settings.Secure.ANDROID_ID
        )
        val baseUid = auth.currentUser?.uid ?: "anon"
        val currentUserId = "${baseUid}_$androidId"

        Log.d(TAG, "Bölgesel Dinleyici Başlatılıyor: $currentUserId")

        // Son 1 dakika içindeki TÜM kayıtları dinleyelim (Bölgesel analiz için)
        statusListener = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS)
            .whereGreaterThan(Constants.FIELD_TIMESTAMP, System.currentTimeMillis() - 30000L) // 30 saniyelik dar pencere
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener
                
                val now = System.currentTimeMillis()
                val docs = snapshots?.documents ?: return@addSnapshotListener

                // 1. Önce kendi durumuma bak (Öncelikli)
                // Sadece son 15 saniye içindeki taze dökümanlarıma bak
                val myDoc = docs.find { 
                    it.getString(Constants.FIELD_USER_ID) == currentUserId &&
                    (it.getLong(Constants.FIELD_TIMESTAMP) ?: 0L) > (now - 15000L)
                }
                
                if (myDoc != null) {
                    val myStatus = myDoc.getString(Constants.FIELD_STATUS)
                    if (myStatus == Constants.STATUS_EARTHQUAKE) {
                        val magnitude = myDoc.getDouble(Constants.FIELD_MAGNITUDE)?.toFloat() ?: 0f
                        val nearby = myDoc.getLong(Constants.FIELD_NEARBY_DEVICES)?.toInt() ?: 0
                        _uiState.postValue(UiState.Confirmed(magnitude, nearby))
                        _navigateToEmergencyMode.postValue(true)
                        timeoutHandler.removeCallbacks(resetToSafeRunnable)
                        return@addSnapshotListener
                    }
                }

                // 2. Yakınlarda başka bir onaylanmış taze deprem var mı? (Erken Uyarı)
                val nearbyConfirmed = docs.find { 
                    it.getString(Constants.FIELD_STATUS) == Constants.STATUS_EARTHQUAKE &&
                    it.getString(Constants.FIELD_USER_ID) != currentUserId &&
                    (it.getLong(Constants.FIELD_TIMESTAMP) ?: 0L) > (now - 20000L) // Son 20 saniye
                }

                if (nearbyConfirmed != null) {
                    // TEST MODU: Bölgesel uyarıyı aktif et
                    val magnitude = nearbyConfirmed.getDouble(Constants.FIELD_MAGNITUDE)?.toFloat() ?: 0f
                    val nearbyCount = nearbyConfirmed.getLong(Constants.FIELD_NEARBY_DEVICES)?.toInt() ?: 1
                    Log.d(TAG, "!!! BÖLGESEL DEPREM UYARISI ALINDI !!!")
                    _uiState.postValue(UiState.Confirmed(magnitude, nearbyCount))
                    _navigateToEmergencyMode.postValue(true)
                    timeoutHandler.removeCallbacks(resetToSafeRunnable)
                } else if (myDoc != null && myDoc.getString(Constants.FIELD_STATUS) == Constants.STATUS_ANALYSING) {
                    _uiState.postValue(UiState.Analysing)
                    restartTimeout()
                } else {
                    if (_uiState.value !is UiState.Safe && _uiState.value !is UiState.Confirmed) {
                        _uiState.postValue(UiState.Safe)
                    }
                }
            }
    }

    private fun restartTimeout() {
        timeoutHandler.removeCallbacks(resetToSafeRunnable)
        timeoutHandler.postDelayed(resetToSafeRunnable, 35000L) // 35 saniye sonra otomatik "Safe"
    }

    fun onEarthquakeDetected(force: Float) {
        // ViewModel artık yerel olarak durumu hemen 'Analysing' yapmıyor.
        // Bunun yerine arka plan servisinin Firebase'e veri yazmasını ve 
        // SnapshotListener'ın tetiklenmesini bekliyoruz. 
        // Bu sayede UI ve Veri tutarlılığı %100 sağlanmış olur.
        Log.d(TAG, "Sarsıntı yerel olarak hissedildi ($force g). Bulut onayı bekleniyor...")
    }

    fun onNavigationToEmergencyModeComplete() {
        _navigateToEmergencyMode.value = false
    }

    override fun onCleared() {
        super.onCleared()
        statusListener?.remove()
        timeoutHandler.removeCallbacks(resetToSafeRunnable)
    }
}
