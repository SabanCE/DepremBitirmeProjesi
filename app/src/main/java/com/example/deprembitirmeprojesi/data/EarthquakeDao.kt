package com.example.deprembitirmeprojesi.data
//Veritabanı erişim nesnesi
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
@Dao
interface EarthquakeDao {
    // Yeni bir sarsıntı kaydet
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: EarthquakeRecord): Long // Geriye Long ID döndür

    // Son 10 sarsıntıyı getir (En yeniden eskiye)
    @Query("SELECT * FROM earthquake_records ORDER BY timestamp DESC LIMIT 10")
    fun getAllEarthquakes():Flow <List<EarthquakeRecord>>

    // Son sarsıntıyı getir
    @Query("SELECT * FROM earthquake_records ORDER BY timestamp DESC LIMIT 1")
    fun getLastEarthquake(): Flow<EarthquakeRecord?>

    /**
     * Belirtilen ID'ye sahip kaydı getirir. Worker tarafından kontrol için kullanılır.
     */
    @Query("SELECT * FROM earthquake_records WHERE id = :id")
    suspend fun getRecordById(id: Long): EarthquakeRecord?

    /**
     * Belirtilen ID'ye sahip kaydı siler.
     */
    @Query("DELETE FROM earthquake_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)

    /**
     * Belirtilen ID'ye sahip kaydın durumunu günceller.
     */
    @Query("UPDATE earthquake_records SET status = :newStatus WHERE id = :id")
    suspend fun updateStatus(id: Long, newStatus: String)

    // Tüm kayıtları sil (Testler için lazım olur)
    @Query("DELETE FROM earthquake_records")
    suspend fun deleteAll()
}
