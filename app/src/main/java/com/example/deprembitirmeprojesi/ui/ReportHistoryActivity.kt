package com.example.deprembitirmeprojesi.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.data.AppDatabase
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.example.deprembitirmeprojesi.mesh.MeshNetworkManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReportHistoryActivity : AppCompatActivity() {

    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_history)

        findViewById<View>(R.id.btnBackHistory).setOnClickListener {
            finish()
        }

        recyclerView = findViewById(R.id.recyclerViewHistory)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = HistoryAdapter(mutableListOf())
        recyclerView.adapter = adapter

        val targetSenderId = intent.getStringExtra("TARGET_SENDER_ID")
        observeReports(targetSenderId)
    }

    private fun observeReports(targetSenderId: String?) {
        lifecycleScope.launch {
            // Flow kullanarak veritabanındaki her değişikliği anlık takip et
            database.reportDao().getAllReportsFlow().collectLatest { reports ->
                adapter.updateList(reports)
                
                if (targetSenderId != null) {
                    val index = reports.indexOfFirst { it.senderId == targetSenderId }
                    if (index != -1) {
                        recyclerView.scrollToPosition(index)
                    }
                }
            }
        }
    }

    inner class HistoryAdapter(private var list: MutableList<DisasterReport>) : 
        RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        fun updateList(newList: List<DisasterReport>) {
            list = newList.toMutableList()
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtProfile: TextView = view.findViewById(R.id.txtProfileName)
            val txtStatus: TextView = view.findViewById(R.id.txtStatus)
            val txtBattery: TextView = view.findViewById(R.id.txtBattery)
            val txtMessage: TextView = view.findViewById(R.id.txtLastMessage)
            val txtLoc: TextView = view.findViewById(R.id.txtLocation)
            val txtTime: TextView = view.findViewById(R.id.txtTime)
            val txtRescueStatus: TextView = view.findViewById(R.id.txtRescueStatus)
            
            val txtBlood: TextView = view.findViewById(R.id.txtBloodType)
            val txtChronic: TextView = view.findViewById(R.id.txtChronic)
            val txtMeds: TextView = view.findViewById(R.id.txtMeds)
            val txtBirth: TextView = view.findViewById(R.id.txtBirthDate)
            val txtBuilding: TextView = view.findViewById(R.id.txtBuildingInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_report_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val dateStr = sdf.format(Date(item.lastSeenTimestamp))
            
            holder.txtProfile.text = if(item.userProfile.isNotEmpty()) item.userProfile else "👤 Bilinmeyen Kullanıcı"
            holder.txtBattery.text = if(item.batteryLevel.isNotEmpty()) item.batteryLevel else "🔋 Pil: --"
            holder.txtMessage.text = if(item.rawMessage.isNotEmpty()) "🆘 Son Mesaj: ${item.rawMessage}" else "Mesaj yok"
            holder.txtLoc.text = if(item.lastLocation.isNotEmpty()) item.lastLocation else "📍 Konum: Bilinmiyor"
            holder.txtTime.text = "🕒 Son Görülme: $dateStr"

            // Rescue Status UI
            updateStatusUI(holder.txtRescueStatus, item.status)

            // Status Değiştirme Özelliği
            holder.txtRescueStatus.setOnClickListener {
                val statuses = arrayOf("PENDING", "CLAIMED", "RESCUING", "RESCUED")
                val statusLabels = arrayOf("⏳ YARDIM BEKLİYOR", "🚑 EKİP YOLDA", "👷 MÜDAHALE EDİLİYOR", "✅ KURTARILDI")
                
                AlertDialog.Builder(this@ReportHistoryActivity)
                    .setTitle("Durum Güncelle")
                    .setItems(statusLabels) { _, which ->
                        val newStatus = statuses[which]
                        lifecycleScope.launch {
                            item.status = newStatus
                            item.lastSeenTimestamp = System.currentTimeMillis()
                            item.version++ // Versiyonu artır ki mesh ağında en güncel veri olsun
                            
                            database.reportDao().upsertReport(item)
                            
                            // MESH AĞINA YAY
                            try {
                                MeshNetworkManager.getInstance(this@ReportHistoryActivity).updateAndBroadcastStatus(item)
                            } catch (e: Exception) {
                                // Mesh başlatılmamış olabilir, sadece yerelde kalsın
                            }
                        }
                    }
                    .show()
            }

            bindExtraInfo(holder.txtBlood, "🩸 Kan Grubu: ${item.bloodType}", item.bloodType)
            bindExtraInfo(holder.txtChronic, "🏥 Kronik Hastalık: ${item.chronicIllness}", item.chronicIllness)
            bindExtraInfo(holder.txtMeds, "💊 Düzenli İlaç: ${item.regularMedication}", item.regularMedication)
            bindExtraInfo(holder.txtBirth, "📅 Doğum Tarihi: ${item.birthDate}", item.birthDate)
            
            val buildingText = if (item.apartmentInfo.isNotEmpty() || item.floorInfo.isNotEmpty()) {
                "🏢 Bina: ${item.apartmentInfo} / Kat: ${item.floorInfo}"
            } else ""
            bindExtraInfo(holder.txtBuilding, buildingText, buildingText)

            if (item.isConnected) {
                holder.txtStatus.text = "● BAĞLI"
                holder.txtStatus.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                holder.txtStatus.text = "○ KOPTU"
                holder.txtStatus.setTextColor(Color.parseColor("#D32F2F"))
            }
        }

        private fun updateStatusUI(textView: TextView, status: String) {
            when(status) {
                "CLAIMED" -> {
                    textView.text = "🚑 EKİP YOLDA"
                    textView.setBackgroundColor(Color.parseColor("#E3F2FD"))
                    textView.setTextColor(Color.parseColor("#1565C0"))
                }
                "RESCUING" -> {
                    textView.text = "👷 MÜDAHALE EDİLİYOR"
                    textView.setBackgroundColor(Color.parseColor("#F1F8E9"))
                    textView.setTextColor(Color.parseColor("#33691E"))
                }
                "RESCUED" -> {
                    textView.text = "✅ KURTARILDI"
                    textView.setBackgroundColor(Color.parseColor("#E8F5E9"))
                    textView.setTextColor(Color.parseColor("#1B5E20"))
                }
                else -> {
                    textView.text = "⏳ YARDIM BEKLİYOR"
                    textView.setBackgroundColor(Color.parseColor("#FFF3E0"))
                    textView.setTextColor(Color.parseColor("#E65100"))
                }
            }
        }

        private fun bindExtraInfo(textView: TextView, fullText: String, value: String) {
            if (value.isNotEmpty()) {
                textView.text = fullText
                textView.visibility = View.VISIBLE
            } else {
                textView.visibility = View.GONE
            }
        }

        override fun getItemCount() = list.size
    }
}
