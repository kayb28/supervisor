/*
 * Diagnostics — el cerebro del estetoscopio.
 * Cada función ejecuta comandos de solo lectura (vía Shizuku, como shell)
 * y los traduce a español claro. El extracto crudo SIEMPRE acompaña al
 * resumen: la verdad sin filtros queda a la vista del dueño.
 */
package com.kayb28.supervisor

import android.os.Build
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {

    /** Ejecuta un comando de solo lectura como shell vía Shizuku. */
    fun shell(cmd: String): String {
        val proceso = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val salida = try {
            proceso.inputStream.bufferedReader().readText() +
                proceso.errorStream.bufferedReader().readText()
        } finally {
            proceso.waitFor()
        }
        return salida.trim()
    }

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
    // El sistema escribe cada muerte en el registro de eventos (am_kill).
    // Este comando no mata nada: lee lo que el sistema ya escribió.
    // ────────────────────────────────────────────────────────────
    fun quienMatoMisApps(): String {
        val crudo = shell(
            "logcat -d -b events 2>/dev/null | " +
                "grep -E 'am_kill|am_proc_died' | tail -n 300"
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
                appendLine("Ninguna muerte registrada.")
                appendLine("O el sistema no mató nada, o la tablet se")
                appendLine("reinició hace poco y el registro quedó en blanco.")
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
            appendLine("— Extracto crudo del registro del sistema:")
            appendLine(recorteHasta(crudo, 30))
        }
    }

    // ────────────────────────────────────────────────────────────
    // 2. Batería — salud, nivel y temperatura del alma de la tablet.
    // ────────────────────────────────────────────────────────────
    fun bateria(): String {
        val crudo = shell("dumpsys battery 2>/dev/null")

        fun campo(rx: String): String =
            Regex(rx).find(crudo)?.groupValues?.get(1) ?: "?"

        val nivel = campo("""level:\s*(\d+)""")
        val temp = campo("""temperature:\s*(\d+)""").toIntOrNull()
        val salud = campo("""health:\s*(\d+)""").toIntOrNull()
        val estadoCarga = campo("""status:\s*(\d+)""").toIntOrNull()

        val saludTxt = when (salud) {
            2 -> "buena"
            3 -> "SOBRECALENTADA (¡ojo!)"
            4 -> "muerta"
            5 -> "voltaje excesivo"
            7 -> "fría"
            else -> "desconocida (código $salud)"
        }
        val cargaTxt = when (estadoCarga) {
            2 -> "cargando"
            3 -> "llena"
            4 -> "descargando"
            5 -> "llena (no conectada)"
            else -> "desconocido (código $estadoCarga)"
        }
        val tempC = temp?.let { it / 10.0 }

        return buildString {
            appendLine("BATERÍA")
            appendLine("────────────────────────────────────────")
            appendLine("Nivel: $nivel%")
            appendLine("Estado: $cargaTxt")
            appendLine("Salud: $saludTxt")
            appendLine("Temperatura: ${tempC ?: "?"} °C" +
                (if ((tempC ?: 0.0) > 40.0) "  ¡CALIENTE!" else ""))
            appendLine()
            appendLine("El análisis fino de consumo por app (quién se")
            appendLine("bebe la batería dormida) llega en la v2.")
            appendLine()
            appendLine("— Extracto crudo:")
            appendLine(recorteDesde(crudo, 35))
        }
    }

    // ────────────────────────────────────────────────────────────
    // 3. Despertares — las alarmas que despiertan la tablet dormida.
    // ────────────────────────────────────────────────────────────
    fun despertares(): String {
        val crudo = shell("dumpsys alarm 2>/dev/null | head -n 250")
        val proximas = Regex("""when=\d+""").findAll(crudo).count()

        return buildString {
            appendLine("DESPERTARES (ALARMAS)")
            appendLine("────────────────────────────────────────")
            appendLine("Alarmas programadas visibles: $proximas")
            appendLine()
            appendLine("Las alarmas son la razón #1 por la que una")
            appendLine("tablet «dormida» despierta y gasta batería")
            appendLine("sin que nadie la toque. Cualquier app puede")
            appendLine("pedirle al sistema que la despierte. Abajo,")
            appendLine("el registro dice quién tiene alarmas puestas")
            appendLine("y cuántas veces ha disparado cada una.")
            appendLine()
            appendLine("— Extracto crudo (inicio del registro):")
            appendLine(recorteDesde(crudo, 80))
        }
    }

    // ────────────────────────────────────────────────────────────
    // 4. Memoria — cuánta RAM tiene, cuánta usa, cuánta desperdicia.
    // ────────────────────────────────────────────────────────────
    fun memoria(): String {
        val crudo = shell("dumpsys meminfo 2>/dev/null | head -n 40")

        fun kb(rx: String): Long? =
            Regex(rx).find(crudo)?.groupValues?.get(1)
                ?.replace(",", "")?.toLongOrNull()

        val total = kb("""Total RAM:\s*([0-9,]+)""")
        val libre = kb("""Free RAM:\s*([0-9,]+)""")
        val usada = kb("""Used RAM:\s*([0-9,]+)""")
        val perdida = kb("""Lost RAM:\s*([0-9,]+)""")

        val procesos = try {
            shell(
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
            appendLine("Libre: ${gb(libre)}")
            appendLine("Perdida (desperdicio del sistema): ${gb(perdida)}")
            appendLine("Procesos de Xiaomi/MIUI corriendo: $procesos")
            appendLine()
            appendLine("La «RAM perdida» es memoria que ningún proceso")
            appendLine("reclama: gestión deficiente, culpa del sistema,")
            appendLine("no de tus apps.")
            appendLine()
            appendLine("— Extracto crudo:")
            appendLine(recorteDesde(crudo, 35))
        }
    }

    // ────────────────────────────────────────────────────────────
    // TODO: el informe que el dueño copia y le lleva al asistente.
    // ────────────────────────────────────────────────────────────
    fun informeCompleto(): String = buildString {
        val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        appendLine("╔══════════════════════════════════════╗")
        appendLine("   INFORME DEL SUPERVISOR — Fase 1")
        appendLine("   $fecha")
        appendLine("   ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("╚══════════════════════════════════════╝")
        appendLine()
        appendLine(seguro("muertes") { quienMatoMisApps() })
        appendLine()
        appendLine(seguro("bateria") { bateria() })
        appendLine()
        appendLine(seguro("alarmas") { despertares() })
        appendLine()
        appendLine(seguro("memoria") { memoria() })
        appendLine()
        appendLine("— Fin del informe. Cópialo completo y pégaselo")
        appendLine("  al asistente para la semana de observación.")
    }
}
