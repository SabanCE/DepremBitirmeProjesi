package com.example.deprembitirmeprojesi

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.launch

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        // Harita Fragmentini Hazırla
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Geri Dön Butonu
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish() // Sayfayı kapat, önceki sayfaya dön
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Varsayılan kamera (Türkiye Geneli)
        val turkey = LatLng(39.0, 35.0)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(turkey, 6f))

        // Harita tipi (Uydu görüntüsü mü normal mi? Normal daha temiz durur)
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL

        // Veritabanından verileri çek ve işaretle
        loadMarkers()
    }

    private fun loadMarkers() {
        lifecycleScope.launch {
            // Room veritabanından tüm raporları çek
            val reports = database.reportDao().getAllReports()

            // Sayacı güncelle
            findViewById<TextView>(R.id.txtCount).text = "Tespit Edilen: ${reports.size}"

            var lastPosition: LatLng? = null

            for (report in reports) {
                val position = parseLocation(report.rawMessage)
                if (position != null) {
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(position)
                            .title("YARDIM ÇAĞRISI")
                            .snippet(formatSnippet(report.rawMessage))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )
                    lastPosition = position
                }
            }

            // Eğer en az bir iğne varsa, son eklenen iğneye zoom yap
            if (lastPosition != null) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(lastPosition, 14f))
            }
        }
    }

    // Mesajın içinden "KONUM: 39.9,32.4" kısmını bulan fonksiyon
    private fun parseLocation(message: String): LatLng? {
        try {
            val lines = message.split("\n")
            // İçinde virgün olan ve sayı içeren satırı bulmaya çalış
            val locationLine = lines.find { it.contains(",") && (it.contains("KONUM") || it.contains("Lat") || it.matches(Regex(".*[0-9]+.*,.*[0-9]+.*"))) }

            if (locationLine != null) {
                // Sadece sayıları, noktayı ve virgülü bırak
                val cleanLine = locationLine.substringAfter(":").trim()
                val coords = cleanLine.split(",")

                if (coords.size >= 2) {
                    val lat = coords[0].trim().toDouble()
                    val lng = coords[1].trim().toDouble()
                    return LatLng(lat, lng)
                }
            }
        } catch (e: Exception) {
            Log.e("MapParser", "Konum hatası: ${e.message}")
        }
        return null
    }

    // Harita iğnesine tıklayınca görünecek kısa metin
    private fun formatSnippet(message: String): String {
        return if (message.length > 50) message.take(50) + "..." else message
    }
}