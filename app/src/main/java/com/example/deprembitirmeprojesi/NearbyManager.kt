package com.example.deprembitirmeprojesi

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class NearbyManager(private val context: Context, private val listener: NearbyListener) {

    interface NearbyListener {
        fun onDeviceFound(endpointId: String, deviceName: String)
        fun onDataReceived(endpointId: String, message: String)
        fun onLogMessage(message: String)
        fun onConnectionEstablished(endpointId: String)
        fun onConnectionLost(endpointId: String)
        fun onConnectionFailed(endpointId: String)
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = context.packageName
    private val strategy = Strategy.P2P_STAR
    private val isConnectionLocked = AtomicBoolean(false)
    private val foundEndpoints = mutableSetOf<String>()

    fun startAdvertising(nickName: String) {
        stopAll()
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            nickName, serviceId, connectionLifecycleCallback, advertisingOptions
        )
            .addOnSuccessListener { listener.onLogMessage("📡 Yayın Başlatıldı: $nickName") }
            .addOnFailureListener { e -> listener.onLogMessage("❌ YAYIN HATASI: ${e.message}") }
    }

    fun startDiscovery() {
        stopAll()
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        )
            .addOnSuccessListener { listener.onLogMessage("🔍 Tarama Başlatıldı...") }
            .addOnFailureListener { e -> listener.onLogMessage("❌ TARAMA HATASI: ${e.message}") }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener.onLogMessage("🔗 Bağlantı İsteği: ${info.endpointName}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                listener.onLogMessage("✅ BAĞLANDI! ($endpointId)")
                listener.onConnectionEstablished(endpointId)
            } else {
                listener.onLogMessage("❌ BAĞLANTI REDDEDİLDİ: ${result.status.statusMessage}")
                listener.onConnectionFailed(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            listener.onLogMessage("⚠️ Bağlantı Koptu ($endpointId)")
            listener.onConnectionLost(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!foundEndpoints.contains(endpointId)) {
                foundEndpoints.add(endpointId)
                listener.onDeviceFound(endpointId, info.endpointName)
                listener.onLogMessage("Cihaz Bulundu: ${info.endpointName}. Bağlanılıyor...")

                connectionsClient.requestConnection("AFAD_EKIBI", endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e ->
                        listener.onLogMessage("❌ BAĞLANTI İSTEĞİ HATASI: ${e.message}")
                        listener.onConnectionFailed(endpointId)
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            foundEndpoints.remove(endpointId)
            listener.onLogMessage("Cihaz Kapsamdan Çıktı: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                val message = String(it, StandardCharsets.UTF_8)
                listener.onDataReceived(endpointId, message)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) { }
    }

    fun sendData(endpointId: String, message: String) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8)))
            .addOnFailureListener { e -> listener.onLogMessage("❌ VERİ GÖNDERME HATASI: ${e.message}") }
    }

    fun stopAll() {
        foundEndpoints.clear()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    fun stopAdvertisingOnly() {
        connectionsClient.stopAdvertising()
    }

    fun stopDiscoveryOnly() {
        connectionsClient.stopDiscovery()
    }
}