package com.light.lightcamera

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/ruditimmermans/BenOSCamera/releases/latest"
        private const val RELEASES_PAGE_URL = "https://github.com/ruditimmermans/BenOSCamera/releases"
    }

    suspend fun checkForUpdates(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(LATEST_RELEASE_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val latestVersion = json.getString("tag_name").replace("v", "")
                val currentVersion = getCurrentVersion()

                if (isNewerVersion(latestVersion, currentVersion)) {
                    val assets = json.getJSONArray("assets")
                    var downloadUrl = RELEASES_PAGE_URL
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                    UpdateResult.NewVersionAvailable(latestVersion, downloadUrl)
                } else {
                    UpdateResult.UpToDate
                }
            } else {
                UpdateResult.Error("Server returned code ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error checking for updates", e)
            UpdateResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0"
        } catch (e: Exception) {
            "0.0"
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val size = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until size) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun openDownloadUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun downloadAndInstallUpdate(url: String, version: String) {
        val destination = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BenOSCamera-v$version.apk")
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(context.getString(R.string.app_name))
            .setDescription("Downloading update v$version")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "BenOSCamera-v$version.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    installApk(destination)
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        Log.e("UpdateManager", "Error unregistering receiver", e)
                    }
                }
            }
        }
        
        ContextCompat.registerReceiver(
            context,
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error starting installation", e)
        }
    }

    sealed class UpdateResult {
        data class NewVersionAvailable(val version: String, val downloadUrl: String) : UpdateResult()
        object UpToDate : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }
}
