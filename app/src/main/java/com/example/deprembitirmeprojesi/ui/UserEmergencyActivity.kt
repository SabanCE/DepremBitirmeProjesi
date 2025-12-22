package com.example.deprembitirmeprojesi.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UserEmergencyActivity : AppCompatActivity(), NearbyManager.NearbyListener {

    private lateinit var binding: ActivityUserEmergencyBinding
    private lateinit var nearbyManager: NearbyManager
    private lateinit var adapter: ArrayAdapter<String>
    private val logMessages = mutableListOf<String>()

    private var connectedEndpointId: String? = null
    private var currentFullPayload: String = ""
    private var pendingAction: (() -> Unit)? = null

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private const val TAG = "UserEmergencyActivity"
        private const val RESTART_DELAY_MS = 3000L
    }

    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            addLog("DEBUG: Bluetooth açıldı.")
            pendingAction?.let { startNearbyProcess(it) }
        } else {
            addLog("UYARI: Bluetooth açma isteği reddedildi.")
            Toast.makeText(this, "Bluetooth'un açılması gerekli!", Toast.LENGTH_SHORT).show()
            pendingAction = null
        }
    }

    private val requestLocationSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        addLog("DEBUG: Konum ayarlarından dönüldü.")
        pendingAction?.let { startNearbyProcess(it) }
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

        setupClickListeners()
        showInitialUI()
    }

    private fun setupClickListeners() {
        binding.btnStartAdvertising.setOnClickListener { startAdvertisingMode() }
        binding.btnReset.setOnClickListener { resetAll() }

        binding.btnGood.setOnClickListener { sendMessage("DURUM: İYİYİM") }
        binding.btnInjured.setOnClickListener { sendMessage("DURUM: YARALIYIM") }
        binding.btnWater.setOnClickListener { sendMessage("DURUM: SU LAZIM") }
        binding.btnStuck.setOnClickListener { sendMessage("DURUM: ENKAZ ALTINDAYIM") }

        binding.btnSend.setOnClickListener {
            val msg = binding.editMessage.text.toString()
            if (msg.isNotBlank()) {
                sendMessage("MESAJ: $msg")
                binding.editMessage.text.clear()
            }
        }

        binding.btnShowCustomMessage.setOnClickListener { showCustomMessagePanel(true) }
        binding.btnCancelCustomMessage.setOnClickListener { showCustomMessagePanel(false) }
    }

    private fun startAdvertisingMode() {
        startNearbyProcess {
            fetchProfileAndPreparePayload()
        }
    }

    private fun fetchProfileAndPreparePayload() {
        addLog("Veriler toplanıyor...")
        val userId = auth.currentUser?.uid

        if (userId == null) {
            addLog("UYARI: Kullanıcı girişi yapılmamış. Sadece cihaz bilgileri gönderilecek.")
            buildPayload(null) { payload -> startAdvertisingWithPayload(payload) }
            return
        }

        firestore.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val userProfile = try { document.toObject(UserProfile::class.java) } catch (e: Exception) { null }
                buildPayload(userProfile) { payload -> startAdvertisingWithPayload(payload) }
            }
            .addOnFailureListener {
                addLog("HATA: Profil alınamadı, temel verilerle devam ediliyor.")
                buildPayload(null) { payload -> startAdvertisingWithPayload(payload) }
            }
    }

    private fun startAdvertisingWithPayload(fullPayload: String) {
        currentFullPayload = fullPayload
        val shortName = "YARDIM_${auth.currentUser?.uid?.take(4)?.uppercase() ?: "GUEST"}"
        nearbyManager.startAdvertising(shortName)
        showMessagingUI()
    }

    @SuppressLint("MissingPermission")
    private fun buildPayload(userProfile: UserProfile?, callback: (String) -> Unit) {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        var signalStrengthLevel = -1
        var wifiRssi = -128

        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                signalStrengthLevel = telephonyManager.signalStrength?.level ?: -1
            }
        } catch (e: SecurityException) {
            addLog("HATA: Sinyal gücü için izin yok: ${e.message}")
        } catch (e: Exception) {
            addLog("HATA: Sinyal gücü alınamadı: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiManager.isWifiEnabled) {
                @Suppress("DEPRECATION")
                val connectionInfo = wifiManager.connectionInfo
                if (connectionInfo != null && connectionInfo.networkId != -1) {
                    wifiRssi = connectionInfo.rssi
                }
            }
        } catch (e: Exception) {
            addLog("HATA: Wi-Fi gücü alınamadı: ${e.message}")
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                formatPayload(userProfile, batteryLevel, signalStrengthLevel, wifiRssi, location, callback)
            }
            .addOnFailureListener { e ->
                addLog("HATA: Konum bilgisi alınamadı: ${e.message}")
                formatPayload(userProfile, batteryLevel, signalStrengthLevel, wifiRssi, null, callback)
            }
    }

    private fun formatPayload(userProfile: UserProfile?, batteryLevel: Int, signalLevel: Int, wifiRssi: Int, location: Location?, callback: (String) -> Unit) {
        val locationStr = if (location != null) "${location.latitude}, ${location.longitude}" else "Konum Bilgisi Yok"
        val signalStr = if (signalLevel != -1) "Sinyal Gücü: $signalLevel/4" else "Sinyal Gücü: Bilinmiyor"
        val wifiStr = if (wifiRssi > -128) "Wi-Fi Gücü: $wifiRssi dBm" else "Wi-Fi: Bağlı Değil"

        val profileInfo = StringBuilder().apply {
            userProfile?.let {
                append("Ad Soyad: ${it.fullName}\n")
                if (it.tckn.isNotBlank()) append("TCKN: ${it.tckn}\n")
                if (it.birthDate.isNotBlank()) append("Doğum Tarihi: ${it.birthDate}\n")
                if (it.bloodType.isNotBlank()) append("Kan Grubu: ${it.bloodType}\n")
            }
        }.toString().trim()

        val finalPayload = """
        ${profileInfo}
        ---
        Batarya: $batteryLevel%
        $signalStr
        $wifiStr
        Konum: $locationStr
        """.trimIndent()

        callback(finalPayload)
    }

    private fun sendMessage(message: String) {
        if (connectedEndpointId != null) {
            nearbyManager.sendData(connectedEndpointId!!, message)
            addLog("GÖNDERİLEN: $message")
        } else {
            addLog("HATA: Mesaj göndermek için bir cihaza bağlı olmalısınız.")
        }
    }

    override fun onLogMessage(message: String) = addLog("DURUM: $message")

    override fun onConnectionEstablished(endpointId: String) {
        connectedEndpointId = endpointId
        addLog("✅ BAĞLANTI KURULDU! Mesajlaşma Başlıyor...")

        // KRİTİK DÜZELTME: stopAll() YERİNE stopAdvertisingOnly()
        // Artık yeni kişi aramıyoruz ama mevcut kişiyle konuşmaya devam ediyoruz.
        nearbyManager.stopAdvertisingOnly()

        // Eğer elinde hazır büyük veri varsa gönder
        if (currentFullPayload.isNotBlank()) {
            nearbyManager.sendData(endpointId, currentFullPayload)
        }

        // Arayüzü Mesajlaşma Moduna Al (UI Güncellemesi)
        runOnUiThread {
            showMessagingUI()
            binding.statusTextView.text = "PERSONEL İLE BAĞLI"
        }
    }

    override fun onConnectionLost(endpointId: String) {
        connectedEndpointId = null
        addLog("⚠️ Bağlantı koptu. Tekrar yayın yapılıyor...")
        // Tekrar "Beni Bulun" demeye başla
        startAdvertisingMode()
    }

    override fun onConnectionFailed(endpointId: String) {
        addLog("Bağlantı denemesi başarısız oldu: $endpointId. Yayın devam ediyor.")
    }

    override fun onDeviceFound(endpointId: String, deviceName: String) { }

    override fun onDataReceived(endpointId: String, message: String) {
        addLog("AFAD: $message")
    }

    private fun addLog(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            logMessages.add(0, "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} - $msg")
            adapter.notifyDataSetChanged()
        }
    }

    private fun resetAll() {
        nearbyManager.stopAll()
        logMessages.clear()
        adapter.notifyDataSetChanged()
        addLog("RESET: Tüm işlemler durduruldu.")
        showInitialUI()
    }

    private fun showInitialUI() {
        binding.initialButtonsLayout.visibility = View.VISIBLE
        binding.quickMessagesLayout.visibility = View.GONE
        binding.customMessagePanel.visibility = View.GONE
        binding.statusTextView.text = "Yardım sinyali yaymak için butona basın."
    }

    private fun showMessagingUI() {
        binding.initialButtonsLayout.visibility = View.GONE
        binding.quickMessagesLayout.visibility = View.VISIBLE
        binding.statusTextView.text = "YAYIN YAPILIYOR..."
    }

    private fun showCustomMessagePanel(show: Boolean) {
        binding.quickMessagesLayout.visibility = if (show) View.GONE else View.VISIBLE
        binding.customMessagePanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun startNearbyProcess(action: () -> Unit) {
        pendingAction = action
        if (isEmulator()) {
            addLog("EMULATOR: Kontroller atlanıyor.")
            pendingAction?.invoke()
            pendingAction = null
            return
        }
        when {
            !hasAllPermissions() -> requestNearbyPermissions()
            !isLocationEnabled() -> showLocationSettings()
            !isBluetoothEnabled() -> requestBluetoothEnable()
            else -> {
                pendingAction?.invoke()
                pendingAction = null
            }
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    private fun hasAllPermissions(): Boolean = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getRequiredPermissions(): List<String> {
        return mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addAll(arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                ))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }

    private fun requestNearbyPermissions() {
        val permissionsToRequest = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun showLocationSettings() {
        Toast.makeText(this, "Konum (GPS) servisleri açık olmalıdır.", Toast.LENGTH_LONG).show()
        requestLocationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter?.isEnabled ?: false
    }

    private fun requestBluetoothEnable() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth Connect izni verilmemiş!", Toast.LENGTH_SHORT).show()
            return
        }
        requestBluetooth.launch(enableBtIntent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                pendingAction?.let { startNearbyProcess(it) }
            } else {
                Toast.makeText(this, "Gerekli izinler verilmedi.", Toast.LENGTH_LONG).show()
                pendingAction = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyManager.stopAll()
    }
}
