package com.example.deprembitirmeprojesi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReportDao {
    // Yeni raporu kaydet
    @Insert
    suspend fun insertReport(report: DisasterReport)

    // Henüz yüklenmemiş raporları getir
    @Query("SELECT * FROM reports WHERE isUploaded = 0")
    suspend fun getPendingReports(): List<DisasterReport>

    // Raporu güncelle (Yüklendi olarak işaretlemek için)
    @Update
    suspend fun updateReport(report: DisasterReport)
    
    // Tüm raporları getir (Listeleme ekranı için opsiyonel)
    @Query("SELECT * FROM reports ORDER BY receivedTimestamp DESC")
    suspend fun getAllReports(): List<DisasterReport>
}