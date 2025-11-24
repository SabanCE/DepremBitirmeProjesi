package com.example.deprembitirmeprojesi

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.deprembitirmeprojesi.databinding.ActivityMainBinding
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class MainActivity : AppCompatActivity(), AccelerometerHelper.AccelerometerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var accelerometerHelper: AccelerometerHelper
    private lateinit var adapter: EarthquakeAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var isAlarmPlaying = false

    // ViewModel'i başlat
    private val viewModel: MainViewModel by viewModels()

    //Grafik verileri için liste
    private val entries = mutableListOf<Entry>()
    private var timeOfIndex = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
        // Uygulama açılır açılmaz izin isteyelim
        requestLocationPermission()

        accelerometerHelper = AccelerometerHelper(this, this)
    }

    private fun setupUI() {
        setupChart()
        adapter = EarthquakeAdapter()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.btnSimulate.setOnClickListener {
            simulateEarthquake()
        }
    }

    private fun observeViewModel() {
        // Veritabanındaki kayıtları dinle ve listeyi güncelle
        viewModel.earthquakeRecords.observe(this) { list ->
            adapter.setData(list)
        }

        // ViewModel'den gelen Toast mesajlarını dinle ve göster
        viewModel.toastMessage.observe(this) { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        // Arayüz durumunu dinle ve UI'ı güncelle
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Safe -> {
                    binding.txtStatus.text = "Güvende"
                    binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.safe_green))
                }
                is UiState.ShakeDetected -> {
                    val statusText = "⚠️ Sarsıntı Algılandı!\nTeyit Bekleniyor..."
                    binding.txtStatus.text = statusText
                    binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
                }
                is UiState.Confirmed -> {
                    val statusText = "🚨 DEPREM KESİNLEŞTİ! \nYakında ${state.nearbyDevices} cihaz daha sallanıyor!"
                    binding.txtStatus.text = statusText
                    binding.txtStatus.setTextColor(Color.RED)

                    // ALARM VE ANİMASYONU SADECE TEYİT ALININCA BAŞLAT!
                    playAlarmSound()
                    playShakeAnimation()
                }
            }
        }
    }

    // 1. Deprem Olduğunda Burası Çalışır
    override fun onShakeDetected(force: Float) {
        if (hasLocationPermission()) {
            // Sadece ViewModel'i haberdar et. Alarm veya animasyon burada BAŞLATILMAZ.
            viewModel.onEarthquakeDetected(force)
        } else {
            requestLocationPermission()
            Toast.makeText(this, "Konum izni gerekli!", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission(){
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
    }

    private fun playShakeAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.1f)
        val animator = ObjectAnimator.ofPropertyValuesHolder(binding.cardStatus, scaleX, scaleY)
        animator.repeatCount = 5
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.duration = 300
        animator.start()
    }

    private fun playAlarmSound() {
        if (isAlarmPlaying) return
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)
            mediaPlayer?.start()
            isAlarmPlaying = true

            object : CountDownTimer(5000, 1000) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    stopAlarmSound()
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        isAlarmPlaying = false
    }

    private fun simulateEarthquake() {
        onShakeDetected(5.8f)
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

    // --- Grafik Çizim Fonksiyonları (UI ile ilgili olduğu için burada kalabilir) ---
    override fun onSensorChanged(x: Float, y: Float, z: Float) {
        binding.txtValues.text = "X: ${String.format("%.1f", x)} Y: ${String.format("%.1f", y)} Z: ${String.format("%.1f", z)}"
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        addEntryToChart(magnitude)
    }

    private fun setupChart() {
        binding.sensorChart.setTouchEnabled(true)
        binding.sensorChart.setPinchZoom(true)
        binding.sensorChart.description = Description().apply { text = "İvmeölçer Verisi" }

        val dataSet = LineDataSet(entries, "Sarsıntı")
        dataSet.color = Color.BLUE
        dataSet.setDrawCircles(false)
        dataSet.lineWidth = 2f

        binding.sensorChart.data = LineData(dataSet)
    }

    private fun addEntryToChart(value: Float) {
        val data = binding.sensorChart.data ?: return
        val set = data.getDataSetByIndex(0) ?: run {
            val newSet = LineDataSet(null, "Sarsıntı")
            data.addDataSet(newSet)
            newSet
        }
        data.addEntry(Entry(timeOfIndex++, value), 0)
        if (set.entryCount > 100) {
            set.removeEntry(0)
        }
        data.notifyDataChanged()
        binding.sensorChart.notifyDataSetChanged()
        binding.sensorChart.setVisibleXRangeMaximum(50f)
        binding.sensorChart.moveViewToX(data.entryCount.toFloat())
    }
}
