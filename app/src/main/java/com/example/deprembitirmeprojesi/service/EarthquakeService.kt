package com.example.deprembitirmeprojesi.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.deprembitirmeprojesi.logic.SignalProcessor
import com.example.deprembitirmeprojesi.logic.WaveDetector
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.example.deprembitirmeprojesi.util.Constants
import com.example.deprembitirmeprojesi.util.NotificationHelper
import com.example.deprembitirmeprojesi.worker.AlertCleanupWorker
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.sqrt

class EarthquakeService : Service(), SensorEventListener, NearbyManager.NearbyListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var nearbyManager: NearbyManager? = null
    private lateinit var notificationHelper: NotificationHelper
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private val signalProcessor = SignalProcessor()
    private val waveDetector = WaveDetector(signalProcessor)
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var confirmationListener: ListenerRegistration? = null

    private var isHighPrecisionActive = false
    private val accelBuffer = mutableListOf<Float>()
    private val WINDOW_SIZE = 100 // ~0.5 - 1 saniyelik veri
    private var pWaveDetected = false // EKLENDİ
    
    private var initialGravity: FloatArray? = null
    private var highEnergyStartTime = 0L // Sarsıntının başladığı an
    private val REQUIRED_STRIKE_TIME_MS = 1500L // En az 1.5 saniye sürmeli
    
    private var highPrecisionStartTime = 0L
    private var lastTriggerTime = 0L
    private var lastFirebaseSendTime = 0L
    private var lastCancelTime = 0L 
    private var isFirebaseSent = false // Bu döngüde veri gönderildi mi?
    private val COOLDOWN_MS = 60000L      

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        nearbyManager = NearbyManager(this, this)
        notificationHelper = NotificationHelper(this)
        // Arka plan stabilitesi için applicationContext kullanıyoruz
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
        
        startForegroundService()
        startLowPowerListening()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "EarthquakeService::WakeLock")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startForegroundService() {
        val channelId = "earthquake_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Deprem Takip Servisi", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Deprem Takibi Aktif")
            .setContentText("Cihaz sarsıntılara karşı korunuyor.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasFineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

            if (hasFineLocation || hasCoarseLocation) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }

            try {
                startForeground(1, notification, types)
            } catch (e: Exception) {
                Log.e("QuakeService", "startForeground error: ${e.message}")
                // Fallback: Try without location if permission/state issue
                try {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } catch (e2: Exception) {
                    startForeground(1, notification)
                }
            }
        } else {
            startForeground(1, notification)
        }
    }

    private fun startLowPowerListening() {
        // NORMAL yerine UI kullanarak arka planda daha stabil çalışmasını sağlıyoruz
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        Log.d("QuakeService", "Düşük güç modunda sensör dinleme başlatıldı.")
    }

    private var lastLogTime = 0L
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val linearAccel = signalProcessor.removeGravity(event.values)
        val magnitude = sqrt(linearAccel[0].pow(2) + linearAccel[1].pow(2) + linearAccel[2].pow(2))
        val staltaRatio = signalProcessor.calculateSTALTA(magnitude)

        val currentTime = System.currentTimeMillis()
        
        // İptal sonrası 3 saniye bekle
        if (currentTime - lastCancelTime < 3000) return

        if (currentTime - lastTriggerTime < COOLDOWN_MS) return

        if (!isHighPrecisionActive) {
            // ARTIK SADECE MAGNITUDE DEĞİL, STA/LTA ORANINA BAKIYORUZ
            // Oran > 4.0 demek, sarsıntı ortam gürültüsünün 4 katı demek.
            if (staltaRatio > 4.0f && magnitude > 2.0f) { 
                Log.d("QuakeService", "Sismik Tetiklenme! Oran: $staltaRatio, Mag: $magnitude")
                initialGravity = event.values.clone()
                triggerHighPrecision(magnitude)
            }
        } else {
            // Sliding Window: Eski verileri at, yeniyi ekle
            if (accelBuffer.size >= WINDOW_SIZE) accelBuffer.removeAt(0)
            accelBuffer.add(magnitude)
            
            // Yön Değişimi Kontrolü
            initialGravity?.let { initial ->
                val current = event.values
                val diff = sqrt((current[0]-initial[0]).pow(2) + (current[1]-initial[1]).pow(2) + (current[2]-initial[2]).pow(2))
                
                // Eğer cihaz sarsıntı anında çok fazla döndürülüyorsa (diff > 8.0) İPTAL ET
                if (diff > 8.0f) { 
                    Log.d("QuakeService", "İptal: Cihaz kontrolsüz hareket ediyor (İnsan/Araç hareketi)")
                    lastCancelTime = currentTime
                    stopHighPrecision()
                    return
                }
            }

            analyzeWavePattern(currentTime)

            // 10 saniye boyunca gerçek deprem bulunamazsa analizi durdur
            if (currentTime - highPrecisionStartTime > 10000) {
                stopHighPrecision()
            }
        }
    }

    private fun analyzeWavePattern(now: Long) {
        if (accelBuffer.size < 50) return
        
        val lastMag = accelBuffer.lastOrNull() ?: 0f
        val staltaRatio = signalProcessor.calculateSTALTA(lastMag)

        val pConfidence = waveDetector.detectPWave(accelBuffer, staltaRatio)
        val sConfidence = waveDetector.detectSWave(accelBuffer, staltaRatio, pWaveDetected)

        // P-Wave yakalama
        if (pConfidence > 0.60f && !pWaveDetected) {
            pWaveDetected = true
            Log.d("QuakeService", "P-Wave (Öncü Sarsıntı) Onaylandı!")
        }
        
        // SARSINTI DOĞRULAMA ADIMI 1: Analiz başladıktan 500ms sonra hala stabilsek "ANALYSING" gönder
        val durationSinceStart = now - highPrecisionStartTime
        if (!isFirebaseSent && durationSinceStart > 500) {
            val currentMag = accelBuffer.lastOrNull() ?: 0f
            Log.d("QuakeService", "Sarsıntı stabil devam ediyor, Firebase'e bildiriliyor...")
            processAndSendFirebase(currentMag, 0.5f)
            notificationHelper.sendNotification(currentMag.toDouble())
            isFirebaseSent = true
        }

        if (sConfidence > 0.5f) {
            if (highEnergyStartTime == 0L) highEnergyStartTime = now
            
            val totalDuration = now - highEnergyStartTime
            if (totalDuration >= REQUIRED_STRIKE_TIME_MS) {
                val finalConfidence = if (pWaveDetected) 0.90f else 0.60f
                Log.d("QuakeService", "🚨 SARSINTI KESİNLEŞTİ: Skoru: $finalConfidence")
                
                // S-Dalgası onayı ile durumu güncelle (Burada processAndSend zaten Firebase'e atacak)
                processAndSendFirebase(sConfidence * 10f, finalConfidence)
                sendNearbyAlert()
                lastTriggerTime = now
                stopHighPrecision()
            }
        } else {
            highEnergyStartTime = 0L 
        }
    }

    private fun triggerHighPrecision(initialMagnitude: Float) {
        isHighPrecisionActive = true
        isFirebaseSent = false // Yeni analiz başladı
        highPrecisionStartTime = System.currentTimeMillis()
        highEnergyStartTime = 0L
        accelBuffer.clear()
        
        // Buradan Firebase gönderimini kaldırdık, analyzeWavePattern içinde doğrulanınca gidecek
        
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME) 
    }

    private fun stopHighPrecision() {
        isHighPrecisionActive = false
        isFirebaseSent = false 
        pWaveDetected = false
        highEnergyStartTime = 0L
        accelBuffer.clear()
        sensorManager.unregisterListener(this)
        startLowPowerListening()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun processAndSendFirebase(magnitude: Float, confidence: Float) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFirebaseSendTime < 10000L) return 
        lastFirebaseSendTime = currentTime

        try {
            // ÖNCE: Son bilinen konumu kontrol et (Hızlı ve Güvenli)
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null && (currentTime - lastLoc.time) < 60000) {
                    // Son 1 dakika içindeyse bunu kullan
                    sendToFirebase(magnitude, confidence, lastLoc)
                } else {
                    // Konum eski veya yoksa güncel olanı iste
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { loc -> sendToFirebase(magnitude, confidence, loc) }
                        .addOnFailureListener { e -> 
                            Log.e("QuakeService", "Konum hatası (Current): ${e.message}")
                            sendToFirebase(magnitude, confidence, null) 
                        }
                }
            }.addOnFailureListener { e ->
                Log.e("QuakeService", "Konum hatası (Last): ${e.message}")
                sendToFirebase(magnitude, confidence, null)
            }
        } catch (e: Exception) {
            Log.e("QuakeService", "Location Client Kritik Hata: ${e.message}")
            sendToFirebase(magnitude, confidence, null)
        }
    }

    private fun sendToFirebase(magnitude: Float, confidence: Float, location: Location?) {
        val user = auth.currentUser
        val myId = user?.uid ?: Constants.DUMMY_USER_ID
        
        // ÖNEMLİ: Uygulama kapalıyken Firestore persistence (kalıcılık) kullanır.
        // Verinin gittiğinden emin olmak için döküman referansını alıp doğrudan yazıyoruz.
        val alertData = hashMapOf(
            Constants.FIELD_USER_ID to myId,
            Constants.FIELD_MAGNITUDE to magnitude,
            Constants.FIELD_RISK_SCORE to confidence,
            Constants.FIELD_LATITUDE to (location?.latitude ?: 0.0),
            Constants.FIELD_LONGITUDE to (location?.longitude ?: 0.0),
            Constants.FIELD_TIMESTAMP to System.currentTimeMillis(),
            Constants.FIELD_STATUS to Constants.STATUS_ANALYSING,
            Constants.FIELD_NEARBY_DEVICES to 0
        )

        val collection = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS)
        collection.add(alertData)
            .addOnSuccessListener { docRef ->
                Log.d("QuakeService", "Firebase Verisi Gönderildi (ANALYSING): ${docRef.id}")
                scheduleFirebaseCleanup(docRef.id)
                listenForConfirmation(docRef.id)
                checkForNearbyAlerts(location?.latitude ?: 0.0, location?.longitude ?: 0.0, myId, docRef.id)
            }
            .addOnFailureListener { e ->
                Log.e("QuakeService", "Firebase Hatası: ${e.message}")
            }
    }

    private fun scheduleFirebaseCleanup(fireDocId: String) {
        val request = OneTimeWorkRequestBuilder<AlertCleanupWorker>()
            .setInitialDelay(25, TimeUnit.SECONDS)
            .setInputData(workDataOf("KEY_FIREBASE_DOC_ID" to fireDocId))
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }

    private fun checkForNearbyAlerts(myLat: Double, myLng: Double, myId: String, myDocId: String) {
        val timeLimit = System.currentTimeMillis() - 30000L // Pencereyi 30 saniyeye çıkardık
        firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS)
            .whereGreaterThan(Constants.FIELD_TIMESTAMP, timeLimit)
            .get()
            .addOnSuccessListener { documents ->
                val nearbyDocs = mutableListOf<String>()
                
                for (doc in documents) {
                    // Aynı döküman olmasın yeter
                    if (doc.id != myDocId) {
                        val lat = doc.getDouble(Constants.FIELD_LATITUDE) ?: 0.0
                        val lng = doc.getDouble(Constants.FIELD_LONGITUDE) ?: 0.0
                        val results = FloatArray(1)
                        Location.distanceBetween(myLat, myLng, lat, lng, results)
                        
                        if (results[0] < Constants.DISTANCE_THRESHOLD_METERS) {
                            nearbyDocs.add(doc.id)
                        }
                    }
                }

                Log.d("QuakeService", "Konsensüs Kontrolü: ${nearbyDocs.size} yakın cihaz bulundu.")

                if (nearbyDocs.isNotEmpty()) { 
                    val batch = firestore.batch()
                    val totalAffected = nearbyDocs.size + 1 // Kendisi + Yakındakiler
                    
                    // KENDİ DURUMUMU GÜNCELLE
                    val myRef = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(myDocId)
                    batch.update(myRef, Constants.FIELD_STATUS, Constants.STATUS_EARTHQUAKE)
                    batch.update(myRef, Constants.FIELD_NEARBY_DEVICES, totalAffected)

                    // DİĞER CİHAZLARI DA GÜNCELLE (İlk atan cihazın da sayısı 0 kalmasın)
                    for (otherId in nearbyDocs) {
                        val otherRef = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(otherId)
                        batch.update(otherRef, Constants.FIELD_STATUS, Constants.STATUS_EARTHQUAKE)
                        batch.update(otherRef, Constants.FIELD_NEARBY_DEVICES, totalAffected)
                    }

                    batch.commit().addOnSuccessListener {
                        Log.d("QuakeService", "Konsensüs sağlandı: Tüm kayıtlar DEPREM ($totalAffected cihaz) olarak güncellendi.")
                    }
                }
            }
    }

    private fun listenForConfirmation(documentId: String) {
        confirmationListener?.remove()
        confirmationListener = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(documentId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists() && snapshot.getString(Constants.FIELD_STATUS) == Constants.STATUS_EARTHQUAKE) {
                    val nearby = snapshot.getLong(Constants.FIELD_NEARBY_DEVICES)?.toInt() ?: 0
                    if (nearby > 0) {
                        notificationHelper.sendConfirmedNotification(snapshot.getDouble(Constants.FIELD_MAGNITUDE)?.toFloat() ?: 0f, nearby)
                        confirmationListener?.remove()
                    }
                }
            }
    }

    private fun sendNearbyAlert() {
        val myId = auth.currentUser?.uid ?: com.example.deprembitirmeprojesi.util.Constants.DUMMY_USER_ID
        nearbyManager?.startHybridMode("SHAKE_ALERT_${Build.MODEL}", myId)
        nearbyManager?.broadcastData("SHAKE_ALERT:${System.currentTimeMillis()}")
    }

    override fun onDataReceived(endpointId: String, message: String) {
        if (message.startsWith("SHAKE_ALERT") && !isHighPrecisionActive) {
            triggerHighPrecision(0f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        sensorManager.unregisterListener(this)
    }

    override fun onDeviceFound(endpointId: String, deviceName: String) {}
    override fun onLogMessage(message: String) {}
    override fun onConnectionEstablished(endpointId: String, deviceName: String) {}
    override fun onConnectionLost(endpointId: String) {}
    override fun onConnectionFailed(endpointId: String) {}
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
