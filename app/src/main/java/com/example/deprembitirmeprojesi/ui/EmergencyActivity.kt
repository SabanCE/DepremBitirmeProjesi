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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.example.deprembitirmeprojesi.databinding.ActivityEmergencyBinding
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.example.deprembitirmeprojesi.worker.UploadWorker
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class EmergencyActivity : AppCompatActivity(), NearbyManager.NearbyListener {

    private lateinit var binding: ActivityEmergencyBinding
    private lateinit var nearbyManager: NearbyManager
    private lateinit var adapter: ArrayAdapter<String>
    private val logMessages = mutableListOf<String>()

    private var connectedEndpointId: String? = null
    private var pendingAction: (() -> Unit)? = null
    private lateinit var auth: FirebaseAuth
    private val database by lazy { AppDatabase.getDatabase(this) }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private const val TAG = "EmergencyActivity"
        private const val RESTART_DELAY_MS = 2000L
        private const val UPLOAD_WORK_NAME = "UploadReports"
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
        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logMessages)
        binding.listViewMessages.adapter = adapter
        nearbyManager = NearbyManager(this, this)

        setupClickListeners()
        showInitialUI()
    }

    private fun setupClickListeners() {
        binding.btnStartDiscovery.setOnClickListener { startDiscoveryMode() }
        binding.btnReset.setOnClickListener { resetAll() }
        binding.btnSend.setOnClickListener { sendMessage(binding.editMessage.text.toString()) }
        binding.btnLogout.setOnClickListener { logout() }
        // Hatalı olan kod buraya, doğru şekilde taşındı
        binding.root.findViewById<View>(R.id.btnOpenMap).setOnClickListener {
            // Harita Sayfasına Git
            val intent = Intent(this, MapActivity::class.java)
            startActivity(intent)
        }
    }

    private fun logout() {
        auth.signOut()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun startDiscoveryMode() {
        startNearbyProcess {
            nearbyManager.startDiscovery()
            showMessagingUI()
        }
    }

    private fun sendMessage(message: String) {
        if (message.isNotBlank() && connectedEndpointId != null) {
            nearbyManager.sendData(connectedEndpointId!!, message)
            addLog("GÖNDERİLEN: $message")
            binding.editMessage.text.clear()
        } else if (connectedEndpointId == null) {
            addLog("HATA: Mesaj göndermek için bir cihaza bağlı olmalısınız.")
        }
    }

    private fun startNearbyProcess(action: () -> Unit) {
        pendingAction = action
        if (isEmulator()) {
            addLog("EMULATOR: Kontroller atlanıyor.")
            action.invoke()
            pendingAction = null
            return
        }
        when {
            !hasAllPermissions() -> requestNearbyPermissions()
            !isLocationEnabled() -> showLocationSettings()
            !isBluetoothEnabled() -> requestBluetoothEnable()
            else -> {
                action.invoke()
                pendingAction = null
            }
        }
    }

    override fun onLogMessage(message: String) = addLog("Durum: $message")

    override fun onConnectionEstablished(endpointId: String) {
        connectedEndpointId = endpointId
        addLog("✅ BAĞLANTI KURULDU! Veri bekleniyor...")
        nearbyManager.stopDiscoveryOnly()
        runOnUiThread {
            showMessagingUI()
            binding.titleTextView.text = "DEPREMZEDE İLE BAĞLI"
        }
    }

    override fun onConnectionLost(endpointId: String) {
        connectedEndpointId = null
        addLog("⚠️ Bağlantı koptu. Tekrar taranıyor...")
        startDiscoveryMode()
    }

    override fun onConnectionFailed(endpointId: String) {
        addLog("Bağlantı denemesi başarısız. Yeni cihaz aranıyor...")
        restartDiscoveryWithDelay()
    }

    private fun restartDiscoveryWithDelay() {
        Handler(Looper.getMainLooper()).postDelayed({ startDiscoveryMode() }, RESTART_DELAY_MS)
    }

    override fun onDeviceFound(endpointId: String, deviceName: String) {
        addLog("Sinyal Bulundu: $deviceName. Otomatik bağlanılıyor...")
    }

    override fun onDataReceived(endpointId: String, message: String) {
        // Gelen mesajın kritik bir veri paketi mi yoksa anlık bir sohbet mesajı mı olduğunu anla.
        // Ana veri paketimiz "---" ve "Batarya:" gibi anahtar kelimeler içeriyor.
        val isEmergencyPayload = message.contains("---") && message.contains("Batarya:")

        if (isEmergencyPayload) {
            // Bu ana acil durum verisidir. Özel başlıkla logla ve kaydet.
            addLog("\n====== 🆘 ACİL DURUM VERİSİ ======")
            message.split("\n").forEach { if (it.isNotBlank()) addLog(it) }
            addLog("==================================\n")

            lifecycleScope.launch {
                val newReport = DisasterReport(
                    senderId = endpointId,
                    rawMessage = message,
                    receivedTimestamp = System.currentTimeMillis(),
                    isUploaded = false
                )
                database.reportDao().insertReport(newReport)
                addLog("💾 Acil Durum Verisi yerel hafızaya kaydedildi (Offline).")
                scheduleUpload()
            }
        } else {
            // Bu anlık bir durum/sohbet mesajıdır. Sadece ekrana logla.
            addLog("GELEN MESAJ: $message")
        }
    }

    private fun scheduleUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            uploadRequest
        )

        addLog("☁️ Senkronizasyon kuyruğa alındı (İnternet bekleniyor...)")

        // İŞİN DURUMUNU DİNLE
        workManager.getWorkInfoByIdLiveData(uploadRequest.id)
            .observe(this, Observer { workInfo ->
                if (workInfo != null && workInfo.state == WorkInfo.State.SUCCEEDED) {
                    val uploadedCount = workInfo.outputData.getInt(UploadWorker.KEY_UPLOAD_COUNT, 0)
                    if (uploadedCount > 0) {
                        addLog("✅☁️ $uploadedCount adet çevrimdışı rapor Firebase'e başarıyla yüklendi!")
                    }
                    // Gözlemciyi kaldır ki her seferinde tekrar tetiklenmesin.
                    workManager.getWorkInfoByIdLiveData(uploadRequest.id).removeObservers(this)
                }
            })
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
        binding.messagingLayout.visibility = View.GONE
        binding.titleTextView.text = "AFAD PERSONEL MODU"
    }

    private fun showMessagingUI() {
        binding.initialButtonsLayout.visibility = View.GONE
        binding.messagingLayout.visibility = View.VISIBLE
        binding.titleTextView.text = "YARDIM SİNYALİ ARANIYOR..."
        binding.editMessage.visibility = View.VISIBLE
        binding.btnSend.visibility = View.VISIBLE
    }

    private fun addLog(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            val formattedMessage = "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} - $message"
            logMessages.add(0, formattedMessage)
            adapter.notifyDataSetChanged()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun showLocationSettings() {
        Toast.makeText(this, "Nearby API için Konum (GPS) servisleri açık olmalıdır.", Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        super.onDestroy()
        nearbyManager.stopAll()
    }
}
