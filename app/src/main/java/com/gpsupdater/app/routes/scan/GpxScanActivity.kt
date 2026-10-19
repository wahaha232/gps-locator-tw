package com.gpsupdater.app.routes.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.gpsupdater.app.R
import com.gpsupdater.app.databinding.ActivityGpxScanBinding
import com.gpsupdater.app.databinding.ItemGpxFileBinding
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 搜尋／掃描整支手機裡的 .gpx 檔案：
 * - 有「所有檔案存取權」時掃描所有儲存空間（內部儲存與 SD 卡）；
 * - 沒有就請使用者授權，或改用系統資料夾選擇器只掃描指定資料夾（不需要額外權限）。
 * 檔案只在本機讀取、不會上傳。點選一個檔案就回傳它的 Uri，由路線編輯器匯入。
 */
class GpxScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGpxScanBinding
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val incoming = ConcurrentLinkedQueue<GpxFileEntry>()
    private val allEntries = mutableListOf<GpxFileEntry>()
    private var shown: List<GpxFileEntry> = emptyList()
    private var scanning = false
    private var scannedOnce = false
    private var latestProgress = ScanProgress(0, 0, false)
    private val adapter = ResultsAdapter()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startScan { SafGpxScanner.scan(this, uri, { cancelled.get() }, this::postProgress) { incoming.add(it) } }
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshAccessState()
    }

    private val ticker = object : Runnable {
        override fun run() {
            drainIncoming()
            updateStatus()
            if (scanning) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGpxScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.resultsList.adapter = adapter
        binding.resultsList.emptyView = null
        binding.resultsList.setOnItemClickListener { _, _, position, _ -> choose(shown[position]) }
        binding.backButton.setOnClickListener { finish() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        binding.grantAccessButton.setOnClickListener { requestAccess() }
        binding.rescanButton.setOnClickListener { scanAllStorage() }
        binding.folderButton.setOnClickListener { folderPicker.launch(null) }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessState()
    }

    override fun onDestroy() {
        cancelled.set(true)
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---- 權限 ----
    private fun hasAllFilesAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(appIntent) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        } else {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun refreshAccessState() {
        val granted = hasAllFilesAccess()
        binding.permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        binding.rescanButton.isEnabled = granted && !scanning
        if (granted && !scannedOnce && !scanning) scanAllStorage()
    }

    // ---- 掃描 ----
    private fun scanAllStorage() {
        if (!hasAllFilesAccess()) {
            refreshAccessState()
            return
        }
        val roots = storageRoots(this)
        startScan { GpxFileScanner.scan(roots, { cancelled.get() }, this::postProgress) { incoming.add(it) } }
    }

    private fun startScan(work: () -> ScanProgress) {
        if (scanning) return
        scanning = true
        scannedOnce = true
        cancelled.set(false)
        allEntries.clear()
        incoming.clear()
        latestProgress = ScanProgress(0, 0, false)
        binding.rescanButton.isEnabled = false
        binding.scanProgress.visibility = View.VISIBLE
        applyFilter()
        handler.post(ticker)
        executor.execute {
            val summary = runCatching(work).getOrDefault(progressSnapshot)
            handler.post {
                scanning = false
                latestProgress = summary
                binding.scanProgress.visibility = View.INVISIBLE
                binding.rescanButton.isEnabled = hasAllFilesAccess()
                drainIncoming()
                updateStatus()
            }
        }
    }

    @Volatile
    private var progressSnapshot = ScanProgress(0, 0, false)

    private fun postProgress(progress: ScanProgress) {
        progressSnapshot = progress
    }

    private fun drainIncoming() {
        var added = false
        while (true) {
            val entry = incoming.poll() ?: break
            allEntries += entry
            added = true
        }
        if (added) applyFilter()
    }

    private fun applyFilter() {
        shown = GpxFileFilter.apply(allEntries, binding.searchInput.text?.toString().orEmpty())
        adapter.notifyDataSetChanged()
        binding.emptyText.visibility = if (shown.isEmpty() && !scanning && scannedOnce) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        if (scanning) {
            latestProgress = progressSnapshot
            binding.statusText.text = getString(R.string.scan_status_running, latestProgress.directoriesVisited, allEntries.size)
            return
        }
        if (!scannedOnce) {
            binding.statusText.text = ""
            return
        }
        var text = getString(R.string.scan_status_done, allEntries.size, latestProgress.directoriesVisited)
        if (latestProgress.limitReached) text += " " + getString(R.string.scan_status_limit, GpxFileScanner.MAX_RESULTS)
        binding.statusText.text = text
    }

    private fun choose(entry: GpxFileEntry) {
        setResult(RESULT_OK, Intent().setData(Uri.parse(entry.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemBinding = if (convertView == null) {
                ItemGpxFileBinding.inflate(LayoutInflater.from(parent.context), parent, false).also { it.root.tag = it }
            } else {
                convertView.tag as ItemGpxFileBinding
            }
            val entry = shown[position]
            itemBinding.fileName.text = entry.name
            itemBinding.fileLocation.text = entry.location
            val date = if (entry.lastModifiedMs > 0) {
                DateFormat.getMediumDateFormat(this@GpxScanActivity).format(Date(entry.lastModifiedMs)) + " " +
                    DateFormat.getTimeFormat(this@GpxScanActivity).format(Date(entry.lastModifiedMs))
            } else {
                ""
            }
            itemBinding.fileDetails.text = getString(
                R.string.scan_item_details,
                Formatter.formatShortFileSize(this@GpxScanActivity, entry.sizeBytes),
                date,
            ).trim()
            return itemBinding.root
        }
    }

    companion object {
        private const val TICK_MS = 250L

        fun intent(context: Context): Intent = Intent(context, GpxScanActivity::class.java)

        /** 內部儲存與所有 SD 卡的根目錄。 */
        fun storageRoots(context: Context): List<File> {
            val roots = linkedSetOf<File>()
            roots += Environment.getExternalStorageDirectory()
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                val path = dir.absolutePath
                val marker = path.indexOf("/Android/data/")
                if (marker > 0) roots += File(path.substring(0, marker))
            }
            return roots.filter { it.isDirectory }
        }
    }
}
