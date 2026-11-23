package com.bolm.mudif.ui.control

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bolm.mudif.ui.MudifActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivityDeviceReportBinding
import com.bolm.mudif.databinding.ItemDeviceReportBinding
import com.bolm.mudif.engine.DeviceReport

/**
 * F7, device report: the phone's real hardware facts, one row
 * per value (SoC, GPU renderer, RAM, storage, display, Vulkan,
 * battery). Dedicated activity, like TaskManagerActivity: a 12-row
 * report does not belong in a dialog. Read-only, no permissions.
 * Collection runs on a background thread (the EGL query creates
 * a short-lived offscreen context); the list is submitted on the
 * UI thread.
 */
class DeviceReportActivity : MudifActivity() {

    private lateinit var binding: ActivityDeviceReportBinding
    private val adapter = ReportAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.reportBack.setOnClickListener { finish() }
        binding.reportRefresh.setOnClickListener { loadReport() }
        binding.reportList.layoutManager = LinearLayoutManager(this)
        binding.reportList.adapter = adapter
        loadReport()
    }

    private fun loadReport() {
        binding.reportEmpty.visibility = View.GONE
        binding.reportEmpty.text = getString(R.string.device_report_loading)
        binding.reportEmpty.visibility = View.VISIBLE
        Thread {
            val rows = try {
                DeviceReport.collect(this)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                adapter.submit(rows)
                binding.reportEmpty.visibility =
                    if (rows.isEmpty()) View.VISIBLE else View.GONE
                if (rows.isEmpty()) {
                    binding.reportEmpty.text = getString(R.string.device_report_empty)
                }
            }
        }.start()
    }

    private class ReportAdapter :
        RecyclerView.Adapter<ReportAdapter.Holder>() {

        private var items: List<DeviceReport.Row> = emptyList()

        fun submit(list: List<DeviceReport.Row>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemDeviceReportBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class Holder(private val binding: ItemDeviceReportBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(row: DeviceReport.Row) {
                binding.reportLabel.text = itemView.context.getString(row.labelRes)
                binding.reportValue.text = row.value
            }
        }
    }
}
