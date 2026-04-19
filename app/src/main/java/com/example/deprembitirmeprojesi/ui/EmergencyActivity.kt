package com.example.deprembitirmeprojesi.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.example.deprembitirmeprojesi.databinding.ActivityEmergencyBinding
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class EmergencyActivity : AppCompatActivity(), NearbyManager.NearbyListener {

    private lateinit var binding: ActivityEmergencyBinding
    private lateinit var nearbyManager: NearbyManager
    private lateinit var adapter: ArrayAdapter<String>
    private val logMessages = mutableListOf<String>()

    private val connectedEndpoints = mutableMapOf<String, String>() // endpointId -> deviceName
    private val endpointToStableId = mutableMapOf<String, String>() // endpointId -> stableUserId (UID)
    private val seenMessages = mutableSetOf<String>()

    private lateinit var auth: FirebaseAuth
    private val database by lazy { AppDatabase.getDatabase(this) }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val TAG = "AFAD"
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

    // ------------------ BLUETOOTH ------------------

    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isBluetoothEnabled()) {
            addLog("Bluetooth açıldı.")
            startMesh()
        } else {
            addLog("HATA: Bluetooth açılmadı.")
        }
    }

    // ------------------ LIFECYCLE ------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logMessages)
        binding.listViewMessages.adapter = adapter

        nearbyManager = NearbyManager(this, this)

        setupClickListeners()
        showInitialUI()
    }

    // ------------------ UI ------------------

    private fun setupClickListeners() {

        binding.btnStartDiscovery.setOnClickListener {
            checkPermissionsAndStart { startMesh() }
        }

        binding.btnReset.setOnClickListener { resetAll() }

        binding.btnSend.setOnClickListener {
            sendAfadMessage(binding.editMessage.text.toString())
        }

        binding.btnLogout.setOnClickListener { logout() }

        binding.btnOpenMap.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java)
            // Sadece şu an bağlı olan cihazların gerçek UID'lerini gönder
            intent.putExtra("CONNECTED_IDS", ArrayList(endpointToStableId.values.distinct()))
            startActivity(intent)
        }

        binding.btnOpenHistory.setOnClickListener {
            startActivity(Intent(this, ReportHistoryActivity::class.java))
        }

        binding.listViewMessages.setOnItemClickListener { _, _, position, _ ->
            val clickedLog = logMessages[position]
            val coords = parseCoords(clickedLog)
            if (coords != null) {
                val intent = Intent(this, MapActivity::class.java)
                intent.putExtra("LAT", coords.first)
                intent.putExtra("LNG", coords.second)
                // Sadece şu an bağlı olan cihazların gerçek UID'lerini gönder
                intent.putExtra("CONNECTED_IDS", ArrayList(endpointToStableId.values.distinct()))
                startActivity(intent)
            }
        }
    }

    private fun parseCoords(text: String): Pair<Double, Double>? {
        return try {
            // Regex ile sayısal koordinatları ara (Örn: 39.123, 32.456)
            val regex = Regex("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)")
            val match = regex.find(text)
            if (match != null) {
                val lat = match.groupValues[1].toDouble()
                val lng = match.groupValues[2].toDouble()
                Pair(lat, lng)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun startMesh() {
        nearbyManager.startHybridMode("AFAD_PERSONEL")
        showMessagingUI()
        addLog("🚨 AFAD mesh başlatıldı")
    }

    // ------------------ SEND ------------------

    private fun sendAfadMessage(message: String) {

        if (message.isBlank()) return

        val meshMsg = MeshMessage(
            id = UUID.randomUUID().toString(),
            from = "AFAD",
            type = "AFAD",
            payload = message,
            ttl = 6
        )

        seenMessages.add(meshMsg.id)
        nearbyManager.broadcastData(meshMsg.toJson())

        addLog("📤 AFAD: $message")
        binding.editMessage.text.clear()
    }

    // ------------------ RECEIVE ------------------

    override fun onDataReceived(endpointId: String, message: String) {

        val meshMsg = parseMessage(message) ?: return

        // LOOP ENGELLE
        if (seenMessages.contains(meshMsg.id)) return
        seenMessages.add(meshMsg.id)

        when (meshMsg.type) {

            "EMERGENCY" -> {
                addLog("🆘 ${meshMsg.payload}")
                endpointToStableId[endpointId] = meshMsg.from
                saveReport(meshMsg.from, meshMsg.payload, isInfo = false)
                
                val coords = parseCoords(meshMsg.payload)
                if (coords != null) {
                    addLog("📍 Konum Tespit Edildi! Haritayı açmak için listedeki mesaja tıklayın.")
                }

                if (meshMsg.ttl > 0) {
                    val forwarded = meshMsg.copy(ttl = meshMsg.ttl - 1)
                    nearbyManager.broadcastData(forwarded.toJson())
                }
            }

            "INFO" -> {
                addLog("📋 Profil Bilgisi Alındı: $endpointId")
                endpointToStableId[endpointId] = meshMsg.from
                saveReport(meshMsg.from, meshMsg.payload, isInfo = true)
            }

            "AFAD" -> {
                addLog("📡 AFAD MESAJI YAYILIYOR")

                if (meshMsg.ttl > 0) {
                    val forwarded = meshMsg.copy(ttl = meshMsg.ttl - 1)
                    nearbyManager.broadcastData(forwarded.toJson())
                }
            }
        }
    }

    // ------------------ DB ------------------

    private fun saveReport(senderId: String, message: String, isInfo: Boolean = false) {
        lifecycleScope.launch {
            val existing = database.reportDao().getReportBySender(senderId)
            val report = if (existing != null) {
                if (isInfo) {
                    // INFO mesajıysa tüm profil bilgilerini güncelle (Emojileri temizleyerek)
                    existing.apply {
                        userProfile = extractValue(message, "👤").replace("👤", "").trim()
                        batteryLevel = extractValue(message, "🔋").replace("🔋", "").trim()
                        lastLocation = extractValue(message, "📍").replace("📍", "").trim()
                        bloodType = extractValue(message, "🩸 Kan:").replace("🩸 Kan:", "").trim()
                        chronicIllness = extractValue(message, "🏥 Hastalık:").replace("🏥 Hastalık:", "").trim()
                        birthDate = extractValue(message, "📅 Doğum:").replace("📅 Doğum:", "").trim()
                        apartmentInfo = extractValue(message, "🏢 Bina:").replace("🏢 Bina:", "").trim()
                        floorInfo = extractValue(message, "🔢 Kat:").replace("🔢 Kat:", "").trim()
                        regularMedication = extractValue(message, "💊 İlaç:").replace("💊 İlaç:", "").trim()
                        
                        lastSeenTimestamp = System.currentTimeMillis()
                        isConnected = true
                    }
                } else {
                    existing.apply {
                        rawMessage = message
                        lastSeenTimestamp = System.currentTimeMillis()
                        isConnected = true
                    }
                }
            } else {
                DisasterReport(
                    senderId = senderId,
                    rawMessage = if (isInfo) "" else message,
                    userProfile = if (isInfo) extractValue(message, "👤").replace("👤", "").trim() else "",
                    batteryLevel = if (isInfo) extractValue(message, "🔋").replace("🔋", "").trim() else "",
                    lastLocation = if (isInfo) extractValue(message, "📍").replace("📍", "").trim() else "",
                    bloodType = if (isInfo) extractValue(message, "🩸 Kan:").replace("🩸 Kan:", "").trim() else "",
                    chronicIllness = if (isInfo) extractValue(message, "🏥 Hastalık:").replace("🏥 Hastalık:", "").trim() else "",
                    birthDate = if (isInfo) extractValue(message, "📅 Doğum:").replace("📅 Doğum:", "").trim() else "",
                    apartmentInfo = if (isInfo) extractValue(message, "🏢 Bina:").replace("🏢 Bina:", "").trim() else "",
                    floorInfo = if (isInfo) extractValue(message, "🔢 Kat:").replace("🔢 Kat:", "").trim() else "",
                    regularMedication = if (isInfo) extractValue(message, "💊 İlaç:").replace("💊 İlaç:", "").trim() else "",
                    lastSeenTimestamp = System.currentTimeMillis(),
                    isConnected = true
                )
            }
            database.reportDao().upsertReport(report)
            addLog("💾 Kayıt Güncellendi: $senderId")
        }
    }

    private fun extractValue(payload: String, key: String) = 
        payload.lines().find { it.contains(key) } ?: ""

    private fun extractProfile(payload: String) = extractValue(payload, "👤")
    private fun extractBattery(payload: String) = extractValue(payload, "🔋")
    private fun extractLocation(payload: String) = extractValue(payload, "📍")

    // ------------------ CONNECTION ------------------

    override fun onConnectionEstablished(endpointId: String, deviceName: String) {
        connectedEndpoints[endpointId] = deviceName
        addLog("✅ Bağlandı: $deviceName")
    }

    override fun onConnectionLost(endpointId: String) {
        val name = connectedEndpoints.remove(endpointId)
        val stableId = endpointToStableId.remove(endpointId)
        addLog("⚠️ Koptu: $name")

        // Bağlantı koptuğunda durumu DB'de güncelle
        stableId?.let { uid ->
            lifecycleScope.launch {
                database.reportDao().getReportBySender(uid)?.let {
                    it.isConnected = false
                    it.lastSeenTimestamp = System.currentTimeMillis()
                    database.reportDao().upsertReport(it)
                }
            }
        }

        runOnUiThread {
            binding.titleTextView.text =
                if (connectedEndpoints.isEmpty()) "SİNYAL ARANIYOR..."
                else "BAĞLI: ${connectedEndpoints.size}"
        }
    }

    override fun onDeviceFound(endpointId: String, deviceName: String) {
        addLog("📍 Bulundu: $deviceName")
    }

    override fun onConnectionFailed(endpointId: String) {
        addLog("❌ Bağlantı hatası")
    }

    override fun onLogMessage(message: String) {
        // Emulator Wifi hatalarını (8029) görmezden gel
        if (isEmulator() && message.contains("8029") && message.contains("NEARBY_WIFI_DEVICES")) return
        addLog("Nearby: $message")
    }

    // ------------------ PERMISSIONS ------------------

    private fun checkPermissionsAndStart(action: () -> Unit) {

        val missingPermissions = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        } else if (!isLocationEnabled()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else if (!isBluetoothEnabled()) {
            requestBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            action.invoke()
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Emulator'da NEARBY_WIFI_DEVICES iznini isteme (çünkü yok)
            if (!isEmulator()) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        return permissions
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun isBluetoothEnabled(): Boolean {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bm.adapter?.isEnabled ?: false
    }

    // ------------------ UI ------------------

    private fun showInitialUI() {
        binding.initialButtonsLayout.visibility = View.VISIBLE
        binding.messagingLayout.visibility = View.GONE
        binding.titleTextView.text = "AFAD PERSONEL"
    }

    private fun showMessagingUI() {
        binding.initialButtonsLayout.visibility = View.GONE
        binding.messagingLayout.visibility = View.VISIBLE
        binding.titleTextView.text = "SİNYAL ARANIYOR..."
    }

    private fun resetAll() {
        nearbyManager.stopAll()
        connectedEndpoints.clear()
        logMessages.clear()
        adapter.notifyDataSetChanged()
        showInitialUI()
        addLog("Sistem sıfırlandı")
    }

    private fun logout() {
        auth.signOut()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun addLog(message: String) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logMessages.add(0, "[$time] $message")
            adapter.notifyDataSetChanged()
        }
    }

    private fun isEmulator(): Boolean =
        Build.PRODUCT.contains("sdk") || Build.MODEL.contains("Emulator")
}
