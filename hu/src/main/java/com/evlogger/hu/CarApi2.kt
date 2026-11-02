package com.evlogger.hu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dalvik.system.DexClassLoader
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Доступ к РЕАЛЬНОМУ автомобильному сервису производителя (пакет com.mega.car, служба MegaCarService,
 * интерфейс mega.car.ICar -> mega.car.hardware.property.ICarProperty), а не к стандартной заглушке
 * android.car.Car (которая на этой магнитоле отвечает тестовыми данными "Toy Vehicle").
 *
 * Классы интерфейса (ICar$Stub, ICarProperty$Stub, CarPropertyValue) берутся не из нашего APK,
 * а загружаются прямо из установленного системного пакета com.mega.car через DexClassLoader:
 * так используется ИХ настоящий код маршалинга Binder, и нам не нужно знать номера транзакций вручную.
 * Только чтение (getProperty); set/inject не вызываются.
 */
object CarApi2 {
    @Volatile var lastError = ""
    @Volatile var statusLine = ""
    private var loader: ClassLoader? = null
    private var icar: Any? = null
    private var iCarClass: Class<*>? = null
    private var propObj: Any? = null
    private var iCarPropertyClass: Class<*>? = null
    private var binderConn: ServiceConnection? = null
    private var appCtx: Context? = null

    private fun root(e: Throwable): Throwable { var t: Throwable = e; while (t is InvocationTargetException && t.cause != null) t = t.cause!!; return t }

    private fun sourceDir(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
    } catch (e: Exception) { lastError = "пакет $pkg не виден: ${e.message}"; null }

    /** Подключение. Вызывать не из главного потока. Возвращает true при успехе. */
    @Synchronized
    fun connect(ctx: Context): Boolean {
        if (propObj != null) return true
        appCtx = ctx.applicationContext
        try {
            statusLine = "Ищу пакет com.mega.car…"
            val apk = sourceDir(ctx, "com.mega.car") ?: return false
            statusLine = "Загружаю классы из $apk…"
            val optDir = ctx.getDir("dex_opt", Context.MODE_PRIVATE)
            val cl = DexClassLoader(apk, optDir.absolutePath, null, ctx.classLoader)
            loader = cl
            iCarClass = cl.loadClass("mega.car.ICar")
            val iCarStub = cl.loadClass("mega.car.ICar\$Stub")
            iCarPropertyClass = cl.loadClass("mega.car.hardware.property.ICarProperty")
            val iCarPropertyStub = cl.loadClass("mega.car.hardware.property.ICarProperty\$Stub")

            statusLine = "Подключаюсь к службе MegaCarService (bindService)…"
            val binder = bindAndGetBinder(ctx) ?: return false

            statusLine = "Оборачиваю Binder интерфейсом ICar…"
            icar = iCarStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: return fail("ICar.Stub.asInterface вернул null")

            statusLine = "Запрашиваю подсервис \"property\"…"
            val propBinder = iCarClass!!.getMethod("getCarService", String::class.java).invoke(icar, "property") as? IBinder
                ?: return fail("getCarService(\"property\") вернул null")
            propObj = iCarPropertyStub.getMethod("asInterface", IBinder::class.java).invoke(null, propBinder)
                ?: return fail("ICarProperty.Stub.asInterface вернул null")
            statusLine = "Подключено."
            return true
        } catch (e: Throwable) {
            val r = root(e); return fail("${r.javaClass.simpleName}: ${r.message}")
        }
    }

    private fun fail(msg: String): Boolean { lastError = msg; statusLine = "ОШИБКА: $msg"; return false }

    private fun bindAndGetBinder(ctx: Context): IBinder? {
        val latch = CountDownLatch(1)
        var result: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { result = service; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        binderConn = conn
        val intent = Intent().setClassName("com.mega.car", "com.mega.car.MegaCarService")
        val ok = try { ctx.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (e: Exception) { lastError = "bindService: ${e.message}"; false }
        if (!ok) { lastError = "bindService отказал (нет прав или служба не экспортирована): $lastError"; return null }
        latch.await(8, TimeUnit.SECONDS)
        if (result == null) lastError = "служба не ответила за 8 с"
        return result
    }

    /** id — значение из CarIds (например CarIds.ENGINE_STATUS), area обычно 0. */
    fun read(id: Int, area: Int = 0): Triple<String, String, String> {
        // (значение, статус/время, ошибка-или-пусто)
        val p = propObj ?: return Triple("", "", "нет подключения")
        return try {
            val pv = iCarPropertyClass!!.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(p, id, area)
                ?: return Triple("", "", "null (свойство не поддерживается на этой машине)")
            val k = pv.javaClass
            val value = k.getMethod("getValue").invoke(pv)
            val status = k.getMethod("getStatus").invoke(pv)
            val ts = k.getMethod("getTimestamp").invoke(pv)
            Triple(value?.toString() ?: "null", "статус=$status время=$ts", "")
        } catch (e: Throwable) {
            val r = root(e)
            Triple("", "", "${r.javaClass.simpleName}: ${(r.message ?: "").take(150)}")
        }
    }

    fun disconnect(ctx: Context) {
        try { binderConn?.let { ctx.applicationContext.unbindService(it) } } catch (_: Exception) {}
        binderConn = null; icar = null; propObj = null
    }
}
