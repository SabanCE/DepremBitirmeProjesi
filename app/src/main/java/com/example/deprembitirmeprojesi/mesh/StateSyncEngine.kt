package com.example.deprembitirmeprojesi.mesh

import android.content.Context
import android.util.Log
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.example.deprembitirmeprojesi.nearby.NearbyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections

/**
 * StateSyncEngine: Yerel DB ile Mesh Ağı arasındaki senkronizasyonu yönetir.
 */
class StateSyncEngine(
    private val context: Context,
    private val nearbyManager: NearbyManager
) {
    private val database = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "StateSyncEngine"

    private val seenMessageIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val MAX_SEEN_CACHE = 500

    private val processedPackets = mutableSetOf<String>()

    fun isPacketSeen(packetId: String): Boolean {
        if (processedPackets.contains(packetId)) return true
        processedPackets.add(packetId)
        // Belleği temiz tutmak için 1000 paketten sonrasını silebiliriz
        if (processedPackets.size > 1000) processedPackets.remove(processedPackets.first())
        return false
    }

    fun handleIncomingPacket(packet: MeshPacket) {
        if (seenMessageIds.contains(packet.id)) return
        
        if (seenMessageIds.size > MAX_SEEN_CACHE) {
            seenMessageIds.clear()
        }
        seenMessageIds.add(packet.id)

        scope.launch {
            try {
                when (packet.type) {
                    "STATE_UPDATE", "SYNC_DATA" -> {
                        val remoteReport = parseReportFromJson(packet.payload) ?: return@launch
                        val localReport = database.reportDao().getReportBySender(remoteReport.senderId)

                        // Çakışma kontrolü ve güncelleme
                        if (ConflictResolver.shouldUpdate(localReport, remoteReport)) {
                            // KRİTİK: Dışarıdan gelen paketteki isConnected bilgisini siliyoruz.
                            // Bir cihazın bağlı olup olmadığına SADECE MeshNetworkManager (Nearby) karar verebilir.
                            val reportToSave = if (localReport != null) {
                                remoteReport.copy(isConnected = localReport.isConnected)
                            } else {
                                remoteReport.copy(isConnected = false) 
                            }
                            database.reportDao().upsertReport(reportToSave)
                            Log.d(TAG, "Sync Success: ${remoteReport.senderId} (Message: ${remoteReport.rawMessage})")
                        }

                        // GOSSIP: Canlı güncellemeleri yaymaya devam et
                        if (packet.type == "STATE_UPDATE" && packet.ttl > 0) {
                            val forwardPacket = packet.copy(ttl = packet.ttl - 1)
                            nearbyManager.broadcastData(forwardPacket.toJson())
                        }
                    }
                    "SYNC_REQUEST" -> {
                        sendRelevantReports()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling packet: ${packet.id}", e)
            }
        }
    }

    fun broadcastLocalUpdate(report: DisasterReport) {
        scope.launch {
            // MESAJ GÜNCELLEME FIX: Her yeni mesajda versiyonu mutlaka artırıyoruz
            // Böylece ağdaki diğer cihazlar "bu yeni bir bilgidir" diyerek listeyi günceller.
            report.version++
            database.reportDao().upsertReport(report)

            val packet = MeshPacket(
                id = java.util.UUID.randomUUID().toString(),
                senderId = report.senderId,
                type = "STATE_UPDATE",
                payload = reportToJson(report),
                version = report.version,
                ttl = 5
            )
            seenMessageIds.add(packet.id)
            nearbyManager.broadcastData(packet.toJson())
        }
    }

    fun requestSync() {
        val packet = MeshPacket(
            id = java.util.UUID.randomUUID().toString(),
            senderId = "SYSTEM",
            type = "SYNC_REQUEST",
            payload = "",
            version = System.currentTimeMillis(),
            ttl = 1 
        )
        nearbyManager.broadcastData(packet.toJson())
    }

    private suspend fun sendRelevantReports() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val myId = user?.uid ?: com.example.deprembitirmeprojesi.util.Constants.DUMMY_USER_ID

        // 1. Önce her zaman kendi raporumu gönder
        val myReport = database.reportDao().getReportBySender(myId)
        myReport?.let { sendSingleReport(it) }

        // 2. Eğer ben AFAD isem, diğer kurbanların raporlarını da paylaşabilirim (Yardımcı olmak için)
        // Ancak normal bir kullanıcıysam (VICTIM), sadece kendimi bildirmem yeterli, 
        // veritabanımı başkasına kopyalamamalıyım.
        if (myReport?.role == "AFAD") {
            val allReports = database.reportDao().getAllReports()
            allReports.forEach { report ->
                if (report.senderId != myId) {
                    sendSingleReport(report)
                }
            }
        }
    }

    private fun sendSingleReport(report: DisasterReport) {
        // AFAD Koordinasyonu: Sadece depremzedelerin (VICTIM) bilgilerini paylaşıyoruz.
        // AFAD kendi bilgisini ağa yaymamalı.
        if (report.role == "AFAD") return

        // KRİTİK: SYNC sırasında isConnected bilgisini ASLA göndermiyoruz.
        val syncReport = report.copy(isConnected = false)

        val packet = MeshPacket(
            id = java.util.UUID.randomUUID().toString(),
            senderId = report.senderId,
            type = "SYNC_DATA",
            payload = reportToJson(syncReport),
            version = report.version,
            ttl = 1
        )
        nearbyManager.broadcastData(packet.toJson())
    }

    private fun reportToJson(report: DisasterReport): String {
        return JSONObject().apply {
            put("senderId", report.senderId)
            put("rawMessage", report.rawMessage)
            put("userProfile", report.userProfile)
            put("batteryLevel", report.batteryLevel)
            put("lastLocation", report.lastLocation)
            put("lastSeenTimestamp", report.lastSeenTimestamp)
            put("role", report.role)
            put("status", report.status)
            put("assignedToAfadId", report.assignedToAfadId ?: "")
            put("priorityLevel", report.priorityLevel)
            put("version", report.version)
            put("bloodType", report.bloodType)
            put("chronicIllness", report.chronicIllness)
            put("birthDate", report.birthDate)
            put("apartmentInfo", report.apartmentInfo)
            put("floorInfo", report.floorInfo)
            put("regularMedication", report.regularMedication)
            put("isConnected", report.isConnected) 
            put("isUploaded", report.isUploaded)
        }.toString()
    }

    private fun parseReportFromJson(json: String): DisasterReport? {
        return try {
            val obj = JSONObject(json)
            DisasterReport(
                senderId = obj.getString("senderId"),
                rawMessage = obj.getString("rawMessage"),
                userProfile = obj.getString("userProfile"),
                batteryLevel = obj.getString("batteryLevel"),
                lastLocation = obj.getString("lastLocation"),
                lastSeenTimestamp = obj.getLong("lastSeenTimestamp"),
                role = obj.getString("role"),
                status = obj.getString("status"),
                assignedToAfadId = obj.optString("assignedToAfadId").takeIf { it.isNotEmpty() },
                priorityLevel = obj.getInt("priorityLevel"),
                version = obj.getLong("version"),
                bloodType = obj.optString("bloodType"),
                chronicIllness = obj.optString("chronicIllness"),
                birthDate = obj.optString("birthDate"),
                apartmentInfo = obj.optString("apartmentInfo"),
                floorInfo = obj.optString("floorInfo"),
                regularMedication = obj.optString("regularMedication"),
                isConnected = obj.optBoolean("isConnected", false),
                isUploaded = obj.optBoolean("isUploaded", false)
            )
        } catch (e: Exception) { null }
    }
}
