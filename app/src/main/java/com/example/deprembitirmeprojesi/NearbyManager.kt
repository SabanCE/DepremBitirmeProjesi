package com.example.deprembitirmeprojesi

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

class NearbyManager(private val context: Context, private val listener: NearbyListener) {

    interface NearbyListener {
        fun onDeviceFound(endpointId: String, deviceName: String)
        fun onDataReceived(endpointId: String, message: String)
        fun onStatusChange(status: String)
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = context.packageName
    private val strategy = Strategy.P2P_STAR

    fun startAdvertising(payload: String) {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            payload,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        )
            .addOnSuccessListener { listener.onStatusChange("📡 Yayın Başlatıldı (Strategy: STAR)") }
            .addOnFailureListener { e -> listener.onStatusChange("❌ ADVERTISING FAILED: ${e.javaClass.simpleName} - ${e.message}") }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        )
            .addOnSuccessListener { listener.onStatusChange("🔍 Tarama Başlatıldı (Strategy: STAR)") }
            .addOnFailureListener { e -> listener.onStatusChange("❌ DISCOVERY FAILED: ${e.javaClass.simpleName} - ${e.message}") }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.stopDiscovery()
            listener.onStatusChange("🔗 Bağlantı İsteği: ${info.endpointName}. Onaylanıyor...")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> listener.onStatusChange("❌ ACCEPT FAILED: ${e.javaClass.simpleName} - ${e.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                listener.onStatusChange("✅ Cihaz Bağlandı! ($endpointId)")
            } else {
                val error = "Code: ${result.status.statusCode} - ${result.status.statusMessage}"
                listener.onStatusChange("❌ CONNECTION FAILED: $error")
            }
        }

        override fun onDisconnected(endpointId: String) {
            listener.onStatusChange("⚠️ Bağlantı Koptu ($endpointId)")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener.onDeviceFound(endpointId, info.endpointName)
            listener.onStatusChange("Cihaz bulundu: ${info.endpointName}. Bağlantı deneniyor...")
            connectionsClient.requestConnection("Kurtaran Cihaz", endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e -> listener.onStatusChange("❌ REQUEST FAILED: ${e.javaClass.simpleName} - ${e.message}") }
        }

        override fun onEndpointLost(endpointId: String) {
            listener.onStatusChange("Cihaz Kayboldu: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                listener.onDataReceived(endpointId, String(it, StandardCharsets.UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun sendData(endpointId: String, message: String) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8)))
            .addOnFailureListener { e -> listener.onStatusChange("❌ SEND FAILED: ${e.javaClass.simpleName} - ${e.message}") }
    }

    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }
}
