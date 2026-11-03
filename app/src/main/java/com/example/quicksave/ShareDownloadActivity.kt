package com.example.quicksave

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.quicksave.databinding.DialogDownloaderBinding
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class ShareDownloadActivity : AppCompatActivity() {

    private lateinit var binding: DialogDownloaderBinding
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogDownloaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intentText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val extractedUrl = extractUrl(intentText)

        if (extractedUrl != null) {
            fetchMediaLinks(extractedUrl)
        } else {
            Toast.makeText(this, "Geçerli bağlantı bulunamadı!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun extractUrl(text: String?): String? {
        if (text == null) return null
        val regex = "(https?://[^\\s]+)".toRegex()
        return regex.find(text)?.value
    }

    private fun fetchMediaLinks(url: String) {
        val apiUrl = "https://tikwm.com/api/?url=$url"
        val request = Request.Builder().url(apiUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { binding.tvStatus.text = "Ağ bağlantı hatası!" }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (body != null) {
                    try {
                        val json = JSONObject(body)
                        val data = json.getJSONObject("data")
                        val playUrl = data.getString("play")
                        val hdPlayUrl = data.optString("hdplay", playUrl)
                        val musicUrl = data.getString("music")

                        runOnUiThread { setupButtons(playUrl, hdPlayUrl, musicUrl) }
                    } catch (e: Exception) {
                        runOnUiThread { binding.tvStatus.text = "Video verisi çözümlenemedi." }
                    }
                }
            }
        })
    }

    private fun setupButtons(noWmUrl: String, hdUrl: String, audioUrl: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "İndirme formatını seçin:"
        binding.optionsContainer.visibility = View.VISIBLE

        binding.btnDownloadNoWm.setOnClickListener {
            startSystemDownload(noWmUrl, "QuickSave_Video.mp4", "video/mp4")
        }
        binding.btnDownloadHD.setOnClickListener {
            startSystemDownload(hdUrl, "QuickSave_HD.mp4", "video/mp4")
        }
        binding.btnDownloadAudio.setOnClickListener {
            startSystemDownload(audioUrl, "QuickSave_Audio.mp3", "audio/mpeg")
        }
    }

    private fun startSystemDownload(url: String, fileName: String, mimeType: String) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("QuickSave indiriliyor...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType(mimeType)
        }

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)

        Toast.makeText(this, "İndirme arka planda başlatıldı.", Toast.LENGTH_SHORT).show()
        finish()
    }
}
