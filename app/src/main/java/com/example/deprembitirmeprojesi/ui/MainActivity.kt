package com.example.deprembitirmeprojesi.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.databinding.ActivityMainBinding
import com.example.deprembitirmeprojesi.util.AccelerometerHelper
import com.example.deprembitirmeprojesi.util.Constants
import com.example.deprembitirmeprojesi.viewmodel.MainViewModel
import com.example.deprembitirmeprojesi.viewmodel.UiState
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class MainActivity : AppCompatActivity(), AccelerometerHelper.AccelerometerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var accelerometerHelper: AccelerometerHelper
    private lateinit var adapter: EarthquakeAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var isAlarmPlaying = false

    private val viewModel: MainViewModel by viewModels()
    private lateinit var auth: FirebaseAuth

    private val entries = mutableListOf<Entry>()
    private var timeOfIndex = 0f
    
    // Optimizasyon için son grafik güncelleme zamanı
    private var lastChartUpdateTime = 0L
    private val CHART_UPDATE_INTERVAL_MS = 100 // Grafiği her 100ms'de bir güncelle

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
        private const val REQUEST_CODE_BACKGROUND_LOCATION = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        // AÇILIŞTA HAYALET BAĞLANTILARI TEMİZLE
        lifecycleScope.launch {
            com.example.deprembitirmeprojesi.data.AppDatabase.getDatabase(this@MainActivity)
                .reportDao().clearAllConnections()
        }

        setupUI()
        observeViewModel()
        checkPermissions()
        checkBatteryOptimizations()

        accelerometerHelper = AccelerometerHelper(this, this)
        
        // Kritik Takip Modu başlangıç ayarı
        val sharedPref = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isCriticalMode = sharedPref.getBoolean("CRITICAL_MODE", false)
        binding.switchCriticalMode.isChecked = isCriticalMode
        
        if (isCriticalMode) {
            startEarthquakeService()
        }
    }

    private fun startEarthquakeService() {
        if (!hasLocationPermission()) return
        
        val serviceIntent = Intent(this, com.example.deprembitirmeprojesi.service.EarthquakeService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("MainActivity", "Deprem Takip Servisi Başlatıldı.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Servis başlatılamadı: ${e.message}")
        }
    }

    private fun stopEarthquakeService() {
        val serviceIntent = Intent(this, com.example.deprembitirmeprojesi.service.EarthquakeService::class.java)
        stopService(serviceIntent)
        Log.d("MainActivity", "Deprem Takip Servisi Durduruldu.")
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Kesintisiz Takip")
                    .setMessage("Deprem uyarılarını uygulama kapalıyken de alabilmek için lütfen pil kısıtlamasını 'Kısıtlama Yok' (Unrestricted) olarak ayarlayın.")
                    .setPositiveButton("Ayarlara Git") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Daha Sonra", null)
                    .show()
            }
        }
    }

    private fun setupUI() {
        setupChart()
        adapter = EarthquakeAdapter()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Kritik Mod Switch Mantığı
        binding.switchCriticalMode.setOnCheckedChangeListener { _, isChecked ->
            val sharedPref = getSharedPreferences("Settings", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putBoolean("CRITICAL_MODE", isChecked)
                apply()
            }
            
            if (isChecked) {
                startEarthquakeService()
                Toast.makeText(this, "Kritik Takip Modu Aktif (7/24 Koruma)", Toast.LENGTH_SHORT).show()
            } else {
                stopEarthquakeService()
                Toast.makeText(this, "Standart Mod: Arka plan takibi durduruldu", Toast.LENGTH_SHORT).show()
            }
        }

        // Simülasyon butonu (Test amaçlı)
        binding.btnSimulate.setOnClickListener {
            val intent = Intent(this, UserEmergencyActivity::class.java)
            startActivity(intent)
        }

        binding.btnMenu.setOnClickListener { view ->
            showPopupMenu(view)
        }
    }

    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(this, view)
        popupMenu.menuInflater.inflate(R.menu.main_menu, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    true
                }
                R.id.menu_logout -> {
                    auth.signOut()
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    private fun observeViewModel() {
        viewModel.earthquakeRecords.observe(this) { list ->
            adapter.setData(list)
        }

        viewModel.toastMessage.observe(this) { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Safe -> {
                    binding.txtStatus.text = "Güvende"
                    binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.safe_green))
                }
                is UiState.Analysing -> {
                    binding.txtStatus.text = "🔍 Sarsıntı Analiz Ediliyor...\nLütfen Bekleyin"
                    binding.txtStatus.setTextColor(Color.BLUE)
                }
                is UiState.RiskDetected -> {
                    handleRiskUI(state.score, state.level)
                }
                is UiState.Confirmed -> {
                    binding.txtStatus.text = "🚨 DEPREM DOĞRULANDI!\nŞiddet: ${state.magnitude}\nÇevredeki Etkilenen Cihazlar: ${state.nearby}"
                    binding.txtStatus.setTextColor(Color.RED)
                    playAlarmSound()
                    playShakeAnimation()
                }
            }
        }

        viewModel.navigateToEmergencyMode.observe(this) { shouldNavigate ->
            if (shouldNavigate) {
                val intent = Intent(this, UserEmergencyActivity::class.java)
                startActivity(intent)
                viewModel.onNavigationToEmergencyModeComplete()
            }
        }
    }

    override fun onShakeDetected(force: Float) {
        // Debounce (Zaman barajı) ViewModel içinde zaten kontrol ediliyor.
        if (hasLocationPermission()) {
            viewModel.onEarthquakeDetected(force)
        } else {
            checkPermissions()
            Toast.makeText(this, "Deprem verisi için konum izni gerekli!", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleRiskUI(score: Double, level: String) {
        when (level) {
            Constants.LEVEL_LOW -> {
                binding.txtStatus.text = "🟡 Düşük Risk\nSarsıntı algılandı, dikkatli olun"
                binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
            }
            Constants.LEVEL_MEDIUM -> {
                binding.txtStatus.text = "🟠 Orta Risk\nBölgenizde deprem olabilir"
                binding.txtStatus.setTextColor(Color.parseColor("#FFA500")) // Orange
                playShakeAnimation()
            }
            Constants.LEVEL_HIGH -> {
                binding.txtStatus.text = "🔴 YÜKSEK RİSK\nDEPREM! Güvenli alana geç"
                binding.txtStatus.setTextColor(Color.RED)
                playAlarmSound()
                playShakeAnimation()
            }
        }
        Log.d("MainActivity", "Risk Level UI Updated: $level (Score: $score)")
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Android 12+ (API 31) Bluetooth İzinleri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            // İzinler tamamsa servisi başlat
            startEarthquakeService()
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this)
                    .setTitle("Arka Plan Konum İzni Gerekli")
                    .setMessage("Uygulama kapalıyken deprem sarsıntılarını konumunuzla birlikte bildirebilmek için konum iznini 'Her zaman izin ver' olarak ayarlamanız gerekmektedir.")
                    .setPositiveButton("Ayarlara Git") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            REQUEST_CODE_BACKGROUND_LOCATION
                        )
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Normal konum izni verildi, servisi şimdi başlatabiliriz
                    startEarthquakeService()
                    checkBackgroundLocationPermission()
                } else {
                    Toast.makeText(this, "Temel izinler olmadan uygulama düzgün çalışamaz.", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_CODE_BACKGROUND_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Arka plan konum izni onaylandı.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Deprem uyarılarını arka planda alabilmek için bu izin kritik!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun playShakeAnimation() {
        ObjectAnimator.ofPropertyValuesHolder(
            binding.cardStatus,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.1f)
        ).apply {
            repeatCount = 5
            repeatMode = ObjectAnimator.REVERSE
            duration = 300
            start()
        }
    }

    private fun playAlarmSound() {
        if (isAlarmPlaying) return
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)?.apply {
                isLooping = false
                isAlarmPlaying = true
                start()
                Handler(Looper.getMainLooper()).postDelayed({
                    stopAlarmSound()
                }, 4000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.release()
        mediaPlayer = null
        isAlarmPlaying = false
    }

    override fun onResume() {
        super.onResume()
        accelerometerHelper.start()
    }

    override fun onPause() {
        super.onPause()
        accelerometerHelper.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmSound()
    }

    override fun onSensorChanged(x: Float, y: Float, z: Float) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastChartUpdateTime > CHART_UPDATE_INTERVAL_MS) {
            binding.txtValues.text = "X: ${String.format("%.1f", x)} Y: ${String.format("%.1f", y)} Z: ${String.format("%.1f", z)}"
            val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            addEntryToChart(magnitude)
            lastChartUpdateTime = currentTime
        }
    }

    private fun setupChart() {
        binding.sensorChart.setTouchEnabled(true)
        binding.sensorChart.setPinchZoom(true)
        binding.sensorChart.description = Description().apply { text = "İvmeölçer Verisi" }
        val dataSet = LineDataSet(entries, "Sarsıntı").apply {
            color = Color.BLUE
            setDrawCircles(false)
            lineWidth = 2f
        }
        binding.sensorChart.data = LineData(dataSet)
    }

    private fun addEntryToChart(value: Float) {
        val data = binding.sensorChart.data ?: return
        val set = data.getDataSetByIndex(0) ?: return
        data.addEntry(Entry(timeOfIndex++, value), 0)
        if (set.entryCount > 100) {
            set.removeFirst()
        }
        data.notifyDataChanged()
        binding.sensorChart.notifyDataSetChanged()
        binding.sensorChart.setVisibleXRangeMaximum(50f)
        binding.sensorChart.moveViewToX(data.entryCount.toFloat())
    }
}
