/*
 * Diagnostics — el cerebro del estetoscopio.
 * Cada función ejecuta comandos de solo lectura a través del pequeño
 * servicio que Shizuku corre con privilegios de shell, y los traduce
 * a español claro. El extracto crudo SIEMPRE acompaña al resumen:
 * la verdad sin filtros queda a la vista del dueño.
 *
 * Desde 0.2.0 la app también pide sus propios permisos de métricas
 * (acceso a datos de uso y lectura del registro): sin ellos hay
 * preguntas que el sistema no deja ni mirar.
 */
package com.kayb28.supervisor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {

    // ────────────────────────────────────────────────────────────
    // Permisos de métricas: saber mirar antes de mirar.
    // ────────────────────────────────────────────────────────────

    /** ¿El dueño concedió «Acceso a datos de uso»? (permiso especial) */
    fun tieneAccesoUso(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** ¿Puede leer el registro del sistema por sí misma? (READ_LOGS, vía ADB) */
    fun tieneReadLogs(context: Context): Boolean = try {
        context.checkSelfPermission(android.Manifest.permission.READ_LOGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** Estado de los tres canales de datos, en texto claro. */
    fun permisosDelInstrumento(context: Context): String = buildString {
        val shizukuVivo = try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Throwable) { false }
        val shizukuConcedido = try {
            shizukuVivo &&
                rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }

        appendLine("PERMISOS DEL INSTRUMENTO")
        appendLine("────────────────────────────────────────")
        appendLine(
            if (tieneAccesoUso(context))
                "Acceso a datos de uso: CONCEDIDO (la sección de uso tendrá datos)"
            else "Acceso a datos de uso: FALTA (sin él, el uso de apps sale vacío)"
        )
        appendLine(
            if (tieneReadLogs(context))
                "Lectura del registro del sistema: concedida"
            else "Lectura del registro del sistema: falta (opcional, vía ADB:"
        )
        if (!tieneReadLogs(context)) {
            appendLine("  adb shell pm grant com.kayb28.supervisor android.permission.READ_LOGS)")
        }
        appendLine(
            if (shizukuConcedido)
                "Canal privilegiado Shizuku: despierto y concedido"
            else "Canal privilegiado Shizuku: dormido o sin conceder"
        )
        appendLine()
        appendLine("El acceso a datos de uso no es un diálogo: es un")
        appendLine("interruptor que solo el dueño puede mover, en Ajustes")
        appendLine("→ Privacidad → Permisos especiales (o con el botón")
        appendLine("de la pantalla principal de esta app).")
    }

    // ────────────────────────────────────────────────────────────
    // El canal: comandos de solo lectura vía Shizuku.
    // ────────────────────────────────────────────────────────────

    /** Ejecuta un comando de solo lectura vía Shizuku. */
    fun shell(svc: IShellService, cmd: String): String {
        val salida = try {
            svc.exec(cmd)
        } catch (e: Throwable) {
            throw IllegalStateException("Shizuku se durmió (${e.message})")
        }
        return salida.trim()
    }

    /** Igual que shell(), pero devuelve null en vez de romper (lecturas opcionales). */
    private fun shellOpcional(svc: IShellService?, cmd: String): String? = try {
        svc?.exec(cmd)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    /** Muestra las primeras N líneas (para volcados largos). */
    private fun recorteDesde(texto: String, max: Int): String {
        if (texto.isEmpty()) return "  (sin datos o no pude leer)"
        val lineas = texto.lines()
        return if (lineas.size <= max) texto
        else lineas.take(max).joinToString("\n") +
            "\n…(mostrando las primeras $max de ${lineas.size} líneas)"
    }

    /** Muestra las últimas N líneas (para registros históricos). */
    private fun recorteHasta(texto: String, max: Int): String {
        if (texto.isEmpty()) return "  (sin datos o no pude leer)"
        val lineas = texto.lines()
        return if (lineas.size <= max) texto
        else lineas.takeLast(max).joinToString("\n") +
            "\n…(mostrando las últimas $max de ${lineas.size} líneas)"
    }

    /** Red de seguridad por sección del informe. */
    private fun seguro(seccion: String, f: () -> String): String =
        try {
            f()
        } catch (e: Throwable) {
            "FALLÓ la sección «$seccion»: ${e.message}\n" +
                "Shizuku probablemente se durmió; despiértalo y reintenta."
        }

    // ────────────────────────────────────────────────────────────
    // 1. ¿Quién mató mi app?
    // El sistema escribe cada muerte en el registro de eventos (am_kill),
    // pero el logcat solo conserva las últimas horas. El histórico que
    // sobrevive al paso del tiempo vive en «exit-info»: salidas de
    // proceso (muertes por sistema, ANR, caídas) desde el arranque.
    // ────────────────────────────────────────────────────────────
    fun quienMatoMisApps(svc: IShellService): String {
        val crudo = shell(
            svc,
            "logcat -d -b events 2>/dev/null | " +
                "grep -E 'am_kill|am_proc_died' | tail -n 300"
        )
        val historial = shellOpcional(
            svc,
            "dumpsys activity exit-info 2>/dev/null | tail -n 60"
        )
        val conteo = mutableMapOf<String, Int>()
        val regex = Regex("""am_kill\([^)]*\):\s*\[([^]]*)\]""")
        for (m in regex.findAll(crudo)) {
            val campos = m.groupValues[1].split(",").map { it.trim() }
            if (campos.size >= 3) {
                val paquete = campos[2]
                conteo[paquete] = (conteo[paquete] ?: 0) + 1
            }
        }
        val total = conteo.values.sum()

        return buildString {
            appendLine("MUERTES DE APPS (desde el último reinicio)")
            appendLine("────────────────────────────────────────")
            if (total == 0) {
                appendLine("Ninguna muerte en el registro reciente.")
                appendLine("O el sistema no mató nada, o el logcat rotó")
                appendLine("(solo guarda horas). Abajo va también el")
                appendLine("histórico desde el arranque: si ahí tampoco")
                appendLine("hay nada, nadie murió de verdad.")
            } else {
                appendLine("Muertes registradas: $total")
                appendLine()
                appendLine("Apps más asesinadas:")
                conteo.entries.sortedByDescending { it.value }.take(10).forEach { (k, v) ->
                    appendLine("  $v ×  $k")
                }
                appendLine()
                appendLine("Ojo: que maten a una app no dice quién ordenó")
                appendLine("la muerte. Con este dato en la mano, la Fase 2")
                appendLine("decide qué apps merecen piedad de batería.")
            }
            appendLine()
            appendLine("— Extracto crudo (logcat, últimas horas):")
            appendLine(recorteHasta(crudo, 30))
            appendLine()
            appendLine("— Extracto crudo (exit-info, desde el arranque):")
            appendLine(
                historial?.let { recorteHasta(it, 30) } ?: "  (no pude leer exit-info)"
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // 2. Batería — salud, nivel, capacidad y siesta del sistema.
    // ────────────────────────────────────────────────────────────
    fun bateria(svc: IShellService): String {
        val crudo = shell(svc, "dumpsys battery 2>/dev/null")

        fun campo(rx: String): String =
            Regex(rx).find(crudo)?.groupValues?.get(1) ?: "?"

        val nivel = campo("""level:\s*(\d+)""")
        val nivelInt = nivel.toIntOrNull()
        val temp = campo("""temperature:\s*(\d+)""").toIntOrNull()
        val salud = campo("""health:\s*(\d+)""").toIntOrNull()
        val estadoCarga = campo("""status:\s*(\d+)""").toIntOrNull()
        val contadorCarga = campo("""Charge counter:\s*(\d+)""").toLongOrNull()

        val saludTxt = when (salud) {
            2 -> "buena"
            3 -> "SOBRECALENTADA (¡ojo!)"
            4 -> "muerta"
            5 -> "voltaje excesivo"
            7 -> "fría"
            else -> "desconocida (código $salud)"
        }
        // Códigos de estado según el propio Android:
        // 2=cargando, 3=descargando, 4=enchufada sin cargar, 5=llena.
        val cargaTxt = when (estadoCarga) {
            2 -> "cargando"
            3 -> "descargando"
            4 -> "enchufada, sin cargar"
            5 -> "llena"
            else -> "desconocido (código $estadoCarga)"
        }
        val tempC = temp?.let { it / 10.0 }

        // Capacidad aprendida: el contador de carga dice cuántos mAh
        // hay ahora; dividido entre el nivel, dice cuánto cree el
        // sistema que cabe la batería entera.
        val capacidad = if (contadorCarga != null && nivelInt != null && nivelInt > 0)
            contadorCarga / 1000.0 / (nivelInt / 100.0) else null

        // Sueño profundo: el tiempo que el sistema pasó suspendido
        // desde el arranque. Menos siesta = más batería despierta.
        val suenoPct = run {
            val real = SystemClock.elapsedRealtime().toDouble()
            val despierto = SystemClock.uptimeMillis().toDouble()
            if (real > 0.0) (1.0 - despierto / real) * 100.0 else null
        }

        return buildString {
            appendLine("BATERÍA")
            appendLine("────────────────────────────────────────")
            appendLine("Nivel: $nivel%")
            appendLine("Estado: $cargaTxt")
            appendLine("Salud: $saludTxt")
            appendLine("Temperatura: ${tempC ?: "?"} °C" +
                (if ((tempC ?: 0.0) > 40.0) "  ¡CALIENTE!" else ""))
            capacidad?.let {
                appendLine(
                    "Capacidad aprendida: ≈ ${String.format(Locale.US, "%.0f", it)} mAh" +
                        " (contador de carga ÷ nivel)"
                )
            }
            suenoPct?.let {
                appendLine(
                    "Sueño profundo desde el arranque: " +
                        String.format(Locale.US, "%.1f", it) + " % del tiempo"
                )
            }
            appendLine()
            appendLine("El consumo real por app se adivina cruzando esta")
            appendLine("lectura con el uso de apps (sección 5) y los")
            appendLine("despertares (sección 3).")
            appendLine()
            appendLine("— Extracto crudo:")
            appendLine(recorteDesde(crudo, 35))
        }
    }

    // ────────────────────────────────────────────────────────────
    // 3. Despertares — las alarmas que despiertan al equipo dormido.
    //    Desde 0.2.0 cuenta las alarmas de verdad (el volcado nuevo
    //    dice «Alarm{…}», no «when=»), y saca el top de dueños.
    // ────────────────────────────────────────────────────────────
    fun despertares(svc: IShellService): String {
        val crudo = shell(svc, "dumpsys alarm 2>/dev/null | head -n 250")
        val totalCrudo = shellOpcional(svc, "dumpsys alarm 2>/dev/null | grep -c 'Alarm{'")
        val total = totalCrudo?.trim()?.toIntOrNull()
        val emisores = shellOpcional(
            svc,
            "dumpsys alarm 2>/dev/null | grep -oE 'uid=[0-9]+ [a-zA-Z0-9._-]+' | " +
                "sed 's/^uid=[0-9]* //' | sort | uniq -c | sort -rn | head -n 12"
        )

        return buildString {
            appendLine("DESPERTARES (ALARMAS)")
            appendLine("────────────────────────────────────────")
            appendLine(
                if (total != null) "Alarmas pendientes en el sistema: $total"
                else "Alarmas pendientes: no pude contarlas (el formato del registro cambió)"
            )
            appendLine()
            if (!emisores.isNullOrBlank()) {
                appendLine("Quién tiene alarmas puestas (por dueño):")
                emisores.lines().forEach { appendLine("  $it") }
                appendLine()
            }
            if (total != null && total == 0) {
                appendLine("Cero alarmas es rarísimo: TIME_TICK y los servicios")
                appendLine("de Google siempre tienen alguna pendiente. Sospecha")
                appendLine("del formato y revisa el extracto crudo de abajo.")
                appendLine()
            }
            appendLine("Las alarmas son la razón #1 por la que un equipo")
            appendLine("«dormido» despierta y gasta batería sin que nadie")
            appendLine("lo toque. Cualquier app puede pedirle al sistema")
            appendLine("que la despierte. El registro de abajo dice quién")
            appendLine("tiene alarmas puestas ahora mismo.")
            appendLine()
            appendLine("— Extracto crudo (inicio del registro):")
            appendLine(recorteDesde(crudo, 80))
        }
    }

    // ────────────────────────────────────────────────────────────
    // 4. Memoria — cuánta RAM hay, cuánta se usa, cuánta se pierde.
    //    El resumen (Total/Used/Free/Lost RAM) vive AL FINAL del
    //    volcado; leer solo la cabecera lo dejaba en «?». Ahora se
    //    lee el final, con /proc/meminfo de respaldo.
    // ────────────────────────────────────────────────────────────
    fun memoria(svc: IShellService): String {
        val crudoProcesos = shell(svc, "dumpsys meminfo 2>/dev/null | head -n 40")
        val crudoResumen = shellOpcional(svc, "dumpsys meminfo 2>/dev/null | tail -n 60") ?: ""
        val meminfo = shellOpcional(svc, "cat /proc/meminfo 2>/dev/null | head -n 5") ?: ""

        fun kbDesde(texto: String, rx: String): Long? =
            Regex(rx).find(texto)?.groupValues?.get(1)
                ?.replace(",", "")?.toLongOrNull()

        val total = kbDesde(crudoResumen, """Total RAM:\s*([0-9,]+)""")
            ?: kbDesde(meminfo, """MemTotal:\s*([0-9,]+)""")
        val usada = kbDesde(crudoResumen, """Used RAM:\s*([0-9,]+)""")
        val libre = kbDesde(crudoResumen, """Free RAM:\s*([0-9,]+)""")
        val perdida = kbDesde(crudoResumen, """Lost RAM:\s*([0-9,]+)""")
        val disponible = kbDesde(meminfo, """MemAvailable:\s*([0-9,]+)""")

        val procesos = try {
            shell(
                svc,
                """ps -A -o NAME 2>/dev/null | """ +
                    """grep -cE '^(com\.miui|com\.xiaomi|miui|vendor\.xiaomi)'"""
            )
        } catch (_: Throwable) { "sin dato" }

        val gb = { n: Long? ->
            if (n == null) "?" else String.format(Locale.US, "%.2f GB", n / 1048576.0)
        }
        val pct = if (total != null && usada != null) {
            " (${usada * 100 / total}%)"
        } else ""

        return buildString {
            appendLine("MEMORIA")
            appendLine("────────────────────────────────────────")
            appendLine("RAM total: ${gb(total)}")
            appendLine("En uso: ${gb(usada)}$pct")
            appendLine(
                if (libre != null) "Libre: ${gb(libre)}"
                else "Disponible (proc): ${gb(disponible)}"
            )
            appendLine(
                if (perdida != null) "Perdida (desperdicio del sistema): ${gb(perdida)}"
                else "Perdida: sin dato (el resumen no apareció en el volcado)"
            )
            appendLine("Procesos de Xiaomi/MIUI corriendo: $procesos")
            appendLine()
            appendLine("La «RAM perdida» es memoria que ningún proceso")
            appendLine("reclama: gestión deficiente, culpa del sistema,")
            appendLine("no de tus apps.")
            appendLine()
            appendLine("— Extracto crudo (procesos que más RAM tienen):")
            appendLine(recorteDesde(crudoProcesos, 35))
            appendLine()
            appendLine("— Extracto crudo (resumen del volcado):")
            appendLine(recorteDesde(crudoResumen, 12))
        }
    }

    // ────────────────────────────────────────────────────────────
    // 5. Uso de apps — quién tuvo la pantalla en las últimas 24 h.
    //    Requiere «Acceso a datos de uso» (permiso especial que
    //    solo el dueño puede mover). NO necesita Shizuku: si está
    //    otorgado, funciona hasta con Shizuku dormido.
    // ────────────────────────────────────────────────────────────
    fun usoDeApps(context: Context, svc: IShellService?): String = buildString {
        appendLine("USO DE APPS (últimas 24 horas)")
        appendLine("────────────────────────────────────────")

        if (!tieneAccesoUso(context)) {
            appendLine()
            appendLine("FALTA el permiso «Acceso a datos de uso».")
            appendLine("Sin él, el sistema no me deja ver quién usó la")
            appendLine("pantalla. Concédelo (una sola vez) y repite:")
            appendLine()
            appendLine("  Ajustes → Privacidad → Permisos especiales")
            appendLine("  → Acceso a datos de uso → Supervisor")
            appendLine()
            appendLine("O por ADB, sin tocar Ajustes:")
            appendLine("  adb shell appops set com.kayb28.supervisor \\")
            appendLine("      android:get_usage_stats allow")
        } else {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val fin = System.currentTimeMillis()
            val inicio = fin - 24L * 3_600_000L
            val eventos = usm.queryEvents(inicio, fin)
            val ev = UsageEvents.Event()
            val enPantalla = HashMap<String, Long>()
            val usoMs = HashMap<String, Long>()
            val aperturas = HashMap<String, Int>()

            while (eventos.hasNextEvent()) {
                eventos.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        enPantalla[pkg] = ev.timeStamp
                        aperturas[pkg] = (aperturas[pkg] ?: 0) + 1
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val desde = enPantalla.remove(pkg)
                        if (desde != null) {
                            usoMs[pkg] = (usoMs[pkg] ?: 0L) +
                                (ev.timeStamp - desde).coerceAtLeast(0L)
                        }
                    }
                }
            }
            // Lo que seguía en pantalla cuando terminó la consulta.
            for ((pkg, desde) in enPantalla) {
                usoMs[pkg] = (usoMs[pkg] ?: 0L) + (fin - desde).coerceAtLeast(0L)
            }

            if (usoMs.isEmpty()) {
                appendLine()
                appendLine("Nada usó la pantalla en las últimas 24 h")
                appendLine("(o el sistema podó el historial).")
            } else {
                appendLine()
                appendLine("Quién tuvo la pantalla (tiempo en primer plano):")
                usoMs.entries.sortedByDescending { it.value }.take(15).forEach { (pkg, ms) ->
                    val min = ms / 60_000.0
                    val veces = aperturas[pkg] ?: 0
                    appendLine(
                        String.format(Locale.US, "  %8.1f min  %3d×  %s", min, veces, pkg)
                    )
                }
                appendLine()
                appendLine("«min» = minutos en primer plano; «×» = aperturas.")
                appendLine("Cruza esta lista con la batería y tendrás al")
                appendLine("culpable del consumo con nombre y apellidos.")
            }
        }

        // Extracto crudo opcional: el historial que guarda el sistema
        // (solo si Shizuku está despierto; si duerme, no pasa nada).
        if (svc != null) {
            appendLine()
            appendLine("— Extracto crudo (histórico del sistema):")
            appendLine(
                try {
                    recorteDesde(
                        shell(svc, "dumpsys usagestats 2>/dev/null | head -n 60"),
                        40
                    )
                } catch (_: Throwable) {
                    "  (Shizuku dormido; sin extracto)"
                }
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // 6. El informe que el dueño copia y le lleva al asistente.
    // ────────────────────────────────────────────────────────────
    fun informeCompleto(context: Context, svc: IShellService): String = buildString {
        val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        appendLine("╔══════════════════════════════════════╗")
        appendLine("   INFORME DEL SUPERVISOR — Fase 1")
        appendLine("   $fecha")
        appendLine("   ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("╚══════════════════════════════════════╝")
        appendLine()
        appendLine(seguro("permisos") { permisosDelInstrumento(context) })
        appendLine()
        appendLine(seguro("muertes") { quienMatoMisApps(svc) })
        appendLine()
        appendLine(seguro("bateria") { bateria(svc) })
        appendLine()
        appendLine(seguro("alarmas") { despertares(svc) })
        appendLine()
        appendLine(seguro("memoria") { memoria(svc) })
        appendLine()
        appendLine(seguro("uso") { usoDeApps(context, svc) })
        appendLine()
        appendLine("— Fin del informe. Cópialo completo y pégaselo")
        appendLine("  al asistente para la semana de observación.")
    }
}
