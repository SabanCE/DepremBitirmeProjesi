package com.example.deprembitirmeprojesi.ui

import com.example.deprembitirmeprojesi.data.DisasterReport
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem

class ReportClusterItem(
    private val report: DisasterReport,
    private val latLng: LatLng,
    private val title: String,
    private val snippet: String,
    val riskScore: Int,
    val isConnected: Boolean,
    val status: String
) : ClusterItem {

    override fun getPosition(): LatLng = latLng
    override fun getTitle(): String = title
    override fun getSnippet(): String = snippet
    override fun getZIndex(): Float? = null
    
    fun getReport(): DisasterReport = report

    // ÖNEMLİ: ClusterManager'ın her öğeyi benzersiz tanıması için equals ve hashCode ekliyoruz
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReportClusterItem) return false
        return report.senderId == other.report.senderId
    }

    override fun hashCode(): Int {
        return report.senderId.hashCode()
    }
}
