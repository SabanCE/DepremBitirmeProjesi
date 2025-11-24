package com.example.deprembitirmeprojesi
//Veritabanı erişim nesnesi
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.deprembitirmeprojesi.EarthquakeRecord
import kotlinx.coroutines.flow.Flow
@Dao
interface EarthquakeDao {
    // Yeni bir sarsıntı kaydet
    @Insert
    suspend fun insert(record: EarthquakeRecord)

    // Son 10 sarsıntıyı getir (En yeniden eskiye)
    @Query("SELECT * FROM earthquakes ORDER BY timestamp DESC LIMIT 10")
    fun getAllEarthquakes():Flow <List<EarthquakeRecord>>

    // Son sarsıntıyı getir
    @Query("SELECT * FROM earthquakes ORDER BY timestamp DESC LIMIT 1")
    fun getLastEarthquake(): Flow<EarthquakeRecord?>

    // Tüm kayıtları sil (Testler için lazım olur)
    @Query("DELETE FROM earthquakes")
    suspend fun deleteAll()
}
