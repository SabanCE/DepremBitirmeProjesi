package com.example.deprembitirmeprojesi.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.util.Constants
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

class AlertCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getLong("KEY_RECORD_ID", -1L)
        val firebaseDocId = inputData.getString("KEY_FIREBASE_DOC_ID")

        if (recordId == -1L || firebaseDocId.isNullOrBlank()) {
            Log.e("CleanupWorker", "Record ID veya Firebase Doc ID gelmedi, işlem başarısız.")
            return Result.failure()
        }

        return try {
            val dao = AppDatabase.getDatabase(applicationContext).earthquakeDao()
            val record = dao.getRecordById(recordId)

            if (record != null && record.status == Constants.STATUS_ANALYSING) {
                // Önce lokal kaydı sil
                dao.deleteRecordById(recordId)
                Log.i("CleanupWorker", "$recordId ID'li lokal analiz kaydı silindi.")

                // Sonra Firestore kaydını sil
                Firebase.firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS)
                    .document(firebaseDocId)
                    .delete()
                    .await()
                Log.i("CleanupWorker", "$firebaseDocId ID'li Firestore kaydı silindi.")

            } else {
                Log.i("CleanupWorker", "$recordId ID'li kayıt ya bulunamadı ya da durumu DEPREM'e güncellenmiş.")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Temizlik sırasında hata: ${e.message}")
            // Hata durumunda tekrar dene
            Result.retry()
        }
    }
}