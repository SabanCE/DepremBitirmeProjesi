package com.example.deprembitirmeprojesi.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.databinding.ActivityMainBinding
import com.example.deprembitirmeprojesi.util.AccelerometerHelper
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

    companion object {
        private const val REQUEST_CODE_LOCATION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        setupUI()
        observeViewModel()
        requestLocationPermission()

        accelerometerHelper = AccelerometerHelper(this, this)
    }

    private fun setupUI() {
        setupChart()
        adapter = EarthquakeAdapter()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        // BU KOD SADECE TEST İÇİNDİR !!
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
                is UiState.ShakeDetected -> {
                    binding.txtStatus.text = "⚠️ Sarsıntı Algılandı!\nTeyit Bekleniyor..."
                    binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
                }
                is UiState.Confirmed -> {
                    binding.txtStatus.text = "🚨 DEPREM KESİNLEŞTİ! \nYakında ${state.nearbyDevices} cihaz daha sallanıyor!"
                    binding.txtStatus.setTextColor(Color.RED)
                    playAlarmSound()
                    playShakeAnimation()
                }
            }
        }

        // Acil Durum Moduna geçiş emrini dinle
        viewModel.navigateToEmergencyMode.observe(this) { shouldNavigate ->
            if (shouldNavigate) {
                val intent = Intent(this, UserEmergencyActivity::class.java)
                startActivity(intent)
                viewModel.onNavigationToEmergencyModeComplete() // Yönlendirme sonrası sinyali sıfırla
            }
        }
    }

    override fun onShakeDetected(force: Float) {
        if (hasLocationPermission()) {
            viewModel.onEarthquakeDetected(force)
        } else {
            requestLocationPermission()
            Toast.makeText(this, "Deprem verisi için konum izni gerekli!", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_CODE_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_LOCATION) {
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Konum izni olmadan ana özellikler çalışamaz.", Toast.LENGTH_LONG).show()
            }
        }
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
                isLooping = false // Döngüyü kapat
                isAlarmPlaying = true
                start()
                // 4 saniye sonra alarmı durdur
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
        binding.txtValues.text = "X: ${String.format("%.1f", x)} Y: ${String.format("%.1f", y)} Z: ${String.format("%.1f", z)}"
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        addEntryToChart(magnitude)
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
