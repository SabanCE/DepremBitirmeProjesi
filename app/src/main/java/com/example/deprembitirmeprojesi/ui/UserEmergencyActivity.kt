package com.example.deprembitirmeprojesi.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.example.deprembitirmeprojesi.data.UserProfile
import com.example.deprembitirmeprojesi.databinding.ActivityUserEmergencyBinding
import com.example.deprembitirmeprojesi.mesh.MeshNetworkManager
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class UserEmergencyActivity : AppCompatActivity(), NearbyManager.NearbyListener {

    private lateinit var binding: ActivityUserEmergencyBinding
    private lateinit var nearbyManager: NearbyManager
    private lateinit var meshManager: MeshNetworkManager
    
    private lateinit var adapter: ArrayAdapter<String>
    private val logMessages = mutableListOf<String>()

    private var currentProfile: UserProfile? = null
    private var lastLocationStr: String = ""

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private val database by lazy { AppDatabase.getDatabase(this) }

    private var toneGenerator: ToneGenerator? = null

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private const val TAG = "UserEmergencyActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logMessages)
        binding.listViewMessages.adapter = adapter

        nearbyManager = NearbyManager(this, this)
        // MeshNetworkManager'ı başlatıyoruz
        meshManager = MeshNetworkManager.getInstance(this, nearbyManager)
        
        // BAŞLANGIÇ TEMİZLİĞİ: Herkes "KOPTU" başlasın
        meshManager.stopAll()

        setupClickListeners()
        showInitialUI()
    }

    private fun setupClickListeners() {
        binding.btnStartAdvertising.setOnClickListener { startRelayMode() }
        binding.btnReset.setOnClickListener { resetAll() }

        binding.btnGood.setOnClickListener { sendEmergencyMessage("İYİYİM") }
        binding.btnInjured.setOnClickListener { sendEmergencyMessage("YARALIYIM") }
        binding.btnWater.setOnClickListener { sendEmergencyMessage("SU LAZIM") }
        binding.btnStuck.setOnClickListener { sendEmergencyMessage("ENKAZ ALTINDAYIM") }

        binding.btnShowCustomMessage.setOnClickListener {
            binding.customMessagePanel.visibility = View.VISIBLE
            binding.quickMessagesLayout.visibility = View.GONE
        }

        binding.btnCancelCustomMessage.setOnClickListener {
            binding.customMessagePanel.visibility = View.GONE
            binding.quickMessagesLayout.visibility = View.VISIBLE
        }

        binding.btnSend.setOnClickListener {
            val msg = binding.editMessage.text.toString()
            if (msg.isNotBlank()) {
                sendEmergencyMessage(msg)
                binding.editMessage.text.clear()
                binding.customMessagePanel.visibility = View.GONE
                binding.quickMessagesLayout.visibility = View.VISIBLE
            }
        }

        binding.btnAlarm.setOnClickListener { playEmergencyAlarm() }
    }

    private fun updateStatusLog(msg: String) {
        runOnUiThread {
            binding.connectionStatusLog.text = "Durum: $msg"
        }
    }

    private fun updateMainStatus(text: String, colorRes: Int) {
        runOnUiThread {
            binding.statusTextView.text = text
            binding.statusTextView.setTextColor(ContextCompat.getColor(this, colorRes))
        }
    }

    private fun sendEmergencyMessage(message: String) {
        val userId = auth.currentUser?.uid ?: "anon_${UUID.randomUUID().toString().take(4)}"
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 1. Önce mevcut raporu çek (Versiyonu kaybetmemek için)
            var report = database.reportDao().getReportBySender(userId)
            
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            if (report == null) {
                report = DisasterReport(
                    senderId = userId,
                    rawMessage = message,
                    userProfile = currentProfile?.fullName ?: "Bilinmeyen Kullanıcı",
                    batteryLevel = "%$batteryLevel",
                    lastLocation = lastLocationStr,
                    bloodType = currentProfile?.bloodType ?: "",
                    chronicIllness = currentProfile?.chronicIllness ?: "",
                    regularMedication = currentProfile?.regularMedication ?: "",
                    birthDate = currentProfile?.birthDate ?: "",
                    apartmentInfo = currentProfile?.apartmentInfo ?: "",
                    floorInfo = currentProfile?.floorInfo ?: "",
                    role = "VICTIM",
                    status = "PENDING",
                    lastSeenTimestamp = System.currentTimeMillis(),
                    isConnected = true,
                    version = 1
                )
            } else {
                // 2. Mevcut raporu güncelle
                report.rawMessage = message
                report.lastLocation = lastLocationStr
                report.batteryLevel = "%$batteryLevel"
                report.lastSeenTimestamp = System.currentTimeMillis()
                report.isConnected = true
                // Versiyonu burada artırıyoruz ki ağdaki diğer cihazlar güncellemeyi kabul etsin
                report.version++
            }

            // 3. Mesh Sistemi üzerinden gönder
            meshManager.updateAndBroadcastStatus(report)
            
            runOnUiThread {
                addLog("📤 GÖNDERİLDİ: $message")
            }
        }
    }

    override fun onDataReceived(endpointId: String, message: String) {
        // Mesh verisini işle
        meshManager.onDataReceived(endpointId, message)
        
        // AFAD'dan gelen özel durum güncellemelerini takip et
        lifecycleScope.launch {
            val myId = auth.currentUser?.uid ?: return@launch
            val myReport = database.reportDao().getReportBySender(myId)
            
            if (myReport != null) {
                runOnUiThread {
                    if (myReport.status != "PENDING") {
                        binding.afadMessageCard.visibility = View.VISIBLE
                        val statusLabel = when(myReport.status) {
                            "CLAIMED" -> "🚑 Ekip Yolda!"
                            "RESCUING" -> "👷 Müdahale Ediliyor!"
                            "RESCUED" -> "✅ Kurtarıldınız!"
                            else -> "⏳ Yardım Bekleniyor"
                        }
                        binding.lastAfadMessage.text = "DURUM GÜNCELLEMESİ: $statusLabel"
                    }
                }
            }
        }
    }

    override fun onConnectionEstablished(endpointId: String, deviceName: String) {
        // İsim ayrıştırma: "ID|NAME" formatından ismi al
        val displayName = if (deviceName.contains("|")) deviceName.split("|")[1] else deviceName
        
        updateMainStatus("✅ BAĞLANTI KURULDU", android.R.color.holo_green_light)
        updateStatusLog("$displayName cihazına bağlandınız.")
        addLog("BAĞLANDI: $displayName")
        meshManager.onConnectionEstablished(endpointId, deviceName)
        
        // Bağlantı kurulunca güncel durumunu bir kez fırlat
        sendEmergencyMessage("SİSTEME BAĞLANDI")
    }

    override fun onConnectionLost(endpointId: String) {
        updateMainStatus("⚠️ BAĞLANTI KOPTU", android.R.color.holo_orange_light)
        updateStatusLog("Bağlantı kesildi, tekrar aranıyor...")
        addLog("KOPTU: $endpointId")
        
        // KRİTİK: MeshManager'a haber ver ki "KOPTU" işaretlesin
        meshManager.onConnectionLost(endpointId)
    }

    override fun onConnectionFailed(endpointId: String) {
        updateStatusLog("Bağlantı hatası oluştu.")
        addLog("BAĞLANTI HATASI")
    }

    override fun onDeviceFound(endpointId: String, deviceName: String) {
        updateStatusLog("$deviceName bulundu, bağlanılıyor...")
        addLog("BULUNDU: $deviceName")
    }

    override fun onLogMessage(message: String) {
        if (binding.statusTextView.text != "✅ BAĞLANTI KURULDU") {
            updateMainStatus("🔍 CİHAZ ARANIYOR", android.R.color.holo_blue_light)
        }
        addLog("Nearby: $message")
    }

    private fun startRelayMode() {
        if (!hasAllPermissions()) {
            requestNearbyPermissions()
            return
        }
        updateMainStatus("🔍 HAZIRLANIYOR...", android.R.color.holo_blue_light)
        
        // Profili ve konumu alıp başlat
        val userId = auth.currentUser?.uid
        if (userId != null) {
            firestore.collection("users").document(userId).get()
                .addOnSuccessListener {
                    currentProfile = it.toObject(UserProfile::class.java)
                    updateLocationAndStart()
                }
                .addOnFailureListener { updateLocationAndStart() }
        } else {
            updateLocationAndStart()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationAndStart() {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                // KONUM FİLTRESİ: Eğer koordinatlar (0.0, 0.0) veya emülatörün varsayılanı (37.422...) ise gerçek konum gelene kadar boş bırak
                lastLocationStr = if (location != null && !isDefaultEmulatorLocation(location)) {
                    "${location.latitude}, ${location.longitude}"
                } else {
                    "" 
                }
                
                val userId = auth.currentUser?.uid ?: "anon_${UUID.randomUUID().toString().take(4)}"
                val displayName = currentProfile?.fullName ?: "Depremzede"
                nearbyManager.startHybridMode(displayName, userId)
                showMessagingUI()
            }
    }

    private fun isDefaultEmulatorLocation(loc: Location): Boolean {
        // San Francisco koordinatları (Emülatör varsayılanı)
        return loc.latitude > 37.42 && loc.latitude < 37.43 && loc.longitude < -122.08 && loc.longitude > -122.09
    }

    private fun addLog(msg: String) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logMessages.add(0, "[$time] $msg")
            adapter.notifyDataSetChanged()
        }
    }

    private fun showInitialUI() {
        binding.initialButtonsLayout.visibility = View.VISIBLE
        binding.quickMessagesLayout.visibility = View.GONE
        binding.btnReset.visibility = View.GONE
        updateMainStatus("SİNYAL KAPALI", android.R.color.holo_red_light)
    }

    private fun showMessagingUI() {
        binding.initialButtonsLayout.visibility = View.GONE
        binding.quickMessagesLayout.visibility = View.VISIBLE
        binding.btnReset.visibility = View.VISIBLE
    }

    private fun resetAll() {
        nearbyManager.stopAll()
        logMessages.clear()
        adapter.notifyDataSetChanged()
        showInitialUI()
    }

    private fun hasAllPermissions(): Boolean = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions
    }

    private fun requestNearbyPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions().toTypedArray(), REQUEST_CODE_PERMISSIONS)
    }

    private fun playEmergencyAlarm() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Alarm error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyManager.stopAll()
        toneGenerator?.release()
    }
}
