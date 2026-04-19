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
import com.example.deprembitirmeprojesi.data.UserProfile
import com.example.deprembitirmeprojesi.databinding.ActivityUserEmergencyBinding
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class UserEmergencyActivity : AppCompatActivity(), NearbyManager.NearbyListener {

    private lateinit var binding: ActivityUserEmergencyBinding
    private lateinit var nearbyManager: NearbyManager
    private lateinit var adapter: ArrayAdapter<String>
    private val logMessages = mutableListOf<String>()

    private val seenMessages = mutableSetOf<String>()
    private var currentFullPayload: String = ""

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    private var toneGenerator: ToneGenerator? = null

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private const val TAG = "UserEmergencyActivity"
    }

    // ------------------ MESH MODEL ------------------

    data class MeshMessage(
        val id: String,
        val from: String,
        val type: String,
        val payload: String,
        val ttl: Int
    )

    private fun MeshMessage.toJson(): String {
        return JSONObject().apply {
            put("id", id)
            put("from", from)
            put("type", type)
            put("payload", payload)
            put("ttl", ttl)
        }.toString()
    }

    private fun parseMessage(json: String): MeshMessage? {
        return try {
            val obj = JSONObject(json)
            MeshMessage(
                id = obj.getString("id"),
                from = obj.getString("from"),
                type = obj.getString("type"),
                payload = obj.getString("payload"),
                ttl = obj.getInt("ttl")
            )
        } catch (e: Exception) {
            null
        }
    }

    // ------------------ LIFECYCLE ------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logMessages)
        binding.listViewMessages.adapter = adapter

        nearbyManager = NearbyManager(this, this)

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

    // ------------------ STATUS UI UPDATE (THREAD SAFE) ------------------

    private fun updateStatusLog(msg: String) {
        Handler(Looper.getMainLooper()).post {
            binding.connectionStatusLog.text = "Durum: $msg"
        }
    }

    private fun updateMainStatus(text: String, colorRes: Int) {
        Handler(Looper.getMainLooper()).post {
            binding.statusTextView.text = text
            binding.statusTextView.setTextColor(ContextCompat.getColor(this, colorRes))
            Log.d(TAG, "UI Updated: $text")
        }
    }

    // ------------------ MESH SEND ------------------

    private fun sendEmergencyMessage(message: String) {
        val meshMsg = MeshMessage(
            id = UUID.randomUUID().toString(),
            from = auth.currentUser?.uid ?: "anon",
            type = "EMERGENCY",
            payload = message,
            ttl = 5
        )

        seenMessages.add(meshMsg.id)
        nearbyManager.broadcastData(meshMsg.toJson())

        addLog("📤 GÖNDERİLDİ: $message")
    }

    // ------------------ RECEIVE ------------------

    override fun onDataReceived(endpointId: String, message: String) {
        val meshMsg = parseMessage(message) ?: return

        if (seenMessages.contains(meshMsg.id)) return
        seenMessages.add(meshMsg.id)

        when (meshMsg.type) {
            "AFAD" -> {
                addLog("🚨 AFAD: ${meshMsg.payload}")
                Handler(Looper.getMainLooper()).post {
                    binding.afadMessageCard.visibility = View.VISIBLE
                    binding.lastAfadMessage.text = meshMsg.payload
                }
            }
            "EMERGENCY" -> {
                addLog("🆘 ${meshMsg.payload}")
                if (meshMsg.ttl > 0) {
                    val forwarded = meshMsg.copy(ttl = meshMsg.ttl - 1)
                    nearbyManager.broadcastData(forwarded.toJson())
                }
            }
            "INFO" -> {
                addLog("📋 BİLGİ ALINDI")
            }
        }
    }

    // ------------------ CONNECTION ------------------

    override fun onConnectionEstablished(endpointId: String, deviceName: String) {
        updateMainStatus("✅ BAĞLANTI KURULDU", android.R.color.holo_green_light)
        updateStatusLog("$deviceName cihazına bağlandınız.")
        addLog("BAĞLANDI: $deviceName")

        if (currentFullPayload.isNotBlank()) {
            val meshMsg = MeshMessage(
                id = UUID.randomUUID().toString(),
                from = auth.currentUser?.uid ?: "anon",
                type = "INFO",
                payload = currentFullPayload,
                ttl = 3
            )

            Handler(Looper.getMainLooper()).postDelayed({
                nearbyManager.sendData(endpointId, meshMsg.toJson())
                addLog("📤 Profil bilgileri gönderildi.")
            }, 1000)
        }
    }

    override fun onConnectionLost(endpointId: String) {
        updateMainStatus("⚠️ BAĞLANTI KOPTU", android.R.color.holo_orange_light)
        updateStatusLog("Bağlantı kesildi, tekrar aranıyor...")
        addLog("KOPTU: $endpointId")
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
        // Log mesajlarını tarayarak UI güncelleme
        if (message.contains("Aktif") || message.contains("Aranıyor") || message.contains("Yayın")) {
            if (binding.statusTextView.text != "✅ BAĞLANTI KURULDU") {
                updateMainStatus("🔍 CİHAZ ARANIYOR", android.R.color.holo_blue_light)
                updateStatusLog("Sinyal yayılıyor ve çevredeki cihazlar taranıyor...")
            }
        }
        addLog("Nearby: $message")
    }

    // ------------------ PAYLOAD ------------------

    @SuppressLint("MissingPermission")
    private fun buildPayload(userProfile: UserProfile?, callback: (String) -> Unit) {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                val locationStr = if (location != null) "${location.latitude}, ${location.longitude}" else "Yok"
                val name = userProfile?.fullName ?: "Bilinmeyen"
                
                // Detaylı Bilgileri Payload'a ekle
                val payload = StringBuilder().apply {
                    append("👤 $name\n")
                    append("📍 $locationStr\n")
                    append("🔋 %$batteryLevel\n")
                    userProfile?.let {
                        if (it.bloodType.isNotEmpty()) append("🩸 Kan: ${it.bloodType}\n")
                        if (it.chronicIllness.isNotEmpty()) append("🏥 Hastalık: ${it.chronicIllness}\n")
                        if (it.birthDate.isNotEmpty()) append("📅 Doğum: ${it.birthDate}\n")
                        if (it.apartmentInfo.isNotEmpty()) append("🏢 Bina: ${it.apartmentInfo}\n")
                        if (it.floorInfo.isNotEmpty()) append("🔢 Kat: ${it.floorInfo}\n")
                        if (it.regularMedication.isNotEmpty()) append("💊 İlaç: ${it.regularMedication}")
                    }
                }.toString()

                callback(payload)
            }
            .addOnFailureListener {
                callback("Bilinmeyen Kullanıcı")
            }
    }

    // ------------------ START ------------------

    private fun startRelayMode() {
        if (!hasAllPermissions()) {
            requestNearbyPermissions()
            return
        }
        updateMainStatus("🔍 HAZIRLANIYOR...", android.R.color.holo_blue_light)
        fetchProfileAndPreparePayload()
    }

    private fun fetchProfileAndPreparePayload() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            buildPayload(null) { startHybridWithPayload(it) }
            return
        }
        firestore.collection("users").document(userId).get()
            .addOnSuccessListener {
                val profile = it.toObject(UserProfile::class.java)
                buildPayload(profile) { startHybridWithPayload(it) }
            }
            .addOnFailureListener {
                buildPayload(null) { startHybridWithPayload(it) }
            }
    }

    private fun startHybridWithPayload(payload: String) {
        currentFullPayload = payload
        nearbyManager.startHybridMode("USER_${UUID.randomUUID().toString().take(4)}")
        addLog("📡 Sinyal başlatıldı.")
        showMessagingUI()
    }

    // ------------------ UI ------------------

    private fun addLog(msg: String) {
        Handler(Looper.getMainLooper()).post {
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
        updateStatusLog("Beklemede")
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
