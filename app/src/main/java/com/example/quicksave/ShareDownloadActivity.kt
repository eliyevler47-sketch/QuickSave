package com.example.quicksave

import android.Manifest
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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingUrl?.let { url ->
                pendingFileName?.let { name -> executeDownload(url, name) }
            }
        } else {
            Toast.makeText(this, "Yazma icazəsi verilmədi.", Toast.LENGTH_LONG).show()
        }
        pendingUrl = null
        pendingFileName = null
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

    // --- YouTube üçün Cobalt API ---
    private fun fetchYouTubeInfo(url: String) {
        val jsonBody = JSONObject().apply {
            put("url", url)
            put("vQuality", "max") // 1080p, 1440p, 2160p (4K) ən yüksək keyfiyyət
            put("vCodec", "h264")
            put("aFormat", "mp3")
        }.toString()

        val request = Request.Builder()
            .url("https://api.cobalt.tools/api/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showError("Şəbəkə xətası!") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        runOnUiThread { showError("YouTube video çözümlənə bilmədi.") }
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        val status = json.optString("status")
                        if (status == "stream" || status == "redirect") {
                            val videoUrl = json.getString("url")
                            val audioUrl = json.optString("audio", videoUrl)
                            runOnUiThread { setupYouTubeButtons(videoUrl, audioUrl) }
                        } else {
                            val errorText = json.optString("text", "YouTube video çözümlənə bilmədi.")
                            runOnUiThread { showError(errorText) }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { showError("YouTube video çözümlənə bilmədi.") }
                    }
                }
            }
        })
    }

    // --- TikTok üçün TikWM API --- (BURADA DÜZƏLİŞ EDİLDİ)
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

                        // Foto göndərişi yoxlaması
                        if (data.has("images") && !data.isNull("images")) {
                            val imagesArray = data.getJSONArray("images")
                            val imageUrls = ArrayList<String>()
                            for (i in 0 until imagesArray.length()) {
                                imageUrls.add(imagesArray.getString(i))
                            }
                            val title = data.optString("title", "QuickSave")
                                .replace(Regex("[^a-zA-Z0-9-_ ]"), "").take(30).ifBlank { "Photo" }
                            runOnUiThread { setupPhotoSelection(imageUrls, title) }
                            return
                        }

                        val playUrl = data.optString("play")
                        val hdUrl = data.optString("hdplay").ifBlank { playUrl }
                        val musicUrl = data.optString("music")
                        val title = data.optString("title", "QuickSave")
                            .replace(Regex("[^a-zA-Z0-9-_ ]"), "").take(40).ifBlank { "Video" }

                        runOnUiThread { setupTikTokButtons(playUrl, hdUrl, musicUrl, title) }
                    } catch (e: Exception) {
                        runOnUiThread { showError("TikTok video çözümlənə bilmədi.") }
                    }
                }
            }
        })
    }

    // --- UI Qurulumu ---
    private fun setupYouTubeButtons(videoUrl: String, audioUrl: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Format seçin:"
        binding.optionsContainer.visibility = View.VISIBLE

        binding.btnDownloadNoWm.text = "🎥 Video (Ən Yüksək Keyfiyyət - 4K/HD)"
        binding.btnDownloadNoWm.setOnClickListener { startDownload(videoUrl, "QuickSave_YouTube.mp4") }

        binding.btnDownloadHD.visibility = View.GONE

        binding.btnDownloadAudio.text = "🎵 Audio (MP3)"
        binding.btnDownloadAudio.setOnClickListener { startDownload(audioUrl, "QuickSave_YouTube.mp3") }
    }

    private fun setupTikTokButtons(noWmUrl: String, hdUrl: String, audioUrl: String, title: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Format seçin:"
        binding.optionsContainer.visibility = View.VISIBLE

        binding.btnDownloadNoWm.text = "🎥 MP4 (Watermarksız)"
        binding.btnDownloadNoWm.setOnClickListener { startDownload(noWmUrl, "${title}_no_wm.mp4") }

        binding.btnDownloadHD.text = "🎥 MP4 (HD Keyfiyyət)"
        binding.btnDownloadHD.visibility = View.VISIBLE
        binding.btnDownloadHD.setOnClickListener { startDownload(hdUrl, "${title}_hd.mp4") }

        binding.btnDownloadAudio.text = "🎵 MP3 (Audio)"
        binding.btnDownloadAudio.setOnClickListener { startDownload(audioUrl, "$title.mp3") }
    }

    private fun setupPhotoSelection(imageUrls: List<String>, title: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Fotoqalereya aşkarlandı! (${imageUrls.size} şəkil)"
        binding.optionsContainer.visibility = View.VISIBLE

        binding.btnDownloadNoWm.text = "📸 Bütün Şəkilləri Endir"
        binding.btnDownloadNoWm.setOnClickListener {
            imageUrls.forEachIndexed { index, url ->
                executeDownload(url, "${title}_${index + 1}.jpg")
            }
            Toast.makeText(this, "${imageUrls.size} şəkil endirilir...", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnDownloadHD.visibility = View.GONE
        binding.btnDownloadAudio.visibility = View.GONE
    }

    // --- Endirmə Məntiqi ---
    private fun startDownload(url: String, fileName: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Link tapılmadı.", Toast.LENGTH_SHORT).show()
            return
        }
        if (needsStoragePermission()) {
            pendingUrl = url
            pendingFileName = fileName
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            executeDownload(url, fileName)
        }
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    private fun executeDownload(url: String, fileName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Endirilir...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Endirmə başladı.", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Endirmə başladıla bilmədi.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = message
        binding.optionsContainer.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
