package com.evlogger.hu

import android.os.IBinder
import android.os.Process
import java.lang.reflect.InvocationTargetException

/**
 * ОТДЕЛЬНАЯ точка входа — НЕ компонент приложения. Не запускается при обычном старте APK.
 * Предназначена для запуска через `adb shell app_process ...` — то есть от имени пользователя
 * shell (uid=2000), а не от имени нашего приложения (у которого есть собственный uid и которому
 * ОС запрещает mega.car.permission.* — это мы уже проверили через pm grant: "not a changeable
 * permission type", т.к. protectionLevel=signature|privileged).
 *
 * Идея проверки: ICarProperty.getProperty() внутри себя делает Binder.getCallingUid() и сверяет
 * его с разрешениями вызывающего. Если производитель (как многие вендоры автомобильных прошивок)
 * оставил в этой проверке "отладочную" лазейку для uid=shell/root — то именно ЭТОТ процесс,
 * запущенный через app_process, сможет прочитать свойство, хотя наше обычное приложение не может.
 * Если лазейки нет — получим точно такую же SecurityException, но зато будем знать это наверняка,
 * а не гадать.
 *
 * Бинарный сервис берём НЕ через bindService (туда он и так подключался, это не было проблемой),
 * а напрямую из ServiceManager по имени "com.mega.car.CarService" (видно в `adb shell service list`,
 * строка: "com.mega.car.CarService: [mega.car.ICar]") — ServiceManager.getService — скрытый API,
 * поэтому берём его через reflection, не ссылаясь на класс напрямую (иначе не скомпилируется
 * против публичного android.jar).
 *
 * Классы mega.car.ICar/ICarProperty должны быть доступны в CLASSPATH процесса — для этого
 * при запуске app_process нужно явно указать путь к APK пакета com.mega.car через -cp, например:
 *
 *   adb shell
 *   (на устройстве:)
 *   app_process -cp "$(pm path com.evlogger.hu | sed 's/^package://'):$(pm path com.mega.car | sed 's/^package://')" /system/bin com.evlogger.hu.ShellBridgeMainKt 83886146 0
 *
 * Аргументы: [0] = числовой id свойства (по умолчанию 83886146 = ENGINE_STATUS из CarIds.kt,
 * удобно для проверки: 0=STOP 1=CRANK 2=RUNNING), [1] = area (по умолчанию 0).
 *
 * Только чтение. Ничего не меняет, не пишет, не инжектирует значения.
 */
fun main(args: Array<String>) {
    fun out(s: String) {
        println(s)
        System.out.flush()
    }

    fun root(e: Throwable): Throwable {
        var t: Throwable = e
        while (t is InvocationTargetException && t.cause != null) t = t.cause!!
        return t
    }

    val id = args.getOrNull(0)?.toIntOrNull() ?: 83886146
    val area = args.getOrNull(1)?.toIntOrNull() ?: 0

    out("=== ShellBridgeMain старт ===")
    out("uid процесса (ожидаем 2000=shell, если запущено через adb shell app_process): ${Process.myUid()}")
    out("Пробую id=$id area=$area")

    try {
        val smClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = smClass.getMethod("getService", String::class.java)
        val carBinder = getServiceMethod.invoke(null, "com.mega.car.CarService") as? IBinder
        if (carBinder == null) {
            out("ServiceManager.getService(\"com.mega.car.CarService\") вернул null — сервис не найден.")
            return
        }
        out("OK: получен IBinder сервиса com.mega.car.CarService.")

        val iCarStub = Class.forName("mega.car.ICar\$Stub")
        val icar = iCarStub.getMethod("asInterface", IBinder::class.java).invoke(null, carBinder)
        if (icar == null) {
            out("ICar.Stub.asInterface вернул null.")
            return
        }
        out("OK: интерфейс ICar получен.")

        val iCarClass = Class.forName("mega.car.ICar")
        val propBinder = iCarClass.getMethod("getCarService", String::class.java)
            .invoke(icar, "property") as? IBinder
        if (propBinder == null) {
            out("getCarService(\"property\") вернул null.")
            return
        }
        out("OK: получен IBinder подсервиса property.")

        val iCarPropertyStub = Class.forName("mega.car.hardware.property.ICarProperty\$Stub")
        val propObj = iCarPropertyStub.getMethod("asInterface", IBinder::class.java).invoke(null, propBinder)
        if (propObj == null) {
            out("ICarProperty.Stub.asInterface вернул null.")
            return
        }
        out("OK: интерфейс ICarProperty получен. Зову getProperty($id, $area)…")

        val iCarPropertyClass = Class.forName("mega.car.hardware.property.ICarProperty")
        val pv = iCarPropertyClass.getMethod(
            "getProperty",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).invoke(propObj, id, area)

        if (pv == null) {
            out("getProperty вернул null (свойство не поддерживается на этой машине).")
        } else {
            val k = pv.javaClass
            val value = k.getMethod("getValue").invoke(pv)
            val status = k.getMethod("getStatus").invoke(pv)
            out("!!! УСПЕХ !!! value=$value status=$status")
        }
    } catch (e: Throwable) {
        val r = root(e)
        out("ОШИБКА: ${r.javaClass.name}: ${r.message}")
    }
    out("=== ShellBridgeMain конец ===")
}
