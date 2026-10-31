package com.evlogger

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Загрузка файлов в приватный репозиторий GitHub через Contents API с проверкой git-хэша. */
object Uploader {
    private const val MAX_BYTES = 40L * 1024 * 1024

    private fun gitSha(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${b.size}\u0000".toByteArray()); md.update(b)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    class Result(val ok: List<File>, val errors: List<String>, val folder: String)

    fun upload(files: List<File>, repo: String, token: String, progress: (String) -> Unit): Result {
        val folder = "inbox/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ok = ArrayList<File>(); val err = ArrayList<String>()
        files.forEachIndexed { i, f ->
            progress("Загрузка на GitHub: ${i + 1}/${files.size} ${f.name}")
            try {
                if (f.length() > MAX_BYTES) { err.add("${f.name}: больше 40 МБ, не загружен"); return@forEachIndexed }
                val data = f.readBytes()
                val body = JSONObject().put("message", "upload ${f.name}")
                    .put("content", Base64.encodeToString(data, Base64.NO_WRAP)).toString()
                val c = URL("https://api.github.com/repos/$repo/contents/$folder/${f.name}").openConnection() as HttpURLConnection
                c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 120000
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (code != 200 && code != 201) {
                    val msg = try { JSONObject(text).optString("message") } catch (_: Exception) { text.take(100) }
                    err.add("${f.name}: HTTP $code $msg")
                } else {
                    val sha = JSONObject(text).getJSONObject("content").getString("sha")
                    if (sha == gitSha(data)) ok.add(f) else err.add("${f.name}: хэш не совпал, файл не удалён")
                }
            } catch (e: Exception) { err.add("${f.name}: ${e.message}") }
        }
        return Result(ok, err, folder)
    }
}
