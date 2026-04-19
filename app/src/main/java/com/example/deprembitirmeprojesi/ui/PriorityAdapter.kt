package com.example.deprembitirmeprojesi.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.data.DisasterReport
import com.example.deprembitirmeprojesi.util.RiskCalculator

class PriorityAdapter(
    private val onReportClick: (DisasterReport) -> Unit
) : RecyclerView.Adapter<PriorityAdapter.ViewHolder>() {

    private var reports = emptyList<DisasterReport>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtMagnitude)
        val txtStatus: TextView = view.findViewById(R.id.txtType)
        val txtTime: TextView = view.findViewById(R.id.txtDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_earthquake, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val report = reports[position]
        val riskScore = RiskCalculator.calculateRiskScore(report)
        val isConnected = report.isConnected

        // Bağlantı durumuna göre isim başına simge ekle
        val statusIcon = if (isConnected) "● " else "○ "
        holder.txtName.text = statusIcon + (if (report.userProfile.isNotEmpty()) report.userProfile else "Bilinmeyen")
        
        // Risk rengini uygula
        holder.txtName.setTextColor(getRiskColor(riskScore))
        
        // Bağlantı yoksa ismi biraz sönükleştir
        holder.txtName.alpha = if (isConnected) 1.0f else 0.6f

        val statusLabel = when(report.status) {
            "CLAIMED" -> "🚑 EKİP YOLDA"
            "RESCUING" -> "👷 MÜDAHALE"
            "RESCUED" -> "✅ KURTARILDI"
            else -> "⏳ YARDIM BEKLİYOR"
        }
        
        holder.txtStatus.text = "$statusLabel | Risk: %$riskScore"
        
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        holder.txtTime.text = sdf.format(java.util.Date(report.lastSeenTimestamp))

        holder.itemView.setOnClickListener { onReportClick(report) }
    }

    private fun getRiskColor(score: Int): Int {
        return when {
            score >= 70 -> Color.RED
            score >= 40 -> Color.parseColor("#FFA500")
            else -> Color.parseColor("#2E7D32")
        }
    }

    override fun getItemCount() = reports.size

    fun setData(newList: List<DisasterReport>) {
        reports = newList
        notifyDataSetChanged()
    }
}
