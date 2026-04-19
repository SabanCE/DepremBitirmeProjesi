package com.example.deprembitirmeprojesi.mesh

import com.example.deprembitirmeprojesi.data.DisasterReport
import org.json.JSONObject

/**
 * Distributed State Network için veri paketi
 */
data class MeshPacket(
    val id: String,
    val senderId: String,
    val type: String, // STATE_UPDATE, HEARTBEAT
    val payload: String,
    val version: Long,
    val ttl: Int = 5
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("id", id)
            put("senderId", senderId)
            put("type", type)
            put("payload", payload)
            put("version", version)
            put("ttl", ttl)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): MeshPacket? {
            return try {
                val obj = JSONObject(json)
                MeshPacket(
                    id = obj.getString("id"),
                    senderId = obj.getString("senderId"),
                    type = obj.getString("type"),
                    payload = obj.getString("payload"),
                    version = obj.getLong("version"),
                    ttl = obj.getInt("ttl")
                )
            } catch (e: Exception) { null }
        }
    }
}
