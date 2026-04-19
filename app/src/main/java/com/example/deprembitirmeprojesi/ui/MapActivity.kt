package com.example.deprembitirmeprojesi.ui

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.example.deprembitirmeprojesi.util.RiskCalculator
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.chip.ChipGroup
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import kotlinx.coroutines.launch

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var clusterManager: ClusterManager<ReportClusterItem>
    
    private var allReports = listOf<DisasterReport>()
    private var currentFilter = "ALL"
    private var currentZoom: Float = 0f

    private var iconCritical: BitmapDescriptor? = null
    private var iconWarning: BitmapDescriptor? = null
    private var iconSafe: BitmapDescriptor? = null
    private var iconDisconnected: BitmapDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupFilters()

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    private fun setupFilters() {
        findViewById<ChipGroup>(R.id.chipGroupFilter).setOnCheckedStateChangeListener { group, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipCritical -> "CRITICAL"
                R.id.chipConnected -> "CONNECTED"
                R.id.chipDisconnected -> "DISCONNECTED"
                R.id.chipHelp -> "HELP"
                else -> "ALL"
            }
            applyFilter()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        currentZoom = map.cameraPosition.zoom
        
        // Marker ikonlarını ana thread üzerinde önceden oluştur (Background thread crashini önlemek için)
        initMarkerIcons()

        setupClusterManager()

        val lat = intent.getDoubleExtra("LAT", 0.0)
        val lng = intent.getDoubleExtra("LNG", 0.0)

        if (lat != 0.0 && lng != 0.0) {
            // Eğer belirli bir koordinat (mesaja tıklanarak) gelmişse oraya odaklan
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 15f))
        } else {
            // Varsayılan Türkiye görünümü
            val turkey = LatLng(39.0, 35.0)
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(turkey, 6f))
        }

        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
        loadData()
    }

    private fun setupClusterManager() {
        clusterManager = ClusterManager(this, googleMap)
        googleMap.setOnCameraIdleListener {
            currentZoom = googleMap.cameraPosition.zoom
            clusterManager.onCameraIdle()
        }
        googleMap.setOnMarkerClickListener(clusterManager)
        
        clusterManager.renderer = object : DefaultClusterRenderer<ReportClusterItem>(this, googleMap, clusterManager) {
            override fun onBeforeClusterItemRendered(item: ReportClusterItem, markerOptions: MarkerOptions) {
                markerOptions.icon(getMarkerIcon(item.riskScore, item.isConnected))
                markerOptions.title(item.title)
                markerOptions.snippet(item.snippet)
            }

            override fun shouldRenderAsCluster(cluster: com.google.maps.android.clustering.Cluster<ReportClusterItem>): Boolean {
                // Zoom seviyesi 15'ten büyükse artık gruplama yapma, pinleri tek tek göster
                return if (currentZoom > 15f) false else cluster.size > 1
            }
        }

        clusterManager.setOnClusterItemInfoWindowClickListener { item ->
            val intent = android.content.Intent(this, ReportHistoryActivity::class.java)
            intent.putExtra("TARGET_SENDER_ID", item.getReport().senderId)
            startActivity(intent)
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            allReports = database.reportDao().getAllReports()
            applyFilter()
        }
    }

    private fun applyFilter() {
        if (!::googleMap.isInitialized || !::clusterManager.isInitialized) return

        val connectedIds = intent.getStringArrayListExtra("CONNECTED_IDS") ?: arrayListOf<String>()
        clusterManager.clearItems()
        
        val filtered = allReports.filter { report ->
            val isConnected = connectedIds.contains(report.senderId)
            val riskScore = RiskCalculator.calculateRiskScore(report)
            val msg = report.rawMessage.uppercase()

            when (currentFilter) {
                "CRITICAL" -> riskScore >= 70
                "CONNECTED" -> isConnected
                "DISCONNECTED" -> !isConnected
                "HELP" -> msg.contains("YARDIM") || msg.contains("ENKAZ") || msg.contains("YARALI")
                else -> true
            }
        }.sortedByDescending { RiskCalculator.calculateRiskScore(it) } // RİSK SKORUNA GÖRE SIRALA

        findViewById<TextView>(R.id.txtCount).text = "Filtrelenen: ${filtered.size} / Toplam: ${allReports.size}"
        findViewById<TextView>(R.id.txtAreaCount).text = "🎯 Alan Taraması: ${filtered.size} Kişi"

        // Alt taraftaki Öncelikli Müdahale Listesini Güncelle
        updatePriorityList(filtered, connectedIds)

        var focusedOnFirst = false
        val hasSpecificTarget = intent.hasExtra("LAT")

        // 1. Önce bağlı bir cihaza odaklanmayı dene
        for (report in filtered) {
            val position = parseLocation(report.lastLocation.ifEmpty { report.rawMessage })
            if (position != null) {
                val isConnected = connectedIds.contains(report.senderId)
                val riskScore = RiskCalculator.calculateRiskScore(report)
                val riskLevel = RiskCalculator.getRiskLevel(riskScore)

                val item = ReportClusterItem(
                    report,
                    position,
                    if (report.userProfile.isNotEmpty()) report.userProfile else "Bilinmeyen",
                    "Risk: %$riskScore ($riskLevel) | Pil: ${report.batteryLevel}",
                    riskScore,
                    isConnected
                )
                clusterManager.addItem(item)

                if (!hasSpecificTarget && isConnected && !focusedOnFirst) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 14f))
                    focusedOnFirst = true
                }
            }
        }

        // 2. Eğer hiç bağlı cihaz yoksa ve özel bir hedef belirtilmemişse, listedeki ilk (en riskli/son görülen) cihaza odaklan
        if (!hasSpecificTarget && !focusedOnFirst && filtered.isNotEmpty()) {
            val firstReport = filtered.first()
            val firstPosition = parseLocation(firstReport.lastLocation.ifEmpty { firstReport.rawMessage })
            firstPosition?.let {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 14f))
                focusedOnFirst = true
            }
        }

        clusterManager.cluster()
    }

    private fun updatePriorityList(reports: List<DisasterReport>, connectedIds: List<String>) {
        val rvPriority = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPriority)
        val adapter = PriorityAdapter { report ->
            // Listedeki birine tıklandığında haritada ona odaklan (Maksimum yakınlık: 19f)
            val position = parseLocation(report.lastLocation.ifEmpty { report.rawMessage })
            position?.let {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 19f))
            }
        }
        rvPriority.adapter = adapter
        rvPriority.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        // Listeyi risk skoruna göre en yüksekten düşüğe sıralayarak göster
        adapter.setData(reports.sortedByDescending { RiskCalculator.calculateRiskScore(it) })
    }

    private fun initMarkerIcons() {
        try {
            iconCritical = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
            iconWarning = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
            iconSafe = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
            iconDisconnected = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)
        } catch (e: Exception) {
            Log.e("MapActivity", "Error initializing icons", e)
        }
    }

    private fun getMarkerIcon(score: Int, isConnected: Boolean): BitmapDescriptor {
        if (!isConnected) return iconDisconnected ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)
        
        return when {
            score >= 70 -> iconCritical ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
            score >= 40 -> iconWarning ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
            else -> iconSafe ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
        }
    }

    private fun parseLocation(locationStr: String): LatLng? {
        try {
            val cleanStr = locationStr.replace("📍", "").trim()
            if (cleanStr.contains(",")) {
                val coords = cleanStr.split(",")
                val lat = coords[0].replace(Regex("[^0-9.-]"), "").toDoubleOrNull()
                val lng = coords[1].replace(Regex("[^0-9.-]"), "").toDoubleOrNull()
                if (lat != null && lng != null) return LatLng(lat, lng)
            }
            val regex = Regex("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)")
            val match = regex.find(locationStr)
            if (match != null) return LatLng(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
        } catch (e: Exception) {}
        return null
    }
}
