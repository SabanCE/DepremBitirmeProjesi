package com.example.deprembitirmeprojesi.mesh

import android.content.Context
import android.util.Log
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Projenin ana Mesh Orkestratörü.
 */
class MeshNetworkManager private constructor(
    private val context: Context,
    private val nearbyManager: NearbyManager
) : NearbyManager.NearbyListener {

    interface MeshMessageListener {
        fun onMessageReceived(userName: String, message: String)
    }

    private var messageListener: MeshMessageListener? = null
    private val syncEngine = StateSyncEngine(context, nearbyManager)
    private val database = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val directConnections = mutableMapOf<String, String>()

    companion object {
        @Volatile
        private var INSTANCE: MeshNetworkManager? = null

        fun getInstance(context: Context, nearbyManager: NearbyManager? = null): MeshNetworkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: if (nearbyManager != null) {
                    MeshNetworkManager(context.applicationContext, nearbyManager).also { INSTANCE = it }
                } else {
                    throw IllegalStateException("MeshNetworkManager not initialized.")
                }
            }
        }
    }

    fun setMessageListener(listener: MeshMessageListener) {
        this.messageListener = listener
    }

    override fun onDataReceived(endpointId: String, message: String) {
        val packet = MeshPacket.fromJson(message) ?: return
        
        // 1. Paket zaten işlendiyse (log kirliliğini önlemek için kritik)
        if (syncEngine.isPacketSeen(packet.id)) return

        if (packet.type == "STATE_UPDATE" || packet.type == "SYNC_DATA") {
            scope.launch {
                try {
                    val obj = JSONObject(packet.payload)
                    val senderId = obj.optString("senderId", "")
                    val userName = obj.optString("userProfile", "Bilinmeyen")
                    val rawMessage = obj.optString("rawMessage", "")
                    val role = obj.optString("role", "VICTIM")

                    // AFAD personelinin mesajlarını loga basma
                    if (role == "AFAD") {
                        syncEngine.handleIncomingPacket(packet)
                        return@launch
                    }

                    // Yereldeki eski durumu kontrol et
                    val localReport = database.reportDao().getReportBySender(senderId)
                    
                    // SADECE mesaj gerçekten değiştiyse veya ilk defa geliyorsa loga bas.
                    // Status güncellemeleri isNewMessage'ı tetiklemez!
                    val isNewMessage = localReport == null || localReport.rawMessage != rawMessage

                    if (isNewMessage && rawMessage.isNotBlank() && 
                        !rawMessage.contains("SİSTEME BAĞLANDI") && 
                        !rawMessage.contains("Bağlantı Kuruldu")) {
                        
                        messageListener?.onMessageReceived(userName, rawMessage)
                    }
                } catch (e: Exception) {
                    Log.e("MeshManager", "Log parse error", e)
                }
                
                // 2. Senkronizasyonu başlat
                syncEngine.handleIncomingPacket(packet)
            }
        } else {
            syncEngine.handleIncomingPacket(packet)
        }
    }

    override fun onConnectionEstablished(endpointId: String, deviceName: String) {
        val parts = deviceName.split("|")
        val senderId = if (parts.size >= 2) parts[0] else endpointId
        val displayName = if (parts.size >= 2) parts[1] else deviceName

        directConnections[endpointId] = senderId
        
        // Eğer AFAD personeli ise listeye hiç ekleme
        if (displayName.contains("AFAD") || senderId.startsWith("AFAD")) {
            return
        }

        scope.launch {
            val report = database.reportDao().getReportBySender(senderId) ?: DisasterReport(
                senderId = senderId,
                rawMessage = "Bağlantı Kuruldu",
                userProfile = displayName,
                lastSeenTimestamp = System.currentTimeMillis(),
                isConnected = true,
                version = 0
            )
            report.isConnected = true
            report.lastSeenTimestamp = System.currentTimeMillis()
            database.reportDao().upsertReport(report)
            Log.d("MeshManager", "CONNECTED: $displayName")
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            syncEngine.requestSync()
        }, 1000)
    }

    override fun onConnectionLost(endpointId: String) {
        val senderId = directConnections.remove(endpointId)
        if (senderId != null) {
            scope.launch {
                if (!directConnections.values.contains(senderId)) {
                    val report = database.reportDao().getReportBySender(senderId)
                    if (report != null) {
                        report.isConnected = false
                        // Saniyelik güncelleme için timestamp'i yenile
                        report.lastSeenTimestamp = System.currentTimeMillis()
                        database.reportDao().upsertReport(report)
                        Log.d("MeshManager", "DISCONNECTED: $senderId")
                    }
                }
            }
        }
    }

    override fun onDeviceFound(endpointId: String, deviceName: String) {}
    override fun onLogMessage(message: String) {}
    override fun onConnectionFailed(endpointId: String) {}

    fun updateAndBroadcastStatus(report: DisasterReport) {
        syncEngine.broadcastLocalUpdate(report)
    }

    fun stopAll() {
        directConnections.clear()
        nearbyManager.stopAll()
        scope.launch {
            database.reportDao().clearAllConnections()
        }
    }
}
