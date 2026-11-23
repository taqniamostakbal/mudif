package com.bolm.mudif.ui.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivitySharedFilesBinding
import com.bolm.mudif.databinding.ItemSharedFileBinding
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.SharedFiles
import com.bolm.mudif.ui.MudifActivity
import com.bolm.mudif.ui.MudifDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F3, file transfer phone <> desktop. The bridge already exists
 * (Android <external-files>/mudif-shared bound to guest
 * /mnt/mudif-shared at boot), so this screen is Android UI only:
 * send a file (system picker -> copy into the shared folder with
 * progress + a toast naming the guest path) and browse the shared
 * files (open in the right app, or share, via a FileProvider).
 * The copy streams in chunks to "<name>.part" and renames to the
 * final name only when complete; a name clash gets " (2)", never
 * an overwrite. It never touches /tmp and never deletes a user
 * file (a failed copy removes only its own .part temp).
 * A desktop shortcut to /mnt/mudif-shared is ensured on first
 * open (only when the file is missing, only when the guest
 * session is alive).
 */
class SharedFilesActivity : MudifActivity() {

    private lateinit var binding: ActivitySharedFilesBinding
    private val adapter = FilesAdapter(
        onOpen = { openFile(it) },
        onShare = { shareFile(it) }
    )

    private val sendPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) sendFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharedFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.sharedBack.setOnClickListener { finish() }
        binding.sharedSend.setOnClickListener { sendPicker.launch(arrayOf("*/*")) }
        binding.sharedCopyPath.setOnClickListener { copyAndroidPath() }
        binding.sharedAndroidPath.text = RootfsManager.sharedDir(this).absolutePath
        binding.sharedGuestPath.text = RootfsManager.SHARED_GUEST_PATH
        binding.sharedList.layoutManager = LinearLayoutManager(this)
        binding.sharedList.adapter = adapter
        loadFiles()
        ensureGuestShortcut()
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    private fun copyAndroidPath() {
        val path = RootfsManager.sharedDir(this).absolutePath
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mudif-shared", path))
        Toast.makeText(this, getString(R.string.shared_folder_copied), Toast.LENGTH_SHORT).show()
    }

    private fun loadFiles() {
        Thread {
            val dir = RootfsManager.sharedDir(this)
            val files = (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && !it.name.endsWith(".part") }
                .sortedByDescending { it.lastModified() }
            runOnUiThread {
                adapter.submit(files)
                binding.sharedEmpty.visibility =
                    if (files.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun sendFile(uri: Uri) {
        val progress = MudifDialog.progress(this, getString(R.string.shared_files_sending))
        progress.setIndeterminate(true)
        progress.setStatus(getString(R.string.shared_files_sending))
        Thread {
            var part: File? = null
            try {
                var name: String? = null
                var total = -1L
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = cursor.getString(nameIdx)
                        if (sizeIdx >= 0) total = cursor.getLong(sizeIdx)
                    }
                }
                val dir = RootfsManager.sharedDir(this)
                val finalName = SharedFiles.uniqueName(dir, name ?: "file")
                val target = File(dir, finalName)
                part = File(dir, "$finalName.part")
                var done = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 1000L) / total).toInt()
                                val status = "${SharedFiles.formatSize(done)} / ${SharedFiles.formatSize(total)}"
                                runOnUiThread {
                                    progress.setProgress(pct)
                                    progress.setStatus(status)
                                }
                            }
                        }
                    }
                } ?: throw RuntimeException(getString(R.string.shared_files_open_failed))
                if (!part.renameTo(target)) {
                    throw RuntimeException(getString(R.string.shared_files_save_failed))
                }
                part = null
                val guestPath = "${RootfsManager.SHARED_GUEST_PATH}/$finalName"
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(
                        this,
                        getString(R.string.shared_files_sent, guestPath),
                        Toast.LENGTH_LONG
                    ).show()
                    loadFiles()
                }
            } catch (e: Exception) {
                // A failed copy removes only its own .part temp;
                // no user file is ever touched.
                try { part?.delete() } catch (_: Exception) { }
                val message = e.message ?: getString(R.string.shared_files_failed)
                runOnUiThread {
                    progress.dismiss()
                    MudifDialog.info(
                        this,
                        getString(R.string.shared_files_failed),
                        message,
                        getString(R.string.ok)
                    )
                }
            }
        }.start()
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, SharedFiles.mimeTypeFor(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        grantUriToHandlers(intent, uri)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.shared_files_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.updates", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = SharedFiles.mimeTypeFor(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        grantUriToHandlers(intent, uri)
        startActivity(Intent.createChooser(intent, getString(R.string.shared_files_share)))
    }

    // F3 fix (23 Nov 2026): the FLAG_GRANT_READ_URI_PERMISSION on
    // the intent alone does not reach the target on this rig. The
    // system resolver/chooser opens the chosen app without the
    // grant, and the app is denied ("provider ... is not
    // exported"). Granting the URI explicitly to every package
    // that can handle the intent makes the open/share work.
    private fun grantUriToHandlers(intent: Intent, uri: Uri) {
        val handlers = packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        )
        for (info in handlers) {
            grantUriPermission(
                info.activityInfo.packageName,
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun ensureGuestShortcut() {
        Thread {
            if (!GuestSession.alive) return@Thread
            GuestSession.sendCommand(SharedFiles.shortcutCommand())
        }.start()
    }

    private class FilesAdapter(
        private val onOpen: (File) -> Unit,
        private val onShare: (File) -> Unit
    ) : RecyclerView.Adapter<FilesAdapter.Holder>() {

        private var items: List<File> = emptyList()

        fun submit(list: List<File>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemSharedFileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class Holder(private val binding: ItemSharedFileBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(file: File) {
                binding.fileName.text = file.name
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
                    .format(Date(file.lastModified()))
                binding.fileMeta.text = "${SharedFiles.formatSize(file.length())} · $date"
                binding.root.setOnClickListener { onOpen(file) }
                binding.fileShare.setOnClickListener { onShare(file) }
            }
        }
    }
}
