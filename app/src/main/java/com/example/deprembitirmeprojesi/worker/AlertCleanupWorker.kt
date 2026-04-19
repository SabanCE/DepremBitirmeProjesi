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
        val firebaseDocId = inputData.getString("KEY_FIREBASE_DOC_ID")

        if (firebaseDocId.isNullOrBlank()) {
            Log.e("CleanupWorker", "Firebase Doc ID gelmedi, işlem iptal.")
            return Result.failure()
        }

        return try {
            val firestore = Firebase.firestore
            val docRef = firestore.collection(Constants.FIRESTORE_COLLECTION_ALERTS).document(firebaseDocId)
            
            val snapshot = docRef.get().await()
            
            if (snapshot.exists()) {
                val status = snapshot.getString(Constants.FIELD_STATUS)
                
                if (status == Constants.STATUS_ANALYSING) {
                    // Eğer 30-45 saniye geçmesine rağmen hala ANALYSING ise sil
                    docRef.delete().await()
                    Log.i("CleanupWorker", "Teyit alınamadı. $firebaseDocId ID'li ANALYSING kaydı Firebase'den temizlendi.")
                } else {
                    Log.i("CleanupWorker", "$firebaseDocId ID'li kayıt DEPREM olarak onaylandığı için silinmedi.")
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Temizlik sırasında hata: ${e.message}")
            Result.retry()
        }
    }
}