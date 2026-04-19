package com.example.deprembitirmeprojesi.util

import com.example.deprembitirmeprojesi.data.DisasterReport
import java.util.*

object RiskCalculator {

    /**
     * Kullanıcı verilerine göre 0-100 arası bir risk skoru hesaplar.
     */
    fun calculateRiskScore(report: DisasterReport): Int {
        var score = 0

        // 1. MÜDAHALE DURUMU (STATUS) - Yeni Kriter
        // Eğer zaten kurtarılmışsa veya ekip müdahale ediyorsa risk puanını düşürürüz
        when(report.status) {
            "RESCUED" -> return 0 // Kurtarıldıysa risk bitti
            "RESCUING" -> score -= 30 // Müdahale ediliyor, aciliyet biraz azaldı
            "CLAIMED" -> score -= 15 // Ekip yolda
            "PENDING" -> score += 10 // Hala yardım bekliyor
        }

        // 2. MESAJ İÇERİĞİ (En Önemli Kriter)
        val msg = report.rawMessage.uppercase(Locale.getDefault())
        when {
            msg.contains("ENKAZ") || msg.contains("ALTINDAYIM") -> score += 60
            msg.contains("YARALI") || msg.contains("KÖTÜ") -> score += 40
            msg.contains("YARDIM") -> score += 30
            msg.contains("İYİYİM") -> score += 0
            else -> score += 10 // Belirsiz durum
        }

        // 3. ŞARJ YÜZDESİ
        val battery = report.batteryLevel.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 100
        when {
            battery < 10 -> score += 20
            battery < 20 -> score += 15
            battery < 50 -> score += 5
        }

        // 4. KRONİK HASTALIK VE İLAÇ
        if (report.chronicIllness.isNotEmpty() && !report.chronicIllness.equals("Yok", true)) {
            score += 15
        }
        if (report.regularMedication.isNotEmpty() && !report.regularMedication.equals("Yok", true)) {
            score += 10
        }

        // 5. YAŞ FAKTÖRÜ (Doğum Tarihi)
        val age = calculateAge(report.birthDate)
        if (age != -1) {
            when {
                age < 12 -> score += 15 // Çocuk
                age > 65 -> score += 15 // Yaşlı
                age > 50 -> score += 5
            }
        }

        // 6. BAĞLANTI DURUMU
        if (!report.isConnected) {
            score += 10 // Bağlantısı kopanlar daha riskli (ulaşılamıyor)
        }

        return score.coerceIn(0, 100) // 0-100 arası sınırla
    }

    fun getRiskLevel(score: Int): String {
        return when {
            score >= 70 -> "KRİTİK"
            score >= 40 -> "ORTA"
            else -> "DÜŞÜK"
        }
    }

    private fun calculateAge(birthDateStr: String): Int {
        return try {
            if (birthDateStr.isEmpty()) return -1
            // Varsayılan format: dd/MM/yyyy veya dd.MM.yyyy
            val parts = birthDateStr.split("/", ".")
            if (parts.size < 3) return -1
            
            val birthYear = parts[2].toInt()
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            currentYear - birthYear
        } catch (e: Exception) {
            -1
        }
    }
}
