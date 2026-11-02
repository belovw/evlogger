package com.evlogger.hu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import dalvik.system.DexClassLoader
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Пробуем ВТОРОЙ, обходной путь к данным автомобиля: служба контроллера com.mega.controller
 * (BigsurCarController) работает с sharedUserId=android.uid.system и поэтому имеет все
 * mega.car.permission.* автоматически. Её AIDL-служба MegaControllerService экспортирована
 * БЕЗ единого permission в манифесте — связаться с ней может кто угодно.
 *
 * Интерфейс mega.controller.manager.ICarController у неё всего из двух методов:
 *   HuCertBean getHuCertBean()
 *   IBinder getManager(String key)
 * Какие строки принимает getManager — неизвестно из статики, перебираем по списку кандидатов.
 * Для каждого ненулевого результата читаем IBinder.getInterfaceDescriptor() — это служебный
 * метод есть у любого Binder'а и сразу покажет настоящее имя AIDL-интерфейса без угадывания.
 *
 * Параллельно пробуем ContentProvider com.mega.controller.utils.ControllerProvider (тоже
 * exported без permission) — простым query() и call() с разными вариантами.
 */
object CarApi3 {
    @Volatile var lastError = ""
    @Volatile var statusLine = ""

    private fun root(e: Throwable): Throwable { var t: Throwable = e; while (t is InvocationTargetException && t.cause != null) t = t.cause!!; return t }

    private fun sourceDir(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
    } catch (e: Exception) { lastError = "пакет $pkg не виден: ${e.message}"; null }

    private fun bindAndGetBinder(ctx: Context, pkg: String, cls: String): IBinder? {
        val latch = CountDownLatch(1)
        var result: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { result = service; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent().setClassName(pkg, cls)
        val ok = try { ctx.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (e: Exception) { lastError = "bindService: ${e.message}"; false }
        if (!ok) { lastError = "bindService отказал: $lastError"; return null }
        latch.await(8, TimeUnit.SECONDS)
        if (result == null) lastError = "служба не ответила за 8 с"
        return result
    }

    // Кандидаты ключей для getManager(String) — имена классов-менеджеров/контроллеров и
    // стандартные short-имена по аналогии с android.car.Car.*_SERVICE константами.
    private val MANAGER_KEY_CANDIDATES = listOf(
        "car", "Car", "property", "Property", "carProperty", "CarProperty",
        "base", "daynight", "DayNight", "racemode", "RaceMode", "remotetask", "RemoteTask",
        "scenemode", "SceneMode", "xcall", "XCall", "messagepush", "MessagePush",
        "audio", "power", "CarAudioManager", "CarPowerManager", "assistant", "apa",
        "CarControllerImpl", "hucert", "HuCert", "info", "Info", "vehicle", "Vehicle"
    )

    fun probeGetManager(ctx: Context): String {
        val sb = StringBuilder()
        statusLine = "Ищу пакет com.mega.controller…"
        val apk = sourceDir(ctx, "com.mega.controller") ?: return "НЕ НАЙДЕН ПАКЕТ: $lastError"
        sb.append("APK: $apk\n")
        val optDir = ctx.getDir("dex_opt3", Context.MODE_PRIVATE)
        val cl = try { DexClassLoader(apk, optDir.absolutePath, null, ctx.classLoader) } catch (e: Throwable) {
            return sb.append("DexClassLoader ошибка: ${root(e)}\n").toString()
        }
        val iStub = try { cl.loadClass("mega.controller.manager.ICarController\$Stub") } catch (e: Throwable) {
            return sb.append("класс ICar\$Stub не найден: ${root(e)}\n").toString()
        }
        statusLine = "Подключаюсь к MegaControllerService (bindService)…"
        val binder = bindAndGetBinder(ctx, "com.mega.controller", "com.mega.controller.service.MegaControllerService")
            ?: return sb.append("bindService не удался: $lastError\n").toString()
        sb.append("bindService: OK, binder=$binder, descriptor=${try { binder.interfaceDescriptor } catch (e: Throwable) { "err:${root(e)}" }}\n")

        val icc = try { iStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) } catch (e: Throwable) {
            return sb.append("asInterface ошибка: ${root(e)}\n").toString()
        } ?: return sb.append("asInterface вернул null\n").toString()

        try {
            val cert = iStub.declaringClass // placeholder, real call below via interface class
        } catch (_: Exception) {}
        val iccClass = icc.javaClass.interfaces.firstOrNull { it.name == "mega.controller.manager.ICarController" } ?: icc.javaClass
        try {
            val hu = iccClass.getMethod("getHuCertBean").invoke(icc)
            sb.append("getHuCertBean() = $hu\n")
        } catch (e: Throwable) { sb.append("getHuCertBean() ошибка: ${root(e)}\n") }

        val getManager = iccClass.getMethod("getManager", String::class.java)
        for (key in MANAGER_KEY_CANDIDATES) {
            try {
                val b = getManager.invoke(icc, key) as? IBinder
                if (b == null) { sb.append("getManager(\"$key\") = null\n"); continue }
                val desc = try { b.interfaceDescriptor } catch (e: Throwable) { "err:${root(e)}" }
                sb.append("getManager(\"$key\") = BINDER, descriptor=$desc\n")
            } catch (e: Throwable) {
                sb.append("getManager(\"$key\") ОШИБКА: ${root(e).javaClass.simpleName}: ${(root(e).message ?: "").take(120)}\n")
            }
        }
        return sb.toString()
    }

    // --- ContentProvider com.mega.controller.utils.ControllerProvider ---
    private val PROVIDER_PATHS = listOf(
        "", "car", "property", "carProperty", "vehicle", "status", "info", "config", "get"
    )
    private val CALL_METHODS = listOf(
        "getCarProperty", "getProperty", "get", "getValue", "query", "getInfo", "getConfig"
    )

    fun probeProvider(ctx: Context): String {
        val sb = StringBuilder()
        val authority = "com.mega.controller.utils.ControllerProvider"
        for (p in PROVIDER_PATHS) {
            val uri = if (p.isEmpty()) Uri.parse("content://$authority") else Uri.parse("content://$authority/$p")
            try {
                ctx.contentResolver.query(uri, null, null, null, null).use { c ->
                    if (c == null) sb.append("query($uri) = null cursor\n")
                    else {
                        val cols = c.columnNames.joinToString(",")
                        sb.append("query($uri) = ${c.count} строк, колонки: $cols\n")
                        var n = 0
                        while (c.moveToNext() && n < 5) {
                            val row = (0 until c.columnCount).joinToString("|") { i -> try { c.getString(i) } catch (_: Exception) { "<bin>" } }
                            sb.append("  row: $row\n"); n++
                        }
                    }
                }
            } catch (e: Throwable) {
                sb.append("query($uri) ОШИБКА: ${root(e).javaClass.simpleName}: ${(root(e).message ?: "").take(120)}\n")
            }
        }
        for (m in CALL_METHODS) {
            try {
                val r = ctx.contentResolver.call(Uri.parse("content://$authority"), m, null, null)
                sb.append("call(\"$m\") = ${r?.keySet()?.joinToString(",") { k -> "$k=${r.get(k)}" } ?: "null"}\n")
            } catch (e: Throwable) {
                sb.append("call(\"$m\") ОШИБКА: ${root(e).javaClass.simpleName}: ${(root(e).message ?: "").take(120)}\n")
            }
        }
        return sb.toString()
    }
}
