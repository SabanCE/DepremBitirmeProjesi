package com.example.deprembitirmeprojesi.nearby

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

/**
 * NearbyManager: İnternet bağlantısı olmasa bile Bluetooth ve Wi-Fi üzerinden 
 * cihazlar arası (P2P) haberleşmeyi sağlar.
 */
class NearbyManager(private val context: Context, private val listener: NearbyListener) {

    interface NearbyListener {
        fun onDeviceFound(endpointId: String, deviceName: String)
        fun onDataReceived(endpointId: String, message: String)
        fun onLogMessage(message: String)
        fun onConnectionEstablished(endpointId: String, deviceName: String)
        fun onConnectionLost(endpointId: String)
        fun onConnectionFailed(endpointId: String)
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = context.packageName
    private val strategy = Strategy.P2P_CLUSTER 
    
    // ÇAKIŞMA ÖNLEME: Her cihaz için benzersiz bir ID oluştur
    private val localId = java.util.UUID.randomUUID().toString().take(4)
    
    private var currentNickName = Build.MODEL + "_" + localId
    private val activeEndpoints = mutableMapOf<String, String>()
    private val discoveredNames = mutableMapOf<String, String>()
    
    // 8012 (STATUS_ALREADY_CONNECTED_TO_ENDPOINT) hatasını önlemek için takip seti
    private val connectingEndpoints = mutableSetOf<String>()
    
    private val handler = Handler(Looper.getMainLooper())

    private val isEmulator = Build.PRODUCT.contains("sdk") || 
                             Build.MODEL.contains("Emulator") || 
                             Build.MODEL.contains("Android SDK")

    /**
     * Hibrit modu başlatır. 
     * @param nickName Kullanıcı adı
     * @param userId Kullanıcının benzersiz ID'si (UUID)
     */
    fun startHybridMode(nickName: String, userId: String) {
        // Nickname içinde ID'yi gönderiyoruz: "ID|MODEL_ISMI"
        currentNickName = "$userId|$nickName"
        listener.onLogMessage("🔄 Çevrimdışı Haberleşme Başlatılıyor ($nickName)...")
        
        // Önceki tüm işlemleri durdur ve temizle (Hataları önlemek için kritik)
        stopAll()

        // 8007 (STATUS_ERROR) Çözümü: 
        // Bazı cihazlarda (Örn: AFAD cihazları) Bluetooth radyosu yayın ve tarama 
        // arasında hızlı geçiş yapamaz. Aralarına güvenli gecikmeler ekliyoruz.
        handler.postDelayed({
            if (!isEmulator) startAdvertising(currentNickName)
        }, 1500)

        handler.postDelayed({
            startDiscovery()
        }, 4000)
    }

    private fun startAdvertising(nickName: String) {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(nickName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { listener.onLogMessage("📡 Yayın Aktif (Bluetooth)") }
            .addOnFailureListener { e -> 
                val code = (e as? ApiException)?.statusCode
                if (code == 8007) {
                    listener.onLogMessage("⚠️ Bluetooth Meşgul (8007). Lütfen Bluetooth'u kapatıp açın.")
                } else {
                    listener.onLogMessage("⚠️ Yayın Hatası ($code)")
                }
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { listener.onLogMessage("🔍 Tarama Aktif") }
            .addOnFailureListener { e -> 
                val code = (e as? ApiException)?.statusCode
                listener.onLogMessage("⚠️ Tarama Hatası ($code)")
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectingEndpoints.add(endpointId)
            discoveredNames[endpointId] = info.endpointName
            listener.onLogMessage("🤝 Bağlantı isteği: ${info.endpointName}")
            
            // Deprem anında hızlı iletişim için gelen tüm bağlantıları otomatik kabul et
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connectingEndpoints.remove(endpointId)
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val name = discoveredNames[endpointId] ?: "Bilinmeyen"
                activeEndpoints[endpointId] = name
                listener.onLogMessage("✅ Bağlandı: $name")
                listener.onConnectionEstablished(endpointId, name)
            } else {
                val code = result.status.statusCode
                listener.onLogMessage("❌ Bağlantı hatası: $code")
                listener.onConnectionFailed(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectingEndpoints.remove(endpointId)
            val name = activeEndpoints.remove(endpointId)
            listener.onLogMessage("⚠️ Koptu: $name")
            listener.onConnectionLost(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // 8012 Çözümü: Eğer zaten bağlıysak veya şu an bağlanma aşamasındaysak isteği engelle
            if (activeEndpoints.containsKey(endpointId) || connectingEndpoints.contains(endpointId)) {
                return
            }

            discoveredNames[endpointId] = info.endpointName
            listener.onLogMessage("📍 Cihaz bulundu: ${info.endpointName}")
            listener.onDeviceFound(endpointId, info.endpointName)
            
            // ÇAKIŞMA ÖNLEME DÜZELTME: 
            // İki cihaz birbirini bulduğunda kimin 'request' atacağına isim sırasına göre karar veriyoruz.
            // Bu sayede her iki cihaz da beklemez, biri mutlaka istek atar.
            val shouldIRequest = currentNickName > info.endpointName
            
            if (shouldIRequest) {
                listener.onLogMessage("🔗 Bağlantı isteği gönderiliyor...")
                connectingEndpoints.add(endpointId)
                connectionsClient.requestConnection(currentNickName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e -> 
                        connectingEndpoints.remove(endpointId)
                        val code = (e as? ApiException)?.statusCode
                        listener.onLogMessage("❌ İstek başarısız: $code")
                    }
            } else {
                listener.onLogMessage("⏳ Karşı tarafın (isim sırasına göre) isteği bekleniyor...")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            connectingEndpoints.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val message = String(payload.asBytes()!!, StandardCharsets.UTF_8)
                listener.onDataReceived(endpointId, message)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun sendData(endpointId: String, message: String) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8)))
    }

    fun broadcastData(message: String) {
        val targets = activeEndpoints.keys.toList()
        if (targets.isNotEmpty()) {
            connectionsClient.sendPayload(targets, Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8)))
        }
    }

    fun stopAll() {
        activeEndpoints.clear()
        discoveredNames.clear()
        connectingEndpoints.clear()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }
}
