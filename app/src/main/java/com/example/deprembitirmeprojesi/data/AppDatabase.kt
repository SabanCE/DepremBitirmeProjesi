package com.example.deprembitirmeprojesi.data

//Veritabanı oluşturucu

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Projenin Room veritabanını temsil eden ana sınıf.
 *
 * @Database anotasyonu, bu sınıfın bir Room veritabanı olduğunu belirtir.
 * entities = Veritabanında tablo olarak kullanılacak veri sınıflarının listesi.
 * version = Veritabanı şemasının versiyon numarası. Şema değiştiğinde bu numara artırılmalıdır.
 */
@Database(entities = [EarthquakeRecord::class, DisasterReport::class], version = 6) // VERSİYON GÜNCELLENDİ (YENİ ALANLAR EKLENDİ)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Veritabanı erişim nesnesini (DAO - Data Access Object) döndüren soyut bir metot.
     * Room kütüphanesi, bu metodun gövdesini bizim için otomatik olarak oluşturur.
     */
    abstract fun earthquakeDao(): EarthquakeDao
    abstract fun reportDao(): ReportDao

    /**
     * companion object, bu sınıfın bir örneği (instance) olmadan doğrudan erişilebilen
     * metotlar ve değişkenler tanımlamamızı sağlar (Java'daki static metotlar gibi).
     * Bu, Singleton tasarım desenini uygulamak için kullanılır.
     */
    companion object {
        /**
         * @Volatile anotasyonu, INSTANCE değişkenine yapılan yazma işlemlerinin
         * anında diğer tüm thread'ler tarafından görünür olmasını garanti eder.
         * Bu, birden fazla thread'in aynı anda veritabanı örneği oluşturmasını engeller.
         */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Veritabanı için Singleton örneğini döndüren metot.
         * Bu metot, uygulama boyunca AppDatabase'in sadece TEK BİR örneğinin
         * oluşturulmasını ve kullanılmasını sağlar. Bu, performansı artırır ve kaynakları korur.
         */
        fun getDatabase(context: Context): AppDatabase {
            // Eğer INSTANCE null ise (yani daha önce hiç oluşturulmadıysa),
            // synchronized bloğu içine gir. Değilse, mevcut örneği döndür.
            return INSTANCE ?: synchronized(this) {
                // synchronized bloğu, aynı anda sadece bir thread'in bu kod bloğunu
                // çalıştırabilmesini sağlar. Bu, yanlışlıkla iki tane veritabanı
                // örneği oluşturulmasını engeller.
                val instance = Room.databaseBuilder(
                    context.applicationContext, // Uygulamanın genel context'i
                    AppDatabase::class.java,    // Oluşturulacak veritabanı sınıfı
                    "earthquake_db"          // Diskte oluşturulacak veritabanı dosyasının adı
                ).fallbackToDestructiveMigration().build() // Veritabanı örneğini oluşturur.
                
                // Oluşturulan yeni örneği INSTANCE değişkenine ata.
                INSTANCE = instance
                // Oluşturulan örneği dışarıya döndür.
                instance
            }
        }
    }
}
