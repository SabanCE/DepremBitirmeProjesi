package com.example.deprembitirmeprojesi.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        // Aktivitenin dinleyeceği ve sonucu alacağı anahtar
        const val KEY_UPLOAD_COUNT = "key_upload_count"
    }

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.reportDao()
        val firestore = FirebaseFirestore.getInstance()

        val pendingReports = dao.getPendingReports()
        if (pendingReports.isEmpty()) {
            Log.d("UploadWorker", "Yüklenecek yeni rapor yok.")
            val outputData = workDataOf(KEY_UPLOAD_COUNT to 0)
            return Result.success(outputData)
        }

        Log.d("UploadWorker", "Yüklenecek rapor sayısı: ${pendingReports.size}")

        return try {
            for (report in pendingReports) {
                val firestoreData = hashMapOf(
                    "original_message" to report.rawMessage,
                    "sender_endpoint_id" to report.senderId,
                    "received_at" to report.receivedTimestamp,
                    "source" to "OFFLINE_SYNC_BLUETOOTH"
                )

                firestore.collection("disaster_reports").add(firestoreData).await()

                val updatedReport = report.copy(isUploaded = true)
                dao.updateReport(updatedReport)
            }

            Log.d("UploadWorker", "Tüm raporlar başarıyla senkronize edildi.")
            // Aktiviteye sonucu bildirmek için çıkış verisi oluştur
            val outputData = workDataOf(KEY_UPLOAD_COUNT to pendingReports.size)
            Result.success(outputData)

        } catch (e: Exception) {
            Log.e("UploadWorker", "Yükleme hatası: ${e.message}")
            Result.retry()
        }
    }
}