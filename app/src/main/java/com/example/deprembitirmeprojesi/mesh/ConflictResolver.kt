package com.example.deprembitirmeprojesi.mesh

import com.example.deprembitirmeprojesi.data.DisasterReport

/**
 * Distributed State Çakışma Çözücü
 */
object ConflictResolver {

    /**
     * Sadece veri içeriği (mesaj, durum, kurtarıldı bilgisi vb.) için güncellik kontrolü yapar.
     * BAĞLANTI DURUMU (isConnected) buraya dahil DEĞİLDİR, o tamamen yereldir.
     */
    fun shouldUpdate(local: DisasterReport?, remote: DisasterReport): Boolean {
        if (local == null) return true

        // 1. AFAD bir güncelleme yaptıysa, kurbanın verisini her zaman ezer (Yetki önceliği)
        if (remote.role == "AFAD" && local.role == "VICTIM") {
            return true
        }

        // 2. Versiyon Kontrolü
        if (remote.version > local.version) {
            return true
        }

        // 3. Zaman Damgası Kontrolü
        if (remote.version == local.version && remote.lastSeenTimestamp > local.lastSeenTimestamp) {
            return true
        }

        return false
    }
}
