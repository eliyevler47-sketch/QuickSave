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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show()
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
            showError(getString(R.string.error_no_link))
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
        binding.tvStatus.text = getString(R.string.status_analyzing)
        binding.optionsContainer.visibility = View.GONE

        val apiUrl = "https://tikwm.com/api/".toHttpUrl().newBuilder()
            .addQueryParameter("url", sourceUrl)
            .build()

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "QuickSave/1.0 (Android)")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showError(getString(R.string.error_network)) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        runOnUiThread { showError(getString(R.string.error_parse)) }
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        if (json.optInt("code", -1) != 0) {
                            runOnUiThread { showError(getString(R.string.error_parse)) }
                            return
                        }
                        val data = json.getJSONObject("data")
                        val playUrl = data.optString("play")
                        val hdUrl = data.optString("hdplay").ifBlank { playUrl }
                        val musicUrl = data.optString("music")

                        val title = data.optString("title")
                            .replace(Regex("[^a-zA-Z0-9-_ ]"), "")
                            .take(40)
                            .trim()
                            .ifBlank { "QuickSave" }

                        runOnUiThread { setupButtons(playUrl, hdUrl, musicUrl, title) }
                    } catch (e: Exception) {
                        runOnUiThread { showError(getString(R.string.error_parse)) }
                    }
                }
            }
        })
    }

    private fun setupButtons(noWmUrl: String, hdUrl: String, audioUrl: String, title: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.status_choose_format)
        binding.optionsContainer.visibility = View.VISIBLE

        binding.btnDownloadNoWm.isEnabled = noWmUrl.isNotBlank()
        binding.btnDownloadHD.isEnabled = hdUrl.isNotBlank()
        binding.btnDownloadAudio.isEnabled = audioUrl.isNotBlank()

        binding.btnDownloadNoWm.setOnClickListener {
            startDownload(noWmUrl, "${title}_no_wm.mp4")
        }
        binding.btnDownloadHD.setOnClickListener {
            startDownload(hdUrl, "${title}_hd.mp4")
        }
        binding.btnDownloadAudio.setOnClickListener {
            startDownload(audioUrl, "$title.mp3")
        }
    }

    private fun startDownload(url: String, fileName: String) {
        if (url.isBlank()) {
            Toast.makeText(this, R.string.error_parse, Toast.LENGTH_SHORT).show()
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
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun executeDownload(url: String, fileName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription(getString(R.string.download_in_progress))
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_download, Toast.LENGTH_LONG).show()
        }
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = message
        binding.optionsContainer.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
