package com.example.deprembitirmeprojesi.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.example.deprembitirmeprojesi.R
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.deprembitirmeprojesi.logic.EarthquakeDetector
import com.example.deprembitirmeprojesi.logic.SignalProcessor
import com.example.deprembitirmeprojesi.logic.WaveDetector
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.example.deprembitirmeprojesi.util.Constants
import com.example.deprembitirmeprojesi.util.NotificationHelper
import com.example.deprembitirmeprojesi.worker.AlertCleanupWorker
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class EarthquakeService : Service(), SensorEventListener, NearbyManager.NearbyListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    
    private var nearbyManager: NearbyManager? = null
    private lateinit var notificationHelper: NotificationHelper
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private val signalProcessor by lazy { SignalProcessor() }
    private val waveDetector by lazy { WaveDetector(signalProcessor) }
    private val detector by lazy { EarthquakeDetector(this, signalProcessor) }
    
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var isHighPrecisionActive = false
    private val accelBuffer = mutableListOf<Float>()
    private val gyroBuffer = mutableListOf<Float>()
    private val WINDOW_SIZE = 300 // ~6 saniyelik pencere
    
    private var pWaveDetected = false
    private var activeAxesCount = 0
    private var highPrecisionStartTime = 0L
    private var lastTriggerTime = 0L
    private var lastFirebaseSendTime = 0L
    private var lastCancelTime = 0L 
    private var isFirebaseSent = false 
    private var currentActiveDocId: String? = null
    private val COOLDOWN_MS = 15000L // 15 saniye (Eski: 45s)

    private var isForeground = false
    private var lastLocation: Location? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                            status == BatteryManager.BATTERY_STATUS_FULL
            detector.updateChargingStatus(isCharging)
        }
    }

    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        ensureForegroundStatus()
        super.onCreate()
        acquireWakeLock()
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        try {
            nearbyManager = NearbyManager(this, this)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
            setupLocationUpdates()
        } catch (e: Exception) {
            Log.e("QuakeService", "GMS Client hatası: ${e.message}")
        }
        
        notificationHelper = NotificationHelper(this)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        startLowPowerListening()
    }

    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(3000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    lastLocation = location
                    val speedKmh = location.speed * 3.6f
                    detector.updateVehicleStatus(speedKmh, false)
                }
            }
        }
        
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "EarthquakeService::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun ensureForegroundStatus() {
        val channelId = "earthquake_service_channel_v3"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(channelId, "Deprem Koruma Sistemi", NotificationManager.IMPORTANCE_LOW)
                manager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Deprem Koruma Sistemi Aktif")
            .setContentText("Sensörler ve bölgesel veri analizi devrede.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(888, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(888, notification)
        }
        isForeground = true
    }

    private fun startLowPowerListening() {
        // Hızı SENSOR_DELAY_UI seviyesine çekiyoruz, NORMAL deprem sarsıntıları için çok yavaş kalabiliyor.
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when(event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
        }
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val linearAccel = signalProcessor.removeGravity(event.values)
        val magnitude = signalProcessor.calculateMagnitude(linearAccel)
        val staltaRatio = signalProcessor.calculateSTALTA(magnitude)

        val currentTime = System.currentTimeMillis()
        // KİLİT: İptalden sonra 5 saniye, tetiklemeden sonra 15 saniye bekler.
        if (currentTime - lastCancelTime < 5000 || currentTime - lastTriggerTime < COOLDOWN_MS) return

        if (!isHighPrecisionActive) {
            // TEST EDİLEBİLİR VE GÜVENLİ EŞİK: Enerji Oranı 4.0 ve Genlik 1.5
            if (staltaRatio > 4.0f && magnitude > 1.5f) {
                triggerHighPrecision()
            }
        } else {
            if (accelBuffer.size >= WINDOW_SIZE) accelBuffer.removeAt(0)
            accelBuffer.add(magnitude)
            
            activeAxesCount = 0
            if (abs(linearAccel[0]) > 0.2f) activeAxesCount++
            if (abs(linearAccel[1]) > 0.2f) activeAxesCount++
            if (abs(linearAccel[2]) > 0.2f) activeAxesCount++

            analyzeDetailedPattern(currentTime)

            // Gürültü Kontrolü: Sarsıntı çok çabuk sönerse iptal et (Eşiği 0.2'ye çektik)
            if (accelBuffer.size > 40 && accelBuffer.takeLast(15).average() < 0.2) {
                lastCancelTime = currentTime
                stopHighPrecision()
            }

            if (currentTime - highPrecisionStartTime > 20000) {
                lastCancelTime = currentTime
                stopHighPrecision()
            }
        }
    }

    private fun handleGyroscope(event: SensorEvent) {
        if (!isHighPrecisionActive) return
        val rotationMag = sqrt(event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2))
        if (gyroBuffer.size >= WINDOW_SIZE) gyroBuffer.removeAt(0)
        gyroBuffer.add(rotationMag)
        
        // HASSASİYET ARTIRILDI: El sallama sırasında rotasyon 15.0'e kadar çıkabilir (Eski: 25.0)
        if (gyroBuffer.size > 20 && rotationMag > 15.0f) {
             Log.d("QuakeService", "Parazit Filtresi: Aşırı rotasyon ($rotationMag), iptal.")
             lastCancelTime = System.currentTimeMillis()
             stopHighPrecision()
        }
    }

    private fun analyzeDetailedPattern(now: Long) {
        val lastMag = if (accelBuffer.isNotEmpty()) accelBuffer.last() else 0f

        // 1. FIREBASE GÖNDERİMİ HIZLANDIRILDI (400ms -> 300ms)
        if (!isFirebaseSent && (now - highPrecisionStartTime) > 300) {
            isFirebaseSent = true
            // Başlangıç skoru
            val initialConfidence = (lastMag / 5.0f).coerceIn(0.1f, 0.6f)
            Log.d("QuakeService", "Firebase'e ANALYSING durumu fırlatılıyor. Skor: $initialConfidence")
            processAndSendFirebase(lastMag, initialConfidence)
        }

        if (accelBuffer.size < 40) return 
        
        // KRİTİK: Ritmik hareket saptandığında servisi DURDURMUYORUZ.
        // Sadece log basıyoruz, kararı checkConsensusAndScore içindeki dedektöre bırakıyoruz.
        if (signalProcessor.isRhythmic(accelBuffer)) {
            Log.d("QuakeService", "Bilgi: Hareket ritmik saptandı (Muhtemel test veya yürüme).")
        }
        
        // 2. KONSENSÜS KONTROLÜ
        if (accelBuffer.size % 10 == 0) {
            checkConsensusAndScore(now)
        }
    }

    private fun checkConsensusAndScore(now: Long) {
        val timeLimit = now - 45000L // 45 saniyelik pencere
        val currentLoc = lastLocation 
        
        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val baseUid = auth.currentUser?.uid ?: "anon"
        val myUserId = "${baseUid}_$androidId"

        firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS)
            .whereGreaterThan(Constants.FIELD_TIMESTAMP, timeLimit)
            .get()
            .addOnSuccessListener { docs ->
                val uniqueUsers = mutableSetOf<String>()
                
                for (doc in docs) {
                    val userId = doc.getString(Constants.FIELD_USER_ID) ?: ""
                    val status = doc.getString(Constants.FIELD_STATUS) ?: ""
                    val lat = doc.getDouble(Constants.FIELD_LATITUDE) ?: 0.0
                    val lng = doc.getDouble(Constants.FIELD_LONGITUDE) ?: 0.0
                    
                    // Kendi döküman ID'mizi dökümandan güncelleyelim (Henüz callback dönmediyse)
                    if (userId == myUserId) {
                        currentActiveDocId = doc.id
                    }

                    // KONUM TOLERANSI: Eğer konum yoksa (0,0) test için yakın sayalım.
                    val isNear = if (currentLoc == null || (lat == 0.0 && lng == 0.0)) {
                        true 
                    } else {
                        val results = FloatArray(1)
                        Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, lat, lng, results)
                        results[0] < 5000 // 5km
                    }

                    // Sadece analiz edilen veya yeni onaylanan yakındaki farklı cihazları say
                    if (isNear && userId != myUserId && 
                        (status == Constants.STATUS_ANALYSING || status == Constants.STATUS_EARTHQUAKE)) {
                        uniqueUsers.add(userId)
                    }
                }

                val nearbyDeviceCount = uniqueUsers.size
                val hasConsensus = nearbyDeviceCount >= 1 // Ben + en az 1 başka cihaz

                val score = detector.calculateConfidence(accelBuffer, gyroBuffer, activeAxesCount, nearbyDeviceCount, false)

                Log.d("QuakeService", "Konsensüs Sorgusu: ${uniqueUsers.size} adet yakın sarsıntı bulundu.")

                // KRİTİK: Eğer en az 1 kişi daha sallanıyorsa skor ne olursa olsun DEPREM'i onayla
                if (score >= 70 || (hasConsensus && accelBuffer.size >= 10)) {
                    Log.d("QuakeService", "!!! DEPREM ONAYLANDI (Konsensüs Sağlandı) !!!")
                    confirmEarthquake(score.coerceAtLeast(70), nearbyDeviceCount + 1)
                }
            }
    }

    private fun confirmEarthquake(score: Int, totalAffected: Int) {
        notificationHelper.sendConfirmedNotification(score.toFloat() / 10f, totalAffected)
        sendNearbyAlert()
        
        currentActiveDocId?.let { docId ->
            firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(docId)
                .update(
                    Constants.FIELD_STATUS, Constants.STATUS_EARTHQUAKE,
                    Constants.FIELD_NEARBY_DEVICES, totalAffected,
                    Constants.FIELD_RISK_SCORE, score
                ).addOnFailureListener { e ->
                    Log.e("QuakeService", "Deprem Onay Hatası (Yazma Yetkisi?): ${e.message}")
                }
        }

        stopHighPrecision()
        lastTriggerTime = System.currentTimeMillis()
    }

    private fun triggerHighPrecision() {
        isHighPrecisionActive = true
        isFirebaseSent = false 
        currentActiveDocId = null
        highPrecisionStartTime = System.currentTimeMillis()
        accelBuffer.clear()
        gyroBuffer.clear()
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopHighPrecision() {
        isHighPrecisionActive = false
        isFirebaseSent = false 
        pWaveDetected = false
        currentActiveDocId = null // Aktif dökümanı sıfırla
        accelBuffer.clear()
        gyroBuffer.clear()
        sensorManager.unregisterListener(this)
        startLowPowerListening()
    }

    private fun processAndSendFirebase(magnitude: Float, confidence: Float) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                sendToFirebase(magnitude, confidence, loc ?: lastLocation)
            }
        } else {
            sendToFirebase(magnitude, confidence, lastLocation)
        }
    }

    private fun sendToFirebase(magnitude: Float, confidence: Float, location: Location?) {
        // TEST VE ANONİM KULLANICI FİX: Aynı hesapla girilse bile cihazları ayırmak için androidId ekliyoruz.
        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val baseUid = auth.currentUser?.uid ?: "anon"
        val userId = "${baseUid}_$androidId"

        val alertData = hashMapOf(
            Constants.FIELD_USER_ID to userId,
            Constants.FIELD_MAGNITUDE to magnitude,
            Constants.FIELD_RISK_SCORE to confidence,
            Constants.FIELD_LATITUDE to (location?.latitude ?: 0.0),
            Constants.FIELD_LONGITUDE to (location?.longitude ?: 0.0),
            Constants.FIELD_TIMESTAMP to System.currentTimeMillis(),
            Constants.FIELD_STATUS to Constants.STATUS_ANALYSING
        )

        firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).add(alertData)
            .addOnSuccessListener { doc ->
                currentActiveDocId = doc.id
                scheduleFirebaseCleanup(doc.id)
            }
    }

    private fun scheduleFirebaseCleanup(fireDocId: String) {
        val request = OneTimeWorkRequestBuilder<AlertCleanupWorker>()
            .setInitialDelay(50, TimeUnit.SECONDS)
            .setInputData(workDataOf("KEY_FIREBASE_DOC_ID" to fireDocId))
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }

    private fun sendNearbyAlert() {
        nearbyManager?.broadcastData("SHAKE_ALERT:${System.currentTimeMillis()}")
    }

    override fun onDataReceived(endpointId: String, message: String) {
        // MESH ÜZERİNDEN GELEN SARSINTI UYARISI
        if (message.startsWith("SHAKE_ALERT")) {
            Log.d("QuakeService", "Mesh üzerinden DEPREM ONAYI alındı! Yerel onay tetikleniyor.")
            
            // 1. Bildirim gönder
            notificationHelper.sendConfirmedNotification(5.5f, 1)
            
            // 2. Eğer ben sallanmıyorsam bile, şebekeden gelen bu 'kesin' bilgiyle
            // kendi kaydımı bulutta 'DEPREM' olarak güncelle/oluştur.
            // Bu sayede MainViewModel (UI) anında kırmızı moda geçer.
            if (!isHighPrecisionActive) {
                processAndSendFirebase(5.5f, 0.8f) // Sahte sarsıntı ile zorunlu onay gönder
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    confirmEarthquake(85, 2)
                }, 1000)
            } else {
                // Zaten sallanıyorsam süreci hızlandır
                checkConsensusAndScore(System.currentTimeMillis())
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        fusedLocationClient.removeLocationUpdates(locationCallback)
        wakeLock?.let { if (it.isHeld) it.release() }
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDeviceFound(endpointId: String, deviceName: String) {}
    override fun onLogMessage(message: String) {}
    override fun onConnectionEstablished(endpointId: String, deviceName: String) {}
    override fun onConnectionLost(endpointId: String) {}
    override fun onConnectionFailed(endpointId: String) {}
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
