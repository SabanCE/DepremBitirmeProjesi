package com.example.deprembitirmeprojesi.ui
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.deprembitirmeprojesi.R
import com.example.deprembitirmeprojesi.data.EarthquakeRecord
import com.example.deprembitirmeprojesi.util.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class EarthquakeAdapter : RecyclerView.Adapter<EarthquakeAdapter.ViewHolder>() {

    private var dataList = emptyList<EarthquakeRecord>()
    private val sdf = SimpleDateFormat(Constants.DATE_FORMAT_ADAPTER, Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtMagnitude: TextView = view.findViewById(R.id.txtMagnitude)
        val txtDate: TextView = view.findViewById(R.id.txtDate)
        val txtType: TextView = view.findViewById(R.id.txtType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_earthquake, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataList[position]

        holder.txtMagnitude.text = String.format("%.1f", item.magnitude)
        holder.txtType.text = item.address

        // Zaman damgasını (Long) okunabilir tarihe çevir
        holder.txtDate.text = sdf.format(Date(item.timestamp))
    }

    override fun getItemCount() = dataList.size

    fun setData(newList: List<EarthquakeRecord>) {
        dataList = newList
        notifyDataSetChanged() // Listeyi yenile
    }
}