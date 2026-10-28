package com.evlogger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** «Домашнее задание»: сценарий из assets/homework.txt. Выполняется по шагам, в конце всё упаковывается в zip и отправляется. */
object Homework {
    @Volatile var running = false
    @Volatile var abort = false

    private class Step(val cmd: String, val args: List<String>)

    private fun load(act: Activity): List<Step> =
        act.assets.open("homework.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { l -> val p = l.split(";").map { it.trim() }; Step(p[0].lowercase(), p.drop(1)) }

    private fun range(s: String): Pair<Int, Int>? {
        val p = s.split("-"); val a = p.getOrNull(0)?.toIntOrNull(16); val b = p.getOrNull(1)?.toIntOrNull(16)
        return if (a != null && b != null && a <= b) Pair(a, b) else null
    }

    private fun dids(s: String): List<String> {
        val out = ArrayList<String>()
        for (tok in s.uppercase().split(Regex("[\\s,]+"))) {
            if (tok.contains("-")) {
                val r = range(tok) ?: continue
                for (v in r.first..r.second) out.add("%04X".format(v))
            } else if (tok.length == 4 && tok.toIntOrNull(16) != null) out.add(tok)
        }
        return out
    }

    fun title(act: Activity): String = try { load(act).firstOrNull { it.cmd == "title" }?.args?.firstOrNull() ?: "без описания" } catch (e: Exception) { "не удалось прочитать задание" }

    fun start(act: Activity, toast: (String) -> Unit) {
        if (running) return toast("Задание уже выполняется")
        if (!Elm.connected) return toast("Сначала подключись")
        if (Elm.logging || Elm.monitoring || Elm.scanning) return toast("Останови запись/сканер")
        AlertDialog.Builder(act).setTitle("Домашнее задание").setMessage(title(act) + "\n\nНачать?")
            .setPositiveButton("Начать") { _, _ -> Thread { run(act, toast) }.start() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun run(act: Activity, toast: (String) -> Unit) {
        running = true; abort = false
        val dir = act.getExternalFilesDir(null)!!
        val t0 = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notes = File(dir, "notes_$ts.txt")
        fun note(s: String) { notes.appendText("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $s\n") }
        note("homework started, app ${Elm.appVersion}")
        try {
            act.startForegroundService(Intent(act, KeepAliveService::class.java))
            val steps = load(act)
            for ((i, st) in steps.withIndex()) {
                if (abort || !Elm.connected) break
                Elm.scanProgress = "задание: шаг ${i + 1}/${steps.size}"
                when (st.cmd) {
                    "note" -> {
                        val latch = CountDownLatch(1)
                        act.runOnUiThread {
                            AlertDialog.Builder(act).setTitle("Задание").setMessage(st.args.getOrElse(0) { "" })
                                .setCancelable(false).setPositiveButton("Готово") { _, _ -> latch.countDown() }.show()
                        }
                        latch.await()
                        note("note done: ${st.args.getOrElse(0) { "" }}")
                    }
                    "survey" -> {
                        val only = st.args.getOrElse(0) { "" }.uppercase().split(Regex("\\s+")).filter { it.length == 3 }.toSet()
                        val r = range(st.args.getOrElse(1) { "" }) ?: continue
                        note("survey ${st.args}"); Elm.surveyEcus(dir, r.first, r.second, st.args.getOrNull(2)?.toIntOrNull() ?: 1, only)
                    }
                    "scan" -> {
                        val r = range(st.args.getOrElse(1) { "" }) ?: continue
                        note("scan ${st.args}"); Elm.scanDids(dir, st.args[0].uppercase(), r.first, r.second, st.args.getOrNull(2)?.toIntOrNull() ?: 1)
                    }
                    "watch" -> {
                        val list = dids(st.args.getOrElse(1) { "" })
                        val sec = st.args.getOrNull(2)?.toLongOrNull() ?: 60
                        note("watch ${st.args}")
                        Thread { Thread.sleep(sec * 1000); Elm.scanning = false }.start()
                        Elm.watchDids(dir, st.args[0].uppercase(), list)
                    }
                }
            }
            note("homework finished${if (abort) " (aborted)" else ""}")
            val zip = File(act.cacheDir, "homework_$ts.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                dir.listFiles { f -> f.isFile && f.lastModified() >= t0 - 2000 }?.forEach { f ->
                    z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
            act.runOnUiThread {
                val uri = FileProvider.getUriForFile(act, "com.evlogger.files", zip)
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Отправить результаты задания"))
            }
        } catch (e: Exception) {
            note("error: ${e.message}"); toast("Ошибка задания: ${e.message}")
        } finally {
            running = false; Elm.scanning = false; Elm.scanProgress = ""
        }
    }
}
