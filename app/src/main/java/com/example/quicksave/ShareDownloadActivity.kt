package com.example.quicksave

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.quicksave.databinding.DialogDownloaderBinding
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ShareDownloadActivity : AppCompatActivity() {

    private lateinit var binding: DialogDownloaderBinding
    private lateinit var client: OkHttpClient
    private var pendingUrl: String? = null
    private var pendingFileName: String? = null

    // BURAYA ÖZ AÇARLARINI YAZ (Hər açarı dırnaq içində, aralara vergül qoy)
    private val VIDFETCH_API_KEYS = listOf(
        "BURAYA_1_CI_ACARI_YAZ",
        "BURAYA_2_CI_ACARI_YAZ",
        "BURAYA_3_CU_ACARI_YAZ"
    )
    private var currentKeyIndex = 0

    private fun getNextApiKey(): String {
        if (VIDFETCH_API_KEYS.isEmpty()) return ""
        val key = VIDFETCH_API_KEYS[currentKeyIndex]
        currentKeyIndex = (currentKeyIndex + 1) % VIDFETCH_API_KEYS.size
        return key
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingUrl?.let { url -> pendingFileName?.let { name -> executeDownload(url, name) } }
        } else {
            Toast.makeText(this, "Yazma icazəsi verilmədi.", Toast.LENGTH_LONG).show()
        }
        pendingUrl = null; pendingFileName = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogDownloaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(true)

        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val sharedText: String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val url = extractUrl(sharedText)
        if (url.isNullOrBlank()) {
            showError("Bağlantı tapılmadı!")
            return
        }
        fetchMediaInfo(url)
    }

    private fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.value?.trimEnd('.', ',', ')', ']', '}')
    }

    private fun fetchMediaInfo(sourceUrl: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Link analiz edilir…"
        binding.optionsContainer.visibility = View.GONE

        if (sourceUrl.contains("youtube.com") || sourceUrl.contains("youtu.be")) {
            fetchYouTubeInfo(sourceUrl)
        } else if (sourceUrl.contains("tiktok.com")) {
            fetchTikTokInfo(sourceUrl)
        } else {
            showError("Bu platforma dəstəklənmir.")
        }
    }

    // --- YouTube: Açar Rotasiyası ilə ---
    private fun fetchYouTubeInfo(url: String, retryCount: Int = 0) {
        if (retryCount >= VIDFETCH_API_KEYS.size) {
            showError("YouTube xətası: Bütün API açarları yoxlanıldı, heç biri işləmədi.")
            return
        }

        val activeKey = getNextApiKey()
        val jsonBody = JSONObject().apply { put("url", url) }.toString()

        val request = Request.Builder()
            .url("https://api.vidfetch.dev/v1/downloads")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $activeKey")
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { fetchYouTubeInfo(url, retryCount + 1) } // Növbəti açarı yoxla
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.code == 401 || resp.code == 403 || resp.code == 402) {
                        // Açar xətalıdır və ya limit bitib -> Növbəti açarı yoxla
                        runOnUiThread { fetchYouTubeInfo(url, retryCount + 1) }
                        return
                    }

                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        runOnUiThread { fetchYouTubeInfo(url, retryCount + 1) }
                        return
                    }

                    try {
                        val json = JSONObject(body)
                        val formatsArray = json.optJSONArray("formats") ?: json.optJSONArray("links")

                        if (formatsArray != null && formatsArray.length() > 0) {
                            val qualities = ArrayList<Pair<String, String>>()
                            for (i in 0 until formatsArray.length()) {
                                val fmt = formatsArray.getJSONObject(i)
                                val quality = fmt.optString("quality", fmt.optString("format_note", "Video"))
                                val link = fmt.optString("url")
                                if (link.isNotEmpty()) qualities.add(Pair(quality, link))
                            }
                            if (qualities.isNotEmpty()) {
                                qualities.sortByDescending { extractResolution(it.first) }
                                runOnUiThread { showQualitySelection(qualities) }
                                return
                            }
                        }

                        val videoUrl = json.optString("url")
                        if (videoUrl.isNotEmpty()) {
                            runOnUiThread { setupSingleYouTubeButton(videoUrl) }
                        } else {
                            runOnUiThread { fetchYouTubeInfo(url, retryCount + 1) }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { fetchYouTubeInfo(url, retryCount + 1) }
                    }
                }
            }
        })
    }

    // --- TikTok: 2K/HD Keyfiyyət Seçimi ---
    private fun fetchTikTokInfo(url: String) {
        val apiUrl = "https://www.tikwm.com/api/".toHttpUrl().newBuilder()
            .addQueryParameter("url", url)
            .addQueryParameter("hd", "1")
            .build()

        val request = Request.Builder().url(apiUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showError("Şəbəkə xətası!") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        runOnUiThread { showError("TikTok video çözümlənə bilmədi.") }
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        if (json.optInt("code", -1) != 0) {
                            runOnUiThread { showError("TikTok video çözümlənə bilmədi.") }
                            return
                        }
                        val data = json.getJSONObject("data")

                        // Foto yoxlaması
                        if (data.has("images") && !data.isNull("images")) {
                            val imagesArray = data.getJSONArray("images")
                            val imageUrls = ArrayList<String>()
                            for (i in 0 until imagesArray.length()) imageUrls.add(imagesArray.getString(i))
                            val title = data.optString("title", "QuickSave").replace(Regex("[^a-zA-Z0-9-_ ]"), "").take(30).ifBlank { "Photo" }
                            runOnUiThread { setupPhotoSelection(imageUrls, title) }
                            return
                        }

                        val playUrl = data.optString("play")
                        val hdUrl = data.optString("hdplay").ifBlank { playUrl }
                        val musicUrl = data.optString("music")
                        val title = data.optString("title", "QuickSave").replace(Regex("[^a-zA-Z0-9-_ ]"), "").take(40).ifBlank { "Video" }
                        
                        // Keyfiyyət məlumatı (TikTok API adətən width/height qaytarır)
                        val width = data.optInt("width", 1080)
                        val height = data.optInt("height", 1920)
                        val qualityText = "${height}p"

                        runOnUiThread { setupTikTokButtons(hdUrl, playUrl, musicUrl, title, qualityText) }
                    } catch (e: Exception) {
                        runOnUiThread { showError("TikTok video çözümlənə bilmədi.") }
                    }
                }
            }
        })
    }

    // --- UI Qurulumu ---
    private fun showQualitySelection(qualities: List<Pair<String, String>>) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "YouTube Keyfiyyət Seçimi"
        binding.optionsContainer.visibility = View.VISIBLE

        binding.btnDownloadNoWm.text = "🎥 Video Keyfiyyətini Seç"
        binding.btnDownloadNoWm.setOnClickListener {
            val qualityNames = qualities.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Keyfiyyət seçin (4K/2K/1080p)")
                .setItems(qualityNames) { _, which ->
                    startDownload(qualities[which].second, "QuickSave_YouTube_${qualities[which].first}.mp4")
                }.show()
        }
        binding.btnDownloadHD.visibility = View.GONE
        binding.btnDownloadAudio.visibility = View.GONE
    }

    private fun setupSingleYouTubeButton(videoUrl: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Format seçin:"
        binding.optionsContainer.visibility = View.VISIBLE
        binding.btnDownloadNoWm.text = "🎥 Video (Ən Yüksək Keyfiyyət)"
        binding.btnDownloadNoWm.setOnClickListener { startDownload(videoUrl, "QuickSave_YouTube.mp4") }
        binding.btnDownloadHD.visibility = View.GONE
        binding.btnDownloadAudio.visibility = View.GONE
    }

    // TikTok: Watermark yazısı silindi, 2K/HD göstərildi
    private fun setupTikTokButtons(hdUrl: String, standardUrl: String, audioUrl: String, title: String, currentQuality: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Video Keyfiyyəti: $currentQuality"
        binding.optionsContainer.visibility = View.VISIBLE

        binding.btnDownloadNoWm.text = "🎥 MP4 ($currentQuality)"
        binding.btnDownloadNoWm.setOnClickListener { startDownload(hdUrl, "${title}_$currentQuality.mp4") }

        // Keyfiyyəti dəyişmək üçün ikinci düymə
        binding.btnDownloadHD.visibility = View.VISIBLE
        binding.btnDownloadHD.text = "⚙️ Keyfiyyəti Dəyişdir"
        binding.btnDownloadHD.setOnClickListener {
            val qualities = listOf("Standart (SD)" to standardUrl, "Yüksək (HD)" to hdUrl)
            val qualityNames = qualities.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("TikTok Keyfiyyətini Seçin")
                .setItems(qualityNames) { _, which ->
                    startDownload(qualities[which].second, "${title}_${qualities[which].first}.mp4")
                }.show()
        }

        binding.btnDownloadAudio.text = "🎵 MP3 (Audio)"
        binding.btnDownloadAudio.setOnClickListener { startDownload(audioUrl, "$title.mp3") }
    }

    private fun setupPhotoSelection(imageUrls: List<String>, title: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Fotoqalereya aşkarlandı! (${imageUrls.size} şəkil)"
        binding.optionsContainer.visibility = View.VISIBLE
        binding.btnDownloadNoWm.text = "📸 Bütün Şəkilləri Endir"
        binding.btnDownloadNoWm.setOnClickListener {
            imageUrls.forEachIndexed { index, url -> executeDownload(url, "${title}_${index + 1}.jpg") }
            Toast.makeText(this, "${imageUrls.size} şəkil endirilir...", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.btnDownloadHD.visibility = View.GONE
        binding.btnDownloadAudio.visibility = View.GONE
    }

    // --- Endirmə Məntiqi ---
    private fun startDownload(url: String, fileName: String) {
        if (url.isBlank()) { Toast.makeText(this, "Link tapılmadı.", Toast.LENGTH_SHORT).show(); return }
        if (needsStoragePermission()) {
            pendingUrl = url; pendingFileName = fileName
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else executeDownload(url, fileName)
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    private fun executeDownload(url: String, fileName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName); setDescription("Endirilir...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true); setAllowedOverRoaming(true)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Endirmə başladı.", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Endirmə başladıla bilmədi.", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractResolution(quality: String): Int {
        val match = Regex("\\d+").find(quality)
        return match?.value?.toIntOrNull() ?: 0
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = message
        binding.optionsContainer.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
