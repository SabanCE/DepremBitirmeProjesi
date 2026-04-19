package com.example.deprembitirmeprojesi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(report: DisasterReport)

    @Query("SELECT * FROM disaster_reports WHERE senderId = :senderId LIMIT 1")
    suspend fun getReportBySender(senderId: String): DisasterReport?

    @Query("SELECT * FROM disaster_reports WHERE role != 'AFAD' ORDER BY lastSeenTimestamp DESC")
    fun getAllReportsFlow(): kotlinx.coroutines.flow.Flow<List<DisasterReport>>

    @Query("SELECT * FROM disaster_reports WHERE role != 'AFAD' ORDER BY lastSeenTimestamp DESC")
    suspend fun getAllReports(): List<DisasterReport>

    @Update
    suspend fun updateReport(report: DisasterReport)

    @Query("UPDATE disaster_reports SET isConnected = 0")
    suspend fun clearAllConnections()

    // reports tablosu eski sürümden kaldıysa burayı koruyoruz
    @Query("SELECT * FROM disaster_reports WHERE isUploaded = 0")
    suspend fun getPendingReports(): List<DisasterReport>
}
