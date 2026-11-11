package com.bolm.mudif.ui.control

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivityTaskManagerBinding
import com.bolm.mudif.databinding.ItemProcessBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.TerminalSession
import com.bolm.mudif.ui.MudifDialog

/**
 * Real-computer #10 — guest task manager: the live process list of
 * the Debian guest (CPU / RAM per process) with a kill button.
 * Built on the existing one-shot proot machinery (TerminalSession);
 * the guest must have procps (ps) — the image bakes it in (it is
 * also what isDesktopRunning and the kill helpers rely on).
 *
 * Safety: PID 1 (the container init) can never be killed from here
 * — killing it would kill the whole guest. Every kill asks first.
 */
class TaskManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskManagerBinding
    private val adapter = ProcessAdapter { process -> confirmKill(process) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.taskBack.setOnClickListener { finish() }
        binding.taskRefresh.setOnClickListener { loadProcesses() }
        binding.taskList.layoutManager = LinearLayoutManager(this)
        binding.taskList.adapter = adapter
        loadProcesses()
    }

    private fun loadProcesses() {
        binding.taskEmpty.visibility = View.GONE
        binding.taskEmpty.text = getString(R.string.task_manager_loading)
        binding.taskEmpty.visibility = View.VISIBLE
        Thread {
            val list = if (DebianSystem.isReady(this)) readProcesses() else emptyList()
            runOnUiThread {
                adapter.submit(list)
                binding.taskEmpty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                if (list.isEmpty()) {
                    binding.taskEmpty.text = getString(R.string.task_manager_empty)
                }
            }
        }.start()
    }

    /**
     * One guest call: ps sorted by CPU, top 50. The comm field is
     * last and never contains spaces, so a 6-way split is exact.
     */
    private fun readProcesses(): List<GuestProcess> {
        val r = TerminalSession.runCommandDebian(
            this,
            "ps -eo pid,ppid,%cpu,%mem,rss,comm --sort=-%cpu 2>/dev/null | head -51",
            idleTimeoutSec = 30,
            maxTimeoutSec = 60
        )
        if (r.exitCode != 0) return emptyList()
        val out = ArrayList<GuestProcess>()
        for (line in r.output.lines().drop(1)) {
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 6) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val ppid = parts[1].toIntOrNull() ?: 0
            val cpu = parts[2].toDoubleOrNull() ?: 0.0
            val mem = parts[3].toDoubleOrNull() ?: 0.0
            val rssKb = parts[4].toLongOrNull() ?: 0L
            out.add(GuestProcess(pid, ppid, cpu, mem, rssKb, parts[5]))
        }
        return out
    }

    private fun confirmKill(process: GuestProcess) {
        if (process.pid <= 1) {
            Toast.makeText(this, getString(R.string.ui_task_cannot_kill_pid1), Toast.LENGTH_SHORT).show()
            return
        }
        MudifDialog.info(
            this,
            getString(R.string.task_manager_title),
            getString(R.string.task_manager_kill_confirm, "${process.comm} (${process.pid})"),
            getString(R.string.ui_stop),
            onPositive = { killProcess(process) },
            negative = getString(R.string.ok)
        )
    }

    private fun killProcess(process: GuestProcess) {
        Thread {
            val r = TerminalSession.runCommandDebian(
                this,
                "kill -9 ${process.pid} 2>&1; echo KILLED",
                idleTimeoutSec = 15,
                maxTimeoutSec = 30
            )
            runOnUiThread {
                if (r.exitCode == 0 && r.output.contains("KILLED")) {
                    Toast.makeText(this, R.string.task_manager_killed, Toast.LENGTH_SHORT).show()
                    loadProcesses()
                } else {
                    Toast.makeText(this, R.string.task_manager_kill_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    data class GuestProcess(
        val pid: Int,
        val ppid: Int,
        val cpu: Double,
        val mem: Double,
        val rssKb: Long,
        val comm: String
    ) {
        val memMb: String get() = "%.1f MB".format(rssKb / 1024.0)
    }

    private class ProcessAdapter(
        private val onKill: (GuestProcess) -> Unit
    ) : RecyclerView.Adapter<ProcessAdapter.Holder>() {

        private var items: List<GuestProcess> = emptyList()

        fun submit(list: List<GuestProcess>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemProcessBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class Holder(private val binding: ItemProcessBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(p: GuestProcess) {
                binding.processName.text = p.comm
                binding.processDetail.text =
                    "PID ${p.pid} · CPU ${p.cpu}% · MEM ${p.mem}% · ${p.memMb}"
                binding.btnKill.setOnClickListener { onKill(p) }
            }
        }
    }
}
