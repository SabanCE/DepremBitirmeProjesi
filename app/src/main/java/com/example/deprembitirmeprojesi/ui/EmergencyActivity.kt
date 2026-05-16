package com.example.deprembitirmeprojesi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.UserProfile
import com.example.deprembitirmeprojesi.databinding.ActivityEmergencyBinding
import com.example.deprembitirmeprojesi.mesh.MeshNetworkManager
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import com.example.deprembitirmeprojesi.util.ThemeHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class EmergencyActivity : AppCompatActivity(), MeshNetworkManager.MeshMessageListener {

    private lateinit var binding: ActivityEmergencyBinding
    private var nearbyManager: NearbyManager? = null
    private var meshManager: MeshNetworkManager? = null

    private lateinit var adapter: ArrayAdapter<String>
    private val logMessages = mutableListOf<String>()
    private val processedMessageHashes = mutableSetOf<String>()
    
    // UUID ve Display Name eşleşmesini tutacak haritalar
    private val logIdMap = mutableMapOf<String, String>()
    private val endpointToNameMap = mutableMapOf<String, String>()
    private val endpointToIdMap = mutableMapOf<String, String>()

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private var assignedLocation: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupClickListeners()
        listenToAssignedTasks()
    }

    private fun setupUI() {
        adapter = ArrayAdapter(this, R.layout.item_log, logMessages)
        binding.listViewMessages.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnStartDiscovery.setOnClickListener {
            checkPermissionsAndStart { startMesh() }
        }

        binding.btnOpenCoordinate.setOnClickListener {
            startActivity(Intent(this, PersonnelActionActivity::class.java))
        }

        binding.btnSendPrivateMessage.setOnClickListener {
            // Önce tag'deki gerçek ID'yi dene, yoksa metindeki ismi/ID'yi kullan
            val targetId = binding.etTargetId.tag as? String ?: binding.etTargetId.text.toString().trim()
            val message = binding.etPrivateMessage.text.toString().trim()
            
            if (targetId.isNotEmpty() && message.isNotEmpty()) {
                sendPrivateMessage(targetId, message)
                binding.etPrivateMessage.text?.clear()
            } else {
                Toast.makeText(this, "Hedef seçiniz ve mesaj giriniz.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.listViewMessages.setOnItemClickListener { _, _, position, _ ->
            val logLine = logMessages[position]
            
            // 1. Gerçek ID'yi logIdMap'ten al (uuid)
            val realSenderId = logIdMap[logLine]
            
            // 2. Görünen ismi ayıkla
            val extractedName = when {
                logLine.startsWith("📍 Yakınlarda cihaz saptandı: ") -> {
                    logLine.substringAfter("📍 Yakınlarda cihaz saptandı: ").trim()
                }
                logLine.startsWith("✅ BAĞLANTI KURULDU: ") -> {
                    logLine.substringAfter("✅ BAĞLANTI KURULDU: ").trim()
                }
                logLine.startsWith("⚠️ BAĞLANTI KOPTU: ") -> {
                    logLine.substringAfter("⚠️ BAĞLANTI KOPTU: ").trim()
                }
                logLine.contains(": ") -> {
                    logLine.substringBefore(": ").trim()
                }
                else -> logLine.trim()
            }
            
            // Ekranda ismi göster, arka planda (tag) gerçek ID'yi sakla
            binding.etTargetId.setText(extractedName)
            binding.etTargetId.tag = realSenderId

            Toast.makeText(this, "Hedef seçildi: $extractedName", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenMap.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java)
            assignedLocation?.let { loc ->
                val coords = loc.split(",")
                val lat = coords[0].toDoubleOrNull()
                val lng = coords[1].toDoubleOrNull()
                if (lat != null && lng != null) {
                    intent.putExtra("LAT", lat)
                    intent.putExtra("LNG", lng)
                    intent.putExtra("TARGET_LOCATION", loc)
                }
            }
            startActivity(intent)
        }

        binding.btnOpenHistory.setOnClickListener {
            startActivity(Intent(this, ReportHistoryActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        
        binding.btnReset.setOnClickListener {
            logMessages.clear()
            processedMessageHashes.clear()
            logIdMap.clear()
            adapter.notifyDataSetChanged()
        }
    }

    private fun listenToAssignedTasks() {
        val user = auth.currentUser ?: return
        firestore.collection("users").document(user.uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                
                val profile = snapshot?.toObject(UserProfile::class.java)
                assignedLocation = profile?.assignedLocation
            }
    }

    private fun startMesh() {
        if (processedMessageHashes.isEmpty()) {
            addLog("📡 Sinyal arama başlatılıyor...")
        }
        
        if (nearbyManager == null) {
            nearbyManager = NearbyManager(this, object : NearbyManager.NearbyListener {
                override fun onDeviceFound(endpointId: String, deviceName: String) {
                    val displayName = if (deviceName.contains("|")) deviceName.split("|")[1] else deviceName
                    runOnUiThread {
                        val logMsg = "📍 Yakınlarda cihaz saptandı: $displayName"
                        if (logMessages.isEmpty() || logMessages[0] != logMsg) {
                            addLog(logMsg, endpointId)
                        }
                    }
                }

                override fun onDataReceived(endpointId: String, message: String) {
                    meshManager?.onDataReceived(endpointId, message)
                    
                    try {
                        val packet = com.example.deprembitirmeprojesi.mesh.MeshPacket.fromJson(message)
                        if (packet != null) {
                            // 1. MESH ECHOSUNU (AYNI PAKETİ) ENGELLE
                            if (processedMessageHashes.contains(packet.id)) return
                            
                            val obj = org.json.JSONObject(packet.payload)
                            val userName = obj.optString("userProfile", "Bilinmeyen")
                            val rawMsg = obj.optString("rawMessage", "")
                            val senderId = obj.optString("senderId", "")
                            
                            // 2. Kendi mesajımız değilse ve boş değilse loga bas
                            val myId = auth.currentUser?.uid
                            if (rawMsg.isNotBlank() && senderId != myId && senderId != "AFAD_OZEL_MESAJ") {
                                processedMessageHashes.add(packet.id)
                                onMessageReceived(userName, rawMsg, senderId)
                            }
                        } else {
                            if (!message.startsWith("{")) {
                                if (processedMessageHashes.contains(message)) return
                                processedMessageHashes.add(message)
                                onMessageReceived("Bilinmeyen", message, endpointId)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("EmergencyActivity", "Packet parse error", e)
                    }
                }

                override fun onLogMessage(message: String) {}

                override fun onConnectionEstablished(endpointId: String, deviceName: String) {
                    val parts = deviceName.split("|")
                    val senderId = if (parts.size >= 2) parts[0] else endpointId
                    val displayName = if (parts.size >= 2) parts[1] else deviceName
                    
                    endpointToNameMap[endpointId] = displayName
                    endpointToIdMap[endpointId] = senderId
                    
                    runOnUiThread {
                        addLog("✅ BAĞLANTI KURULDU: $displayName", senderId)
                        updateConnectedCount()
                    }
                    meshManager?.onConnectionEstablished(endpointId, deviceName)
                }

                override fun onConnectionLost(endpointId: String) {
                    val displayName = endpointToNameMap[endpointId] ?: endpointId
                    val senderId = endpointToIdMap[endpointId] ?: endpointId
                    
                    runOnUiThread {
                        val logMsg = "⚠️ BAĞLANTI KOPTU: $displayName"
                        if (!processedMessageHashes.contains(logMsg)) {
                            addLog(logMsg, senderId)
                        }
                        updateConnectedCount()
                    }
                    
                    endpointToNameMap.remove(endpointId)
                    endpointToIdMap.remove(endpointId)
                    meshManager?.onConnectionLost(endpointId)
                }

                override fun onConnectionFailed(endpointId: String) {
                    runOnUiThread {
                        if (!processedMessageHashes.contains("❌ Bağlantı hatası oluştu.")) {
                            addLog("❌ Bağlantı hatası oluştu.")
                        }
                    }
                }
            })
        }

        meshManager = MeshNetworkManager.getInstance(this, nearbyManager)
        meshManager?.setMessageListener(this)
        
        val user = auth.currentUser
        val userId = user?.uid ?: "afad_${java.util.UUID.randomUUID().toString().take(4)}"
        
        nearbyManager?.startHybridMode("AFAD Görevlisi", "AFAD_$userId")
    }

    private fun sendPrivateMessage(targetId: String, message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val report = com.example.deprembitirmeprojesi.data.DisasterReport(
                senderId = "AFAD_OZEL_MESAJ",
                userProfile = "AFAD PERSONEL",
                rawMessage = "BİLGİ: (Size Özel) $message",
                assignedToAfadId = targetId, // İsim veya ID burada hedef olarak gidiyor
                role = "AFAD",
                lastSeenTimestamp = System.currentTimeMillis()
            )
            
            try {
                meshManager?.updateAndBroadcastStatus(report)
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@EmergencyActivity, "Gönderilemedi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onMessageReceived(userName: String, message: String) {
        // Fallback: realSenderId bilinmiyorsa arkaplan haritasında yoksa "Unknown" yerine isme tıklandığında ismi doldururuz
        onMessageReceived(userName, message, userName)
    }

    private fun onMessageReceived(userName: String, message: String, realSenderId: String) {
        runOnUiThread {
            // 1. Teknik mesajları süz (Gereksiz veri filtresi)
            val technicalKeywords = listOf("SİSTEME BAĞLANDI", "Bağlantı Kuruldu", "KONUM_GUNCELLE", "DURUM_GUNCELLE")
            if (technicalKeywords.any { message.contains(it) }) return@runOnUiThread
            
            // 2. Kendi gönderdiğimiz (AFAD) mesajlarını süz
            val myId = auth.currentUser?.uid
            if (userName == "AFAD" || userName.startsWith("AFAD_OZEL_MESAJ") || realSenderId == myId) return@runOnUiThread
            
            // SADE GÖRÜNÜM: Sadece isim ve mesaj
            val displayLine = "$userName: $message"
            
            // 3. Eşsiz mesaj kontrolü ve loga ekleme
            addLog(displayLine, realSenderId)
        }
    }

    private fun addLog(msg: String, senderId: String? = null) {
        // ARTIK PAKET ID'SİNE GÖRE FİLTRELEDİĞİMİZ İÇİN BURADA SADECE 
        // ÜST ÜSTE GELEN AYNI SİSTEM LOGLARINI ENGELEMEMİZ YETERLİ
        if (logMessages.isNotEmpty() && logMessages[0] == msg) return
        
        logMessages.add(0, msg)
        
        // Eğer bir gönderici ID'si varsa, bu mesaj satırını o ID ile eşleştir (Tıklama için)
        if (senderId != null) {
            logIdMap[msg] = senderId
        }

        adapter.notifyDataSetChanged()
        
        // Bellek yönetimi
        if (processedMessageHashes.size > 1000) processedMessageHashes.clear()
    }

    private fun checkPermissionsAndStart(action: () -> Unit) {
        val permissions = getRequiredPermissions()
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        
        if (missing.isEmpty()) {
            action()
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) startMesh()
    }

    private fun getRequiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyManager?.stopAll()
    }

    private fun updateConnectedCount() {
        val count = endpointToIdMap.size
        binding.txtLogTitle.text = "CANLI VERİ AKIŞI (Bağlı: $count)"
    }
}
