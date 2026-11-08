package com.example.pembersih

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

enum class Category(val label: String, val defaultOn: Boolean) {
    TEMP("File sementara dan log (.tmp, .log, .bak, .old)", true),
    THUMB("Thumbnail galeri (.thumbnails)", true),
    APK("Installer APK di folder Download", false),
    EMPTY("Folder kosong (kadang dipakai aplikasi)", false)
}

class JunkItem(val file: File, val size: Long, val category: Category)

object JunkScanner {

    private val tempExt = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp", "cache")

    fun scan(): List<JunkItem> {
        val result = ArrayList<JunkItem>()
        val root = Environment.getExternalStorageDirectory()
        val androidDir = File(root, "Android")
        val walk = root.walkTopDown().onEnter { it != androidDir }
        for (f in walk) {
            try {
                if (f.isDirectory) {
                    if (f != root && f.list()?.isEmpty() == true) {
                        result.add(JunkItem(f, 0L, Category.EMPTY))
                    }
                } else {
                    val ext = f.extension.lowercase()
                    val cat: Category? = when {
                        f.parentFile?.name == ".thumbnails" || f.name.startsWith(".thumbdata") -> Category.THUMB
                        ext in tempExt || f.name.endsWith("~") -> Category.TEMP
                        ext == "apk" && f.absolutePath.contains("/Download", ignoreCase = true) -> Category.APK
                        else -> null
                    }
                    if (cat != null) {
                        result.add(JunkItem(f, f.length(), cat))
                    }
                }
            } catch (e: SecurityException) {
                // lewati yang tidak bisa diakses
            }
        }
        return result
    }

    fun delete(items: List<JunkItem>): Long {
        var freed = 0L
        val files = items.filter { it.category != Category.EMPTY }
        val dirs = items.filter { it.category == Category.EMPTY }
            .sortedByDescending { it.file.absolutePath.length }
        for (item in files) {
            if (item.file.delete()) freed += item.size
        }
        for (item in dirs) {
            item.file.delete()
        }
        return freed
    }
}

class MainActivity : Activity() {

    private val bg = Color.parseColor("#101614")
    private val fg = Color.parseColor("#e6efec")
    private val muted = Color.parseColor("#93a8a3")

    private lateinit var storageText: TextView
    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout
    private val checks = LinkedHashMap<Category, CheckBox>()
    private var junk: List<JunkItem> = emptyList()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun fmt(b: Long): String = Formatter.formatFileSize(this, b)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(40), dp(16), dp(32))

        root.addView(label("Pembersih Sampah", 26f, fg, true))
        storageText = label("", 14f, muted, false)
        root.addView(storageText)
        root.addView(space(12))

        root.addView(button("Bersihkan cache semua aplikasi") { clearAllCaches() })
        root.addView(button("Lihat cache per aplikasi") { showAppCaches() })
        root.addView(button("Pindai berkas sampah") { scanJunk() })

        status = label("", 14f, fg, false)
        status.setPadding(0, dp(12), 0, dp(12))
        root.addView(status)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun space(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun updateStorage() {
        val st = StatFs(Environment.getDataDirectory().path)
        storageText.text = "Kosong " + fmt(st.availableBytes) + " dari " + fmt(st.totalBytes)
    }

    // 1. Minta ruang kosong sebesar-besarnya, sistem menghapus cache aplikasi lain untuk memenuhinya
    private fun clearAllCaches() {
        status.text = "Membersihkan cache, tunggu beberapa detik..."
        thread {
            val msg: String = try {
                val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val before = StatFs(Environment.getDataDirectory().path).availableBytes
                val want = sm.getAllocatableBytes(StorageManager.UUID_DEFAULT)
                val tmp = File(cacheDir, "alloc.tmp")
                FileOutputStream(tmp).use { out ->
                    sm.allocateBytes(out.fd, want)
                }
                tmp.delete()
                val after = StatFs(Environment.getDataDirectory().path).availableBytes
                val freed = after - before
                if (freed > 1024 * 1024) {
                    "Selesai. Sekitar " + fmt(freed) + " cache dibersihkan oleh sistem."
                } else {
                    "Selesai, tapi sistem hampir tidak menghapus cache (kurang dari 1 MB)."
                }
            } catch (e: Throwable) {
                "Gagal: " + e.javaClass.simpleName + " " + (e.message ?: "")
            }
            runOnUiThread {
                status.text = msg
                updateStorage()
            }
        }
    }

    // 2. Daftar cache per aplikasi
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showAppCaches() {
        if (!hasUsageAccess()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            status.text = "Aktifkan akses penggunaan untuk aplikasi ini, lalu kembali dan tekan tombol lagi."
            return
        }
        status.text = "Menghitung cache aplikasi..."
        listBox.removeAllViews()
        thread {
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val pm = packageManager
            val rows = ArrayList<Triple<String, String, Long>>()
            for (app in pm.getInstalledApplications(0)) {
                try {
                    val st = ssm.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle()
                    )
                    if (st.cacheBytes > 0L) {
                        rows.add(Triple(pm.getApplicationLabel(app).toString(), app.packageName, st.cacheBytes))
                    }
                } catch (e: Exception) {
                    // lewati aplikasi yang tidak bisa dibaca
                }
            }
            rows.sortByDescending { it.third }
            runOnUiThread {
                status.text = "Ketuk aplikasi untuk membuka halamannya, lalu pilih Hapus cache."
                for (r in rows.take(40)) {
                    val tv = label(r.first + "\n" + fmt(r.third), 15f, fg, false)
                    tv.setPadding(dp(4), dp(10), dp(4), dp(10))
                    tv.setOnClickListener {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + r.second))
                        )
                    }
                    listBox.addView(tv)
                }
            }
        }
    }

    // 3. Berkas sampah
    private fun scanJunk() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
            status.text = "Beri izin akses semua file, lalu kembali dan tekan Pindai lagi."
            return
        }
        status.text = "Memindai..."
        listBox.removeAllViews()
        thread {
            val found = JunkScanner.scan()
            runOnUiThread {
                junk = found
                showJunk()
            }
        }
    }

    private fun showJunk() {
        listBox.removeAllViews()
        checks.clear()
        if (junk.isEmpty()) {
            status.text = "Tidak ada berkas sampah ditemukan."
            return
        }
        status.text = "Ditemukan " + junk.size + " item (" + fmt(junk.sumOf { it.size }) + ")."
        for (cat in Category.values()) {
            val group = junk.filter { it.category == cat }
            if (group.isEmpty()) continue
            val cb = CheckBox(this)
            cb.text = cat.label + "\n" + group.size + " item, " + fmt(group.sumOf { it.size })
            cb.isChecked = cat.defaultOn
            cb.setTextColor(fg)
            checks[cat] = cb
            listBox.addView(cb)
        }
        listBox.addView(button("Hapus yang dicentang") { confirmDelete() })
    }

    private fun confirmDelete() {
        val selected = junk.filter { checks[it.category]?.isChecked == true }
        if (selected.isEmpty()) {
            status.text = "Tidak ada yang dicentang."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus " + selected.size + " item?")
            .setMessage("Sekitar " + fmt(selected.sumOf { it.size }) + " akan dihapus permanen dan tidak bisa dikembalikan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> doDelete(selected) }
            .show()
    }

    private fun doDelete(selected: List<JunkItem>) {
        status.text = "Menghapus..."
        thread {
            val freed = JunkScanner.delete(selected)
            runOnUiThread {
                listBox.removeAllViews()
                checks.clear()
                junk = emptyList()
                status.text = "Selesai. " + fmt(freed) + " dibebaskan."
                updateStorage()
            }
        }
    }
}
