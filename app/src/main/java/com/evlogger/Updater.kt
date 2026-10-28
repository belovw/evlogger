package com.evlogger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Обновление из GitHub Releases (тег latest). Подпись у всех сборок одна, поэтому ставится поверх. */
object Updater {
    private const val API = "https://api.github.com/repos/belovw/evlogger/releases/tags/latest"
    private const val APK = "https://github.com/belovw/evlogger/releases/download/latest/EvLogger.apk"

    /** Номер последней сборки из названия релиза «EV Logger v0.N ...», либо null. */
    fun latestBuild(): Int? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            val name = JSONObject(c.inputStream.bufferedReader().readText()).optString("name")
            Regex("""v0\.(\d+)""").find(name)?.groupValues?.get(1)?.toInt()
        } finally { c.disconnect() }
    }

    fun download(ctx: Context, progress: (String) -> Unit): File {
        val out = File(ctx.cacheDir, "EvLogger.apk")
        val c = URL(APK).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        try {
            val total = c.contentLength.toLong()
            c.inputStream.use { i -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (i.read(buf).also { n = it } > 0) {
                    o.write(buf, 0, n); done += n
                    progress(if (total > 0) "скачано ${done * 100 / total} %" else "скачано ${done / 1024} КБ")
                }
            } }
        } finally { c.disconnect() }
        return out
    }

    /** true = установщик запущен; false = нужно разрешение «установка из неизвестных источников». */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "com.evlogger.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
