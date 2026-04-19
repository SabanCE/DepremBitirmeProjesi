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
import com.example.deprembitirmeprojesi.mesh.MeshNetworkManager
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class EmergencyActivity : AppCompatActivity(), NearbyManager.NearbyListener, MeshNetworkManager.MeshMessageListener {

    private lateinit var binding: ActivityEmergencyBinding
    private lateinit var nearbyManager: NearbyManager
    private lateinit var meshManager: MeshNetworkManager
    
    private lateinit var adapter: ArrayAdapter<String>
    private val logMessages = mutableListOf<String>()

    private val connectedEndpoints = mutableMapOf<String, String>() // endpointId -> deviceName
    private val endpointToStableId = mutableMapOf<String, String>() // endpointId -> stableUserId (UID)

    private lateinit var auth: FirebaseAuth
    private val database by lazy { AppDatabase.getDatabase(this) }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val TAG = "AFAD"
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logMessages)
        binding.listViewMessages.adapter = adapter

        nearbyManager = NearbyManager(this, this)
        // MeshNetworkManager'ı başlatıyoruz
        meshManager = MeshNetworkManager.getInstance(this, nearbyManager)
        meshManager.setMessageListener(this)
        
        // BAŞLANGIÇ TEMİZLİĞİ: Uygulama açıldığında her şey "KOPTU" başlasın
        meshManager.stopAll()

        setupClickListeners()
        showInitialUI()
    }

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
                intent.putExtra("CONNECTED_IDS", ArrayList(endpointToStableId.values.distinct()))
                startActivity(intent)
            }
        }
    }

    private fun parseCoords(text: String): Pair<Double, Double>? {
        return try {
            val regex = Regex("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)")
            val match = regex.find(text)
            if (match != null) {
                val lat = match.groupValues[1].toDouble()
                val lng = match.groupValues[2].toDouble()
                Pair(lat, lng)
            } else null
        } catch (e: Exception) { null }
    }

    private fun startMesh() {
        val myId = auth.currentUser?.uid ?: "AFAD_${UUID.randomUUID().toString().take(4)}"
        nearbyManager.startHybridMode("AFAD_PERSONEL", myId)
        showMessagingUI()
        addLog("🚨 AFAD mesh başlatıldı")
    }

    private fun sendAfadMessage(message: String) {
        if (message.isBlank()) return
        
        // Yeni Mesh Sistemi üzerinden gönder (Kritik!)
        val report = DisasterReport(
            senderId = "AFAD_${auth.uid}",
            rawMessage = message,
            role = "AFAD",
            status = "INFO",
            lastSeenTimestamp = System.currentTimeMillis()
        )
        meshManager.updateAndBroadcastStatus(report)

        addLog("📤 AFAD: $message")
        binding.editMessage.text.clear()
    }

    override fun onDataReceived(endpointId: String, message: String) {
        // Tüm veri işleme işini MeshManager'a devret
        meshManager.onDataReceived(endpointId, message)
    }

    override fun onMessageReceived(userName: String, message: String) {
        addLog("🆘 SOS ($userName): $message")
    }

    private fun updateTitle() {
        runOnUiThread {
            val count = connectedEndpoints.size
            binding.titleTextView.text = if (count == 0) "SİNYAL ARANIYOR..." else "SİNYAL ARANIYOR... (BAĞLI: $count)"
        }
    }

    override fun onConnectionEstablished(endpointId: String, deviceName: String) {
        // İsim ayrıştırma: "ID|NAME" formatından ismi al
        val displayName = if (deviceName.contains("|")) deviceName.split("|")[1] else deviceName
        
        connectedEndpoints[endpointId] = displayName
        addLog("✅ Bağlandı: $displayName")
        updateTitle()
        
        meshManager.onConnectionEstablished(endpointId, deviceName)
    }

    override fun onConnectionLost(endpointId: String) {
        val name = connectedEndpoints.remove(endpointId)
        if (name != null) {
            addLog("⚠️ Koptu: $name")
        }
        
        // KRİTİK: MeshManager'a haber ver ki veritabanını "KOPTU" yapsın
        meshManager.onConnectionLost(endpointId)

        updateTitle()
    }

    override fun onDeviceFound(endpointId: String, deviceName: String) {
        addLog("📍 Bulundu: $deviceName")
    }

    override fun onConnectionFailed(endpointId: String) {
        addLog("❌ Bağlantı hatası")
    }

    override fun onLogMessage(message: String) {
        if (isEmulator() && message.contains("8029")) return
        addLog("Nearby: $message")
    }

    private fun checkPermissionsAndStart(action: () -> Unit) {
        val missingPermissions = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isEmulator()) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
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
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun addLog(message: String) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logMessages.add(0, "[$time] $message")
            adapter.notifyDataSetChanged()
        }
    }

    private fun isEmulator(): Boolean = Build.PRODUCT.contains("sdk") || Build.MODEL.contains("Emulator")
}
