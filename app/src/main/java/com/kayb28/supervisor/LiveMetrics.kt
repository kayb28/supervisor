/*
 * LiveMetrics — el monitor en tiempo real.
 *
 * Una sola llamada al canal de Shizuku lee TODO el panel: CPU por
 * núcleo (uso y frecuencia), GPU (ocupación y frecuencia, Adreno
 * kgsl con respaldo devfreq), RAM y swap, batería instantánea,
 * temperaturas por zona y almacenamiento (ocupación, ritmo de
 * escritura, E/S en vivo y una ESTIMACIÓN de salud).
 *
 * El truco para las métricas «por segundo»: el guion lee /proc/stat
 * y las estadísticas de disco DOS veces separadas por «sleep 1»,
 * y Kotlin calcula los deltas. Sin $ en el guion (chocarían con
 * las plantillas de Kotlin): los archivos múltiples se leen con
 * «grep -H .», que imprime «ruta: valor».
 *
 * Todo es de solo lectura y degrada con elegancia: lo que el kernel
 * no deja ver sale como «no legible», nunca como mentira.
 */
package com.kayb28.supervisor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object LiveMetrics {

    /** Ventana de muestreo dentro de cada medición (ms). */
    const val VENTANA_MS = 1000L

    /** Refresco recomendado del panel en vivo (ms). */
    const val REFRESCO_MS = 2000L

    // ────────────────────────────────────────────────────────────
    // El guion de muestreo (una sola ida al shell por tick).
    // ────────────────────────────────────────────────────────────
    private val GUION = """
echo @@STAT1
cat /proc/stat
echo @@BLOQUEO1
grep -H . /sys/block/sd*/stat /sys/block/mmcblk*/stat 2>/dev/null
echo @@DORMIR
sleep 1
echo @@STAT2
cat /proc/stat
echo @@BLOQUEO2
grep -H . /sys/block/sd*/stat /sys/block/mmcblk*/stat 2>/dev/null
echo @@CPUFREQ
grep -H . /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq 2>/dev/null
echo @@CPUMAX
grep -H . /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq 2>/dev/null
echo @@HARDWARE
grep -m1 -i hardware /proc/cpuinfo
grep -m1 -i "model name" /proc/cpuinfo
echo @@GPUMODELO
cat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null
echo @@GPUOCUPA
cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null
echo @@GPUFREQ
grep -H . /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
echo @@DEVFREQ
grep -H . /sys/class/devfreq/*/cur_freq /sys/class/devfreq/*/max_freq /sys/class/devfreq/*/load 2>/dev/null
echo @@MEM
grep -E "^(MemTotal|MemFree|MemAvailable|SwapTotal|SwapFree):" /proc/meminfo
echo @@ZRAM
cat /sys/block/zram0/mm_stat 2>/dev/null
echo @@CORRIENTE
grep -H . /sys/class/power_supply/battery/current_now /sys/class/power_supply/battery/temp /sys/class/power_supply/battery/cycle_count 2>/dev/null
echo @@DUMPSYSBAT
dumpsys battery 2>/dev/null
echo @@TERMICO
grep -H . /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp 2>/dev/null
echo @@MONTAJE
grep " /data " /proc/mounts
echo @@DF
df -k /data 2>/dev/null
echo @@F2FS
grep -H . /sys/fs/f2fs/*/unusable /sys/fs/f2fs/*/gc_mode 2>/dev/null
echo @@UFS
grep -H . /sys/bus/platform/devices/*ufshc*/health_descriptor/* /sys/devices/platform/soc/*ufshc*/health_descriptor/* 2>/dev/null
echo @@FIN
""".trimIndent()

    // ────────────────────────────────────────────────────────────
    // Lectura: todo lo que el guion trajo, ya masticado.
    // ────────────────────────────────────────────────────────────
    data class Nucleo(val num: Int, val usoPct: Int, val curMhz: Int, val maxMhz: Int)

    data class Lectura(
        val nucleos: List<Nucleo>,
        val usoTotalPct: Int,
        val hardware: String,
        val gpuModelo: String,
        val gpuOcupaPct: Int?,
        val gpuCurMhz: Int?,
        val gpuMaxMhz: Int?,
        val gpuFuente: String?,
        val memTotalKb: Long?,
        val memAvailKb: Long?,
        val memFreeKb: Long?,
        val swapTotalKb: Long?,
        val swapLibreKb: Long?,
        val zramOrigB: Long?,
        val zramMemB: Long?,
        val nivel: Int?,
        val estadoTxt: String,
        val saludTxt: String,
        val tempBatC: Double?,
        val corrienteMa: Int?,
        val ciclos: Int?,
        val fsTipo: String,
        val discoTotalKb: Long?,
        val discoUsadoKb: Long?,
        val discoLibreKb: Long?,
        val escritoDesdeArranqueGb: Double?,
        val leidoDesdeArranqueGb: Double?,
        val escribirMbS: Double?,
        val leerMbS: Double?,
        val cpuTempC: Double?,
        val cpuTempZona: String?,
        val gpuTempC: Double?,
        val tempMaxC: Double?,
        val ufsPreEol: Int?,
        val ufsVidaA: Int?,
        val ufsVidaB: Int?,
        val notasF2fs: List<String>,
        val hora: String
    )

    private data class StatCpu(val idle: Long, val total: Long)
    private data class StatBloque(val leidos: Long, val escritos: Long)

    /** Ejecuta el guion y devuelve la lectura parseada. */
    fun muestrear(svc: IShellService): Lectura {
        val crudo = svc.exec(GUION)
        if (crudo.startsWith("ERROR:")) {
            throw IllegalStateException("el canal Shizuku falló (${crudo.trim()})")
        }
        val s = secciones(crudo)

        // — CPU: deltas entre las dos ventanas —
        val c1 = statCpu(s["STAT1"] ?: "")
        val c2 = statCpu(s["STAT2"] ?: "")
        val cur = frecuencias(s["CPUFREQ"])
        val tope = frecuencias(s["CPUMAX"])

        val nucleos = ArrayList<Nucleo>()
        var usoTotalPct = 0
        val agregada1 = c1["cpu"]
        val agregada2 = c2["cpu"]
        if (agregada1 != null && agregada2 != null) {
            usoTotalPct = usoPct(agregada1, agregada2)
        }
        for (num in 0 until 32) {
            val a = c1["cpu$num"] ?: continue
            val b = c2["cpu$num"] ?: break
            nucleos.add(
                Nucleo(
                    num = num,
                    usoPct = usoPct(a, b),
                    curMhz = (cur[num] ?: -1L).let { if (it > 0) (it / 1000).toInt() else -1 },
                    maxMhz = (tope[num] ?: -1L).let { if (it > 0) (it / 1000).toInt() else -1 }
                )
            )
        }

        // — GPU —
        val kgslModelo = (s["GPUMODELO"] ?: "").trim().lines().firstOrNull { it.isNotBlank() } ?: ""
        val kgslOcupa = (s["GPUOCUPA"] ?: "").trim().split(Regex("\\s+"))
            .firstOrNull()?.trimEnd('%')?.toIntOrNull()
        val kgslCurHz = valorPorSufijo(s["GPUFREQ"], "cur_freq")
        val kgslMaxHz = valorPorSufijo(s["GPUFREQ"], "max_freq")

        var gpuModelo = kgslModelo
        var gpuOcupa = kgslOcupa
        var gpuCurHz = kgslCurHz
        var gpuMaxHz = kgslMaxHz
        var gpuFuente: String? = if (kgslOcupa != null) "Adreno/kgsl" else null

        if (gpuOcupa == null) {
            // Respaldo genérico: devfreq con nombre de GPU (Mali, etc.).
            val dv = devfreq(s["DEVFREQ"])
            val clave = dv.keys.firstOrNull {
                it.contains(Regex("kgsl|gpu|adreno|mali", RegexOption.IGNORE_CASE))
            }
            if (clave != null) {
                val d = dv[clave] ?: emptyMap()
                gpuFuente = "devfreq/$clave"
                val carga = d["load"] ?: ""
                if (carga.contains('@')) {
                    gpuOcupa = carga.substringBefore('@').trim().toIntOrNull()
                    val hzParte = carga.substringAfter('@').removeSuffix("Hz").trim()
                    gpuCurHz = hzParte.toLongOrNull() ?: gpuCurHz
                }
                if (gpuCurHz == null) gpuCurHz = d["cur_freq"]?.toLongOrNull()
                gpuMaxHz = d["max_freq"]?.toLongOrNull() ?: gpuMaxHz
                if (gpuModelo.isBlank()) gpuModelo = clave
            }
        }
        val gpuCurMhz = gpuCurHz?.let { (it / 1_000_000L).toInt() }
        val gpuMaxMhz = gpuMaxHz?.let { (it / 1_000_000L).toInt() }

        // — RAM —
        val memTotal = kb(s["MEM"], "MemTotal:")
        val memLibre = kb(s["MEM"], "MemFree:")
        val memDisp = kb(s["MEM"], "MemAvailable:")
        val swapTotal = kb(s["MEM"], "SwapTotal:")
        val swapLibre = kb(s["MEM"], "SwapFree:")
        val zram = (s["ZRAM"] ?: "").trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        val zramOrig = zram.getOrNull(0)
        val zramMem = zram.getOrNull(2)

        // — Batería —
        val bat = s["DUMPSYSBAT"] ?: ""
        fun campo(rx: String): String =
            Regex(rx).find(bat)?.groupValues?.get(1) ?: ""
        val nivel = campo("""level:\s*(\d+)""").toIntOrNull()
        val estado = campo("""status:\s*(\d+)""").toIntOrNull()
        val salud = campo("""health:\s*(\d+)""").toIntOrNull()
        val tempBat = campo("""temperature:\s*(\d+)""").toDoubleOrNull()?.let { it / 10.0 }
        val corriente = valorPorSufijo(s["CORRIENTE"], "current_now")?.let {
            (kotlin.math.abs(it) / 1000L).toInt()
        }
        val ciclos = valorPorSufijo(s["CORRIENTE"], "cycle_count")?.toInt()

        val estadoTxt = when (estado) {
            2 -> "cargando"; 3 -> "descargando"
            4 -> "enchufada, sin cargar"; 5 -> "llena"
            else -> "desconocido"
        }
        val saludTxt = when (salud) {
            2 -> "buena"; 3 -> "sobrecalentada"; 4 -> "muerta"
            5 -> "voltaje excesivo"; 7 -> "fría"
            else -> "sin dato"
        }

        // — Disco: tipo, espacio y deltas de E/S —
        val montaje = (s["MONTAJE"] ?: "").lines().firstOrNull { it.contains(" /data ") }
        val fsTipo = montaje?.trim()?.split(Regex("\\s+"))?.getOrNull(2) ?: "¿?"
        val dfl = (s["DF"] ?: "").lines().lastOrNull { it.trim().startsWith("/") }
        val dcampos = dfl?.trim()?.split(Regex("\\s+"))
        val discoTotal = dcampos?.getOrNull(1)?.toLongOrNull()
        val discoUsado = dcampos?.getOrNull(2)?.toLongOrNull()
        val discoLibre = dcampos?.getOrNull(3)?.toLongOrNull()

        val b1 = statBloque(s["BLOQUEO1"])
        val b2 = statBloque(s["BLOQUEO2"])
        val l1 = b1.values.fold(0L) { acc, v -> acc + v.leidos }
        val l2 = b2.values.fold(0L) { acc, v -> acc + v.leidos }
        val e1 = b1.values.fold(0L) { acc, v -> acc + v.escritos }
        val e2 = b2.values.fold(0L) { acc, v -> acc + v.escritos }
        val escritoGb = e2.takeIf { it > 0 }?.let { it * 512.0 / 1e9 }
        val leidoGb = l2.takeIf { it > 0 }?.let { it * 512.0 / 1e9 }
        val escribirMbS = if (b1.isNotEmpty()) (e2 - e1) * 512.0 / 1e6 / (VENTANA_MS / 1000.0) else null
        val leerMbS = if (b1.isNotEmpty()) (l2 - l1) * 512.0 / 1e6 / (VENTANA_MS / 1000.0) else null

        // — Temperaturas por zona —
        val zonas = termicas(s["TERMICO"])
        val cpuZonas = zonas.filter { it.first.contains("cpu", ignoreCase = true) }
        val cpuMax = cpuZonas.maxByOrNull { it.second }
        val gpuZona = zonas.firstOrNull { it.first.contains("gpu", ignoreCase = true) }
        val tempMax = zonas.maxByOrNull { it.second }

        // — UFS y f2fs: salud del chip, si el kernel la deja ver —
        val ufs = s["UFS"] ?: ""
        fun ufsCampo(nombre: String): Int? {
            val l = ufs.lines().firstOrNull { li ->
                li.contains("/$nombre:") || li.endsWith(": $nombre")
            } ?: return null
            val v = l.substringAfter(": ").trim()
            return if (v.startsWith("0x")) v.removePrefix("0x").toIntOrNull(16) else v.toIntOrNull()
        }
        val preEol = ufsCampo("preEOLInfo")
        val vidaA = ufsCampo("lifetimeA")
        val vidaB = ufsCampo("lifetimeB")
        val notasF2fs = (s["F2FS"] ?: "").lines()
            .filter { it.contains(": ") }
            .map { it.substringAfterLast('/').replace(":", " →") }

        return Lectura(
            nucleos = nucleos,
            usoTotalPct = usoTotalPct,
            hardware = hardware(s["HARDWARE"]),
            gpuModelo = gpuModelo,
            gpuOcupaPct = gpuOcupa,
            gpuCurMhz = gpuCurMhz,
            gpuMaxMhz = gpuMaxMhz,
            gpuFuente = gpuFuente,
            memTotalKb = memTotal,
            memAvailKb = memDisp,
            memFreeKb = memLibre,
            swapTotalKb = swapTotal,
            swapLibreKb = swapLibre,
            zramOrigB = zramOrig,
            zramMemB = zramMem,
            nivel = nivel,
            estadoTxt = estadoTxt,
            saludTxt = saludTxt,
            tempBatC = tempBat,
            corrienteMa = corriente,
            ciclos = ciclos,
            fsTipo = fsTipo,
            discoTotalKb = discoTotal,
            discoUsadoKb = discoUsado,
            discoLibreKb = discoLibre,
            escritoDesdeArranqueGb = escritoGb,
            leidoDesdeArranqueGb = leidoGb,
            escribirMbS = escribirMbS?.let { if (it >= 0) it else null },
            leerMbS = leerMbS?.let { if (it >= 0) it else null },
            cpuTempC = cpuMax?.second,
            cpuTempZona = cpuMax?.first,
            gpuTempC = gpuZona?.second,
            tempMaxC = tempMax?.second,
            ufsPreEol = preEol,
            ufsVidaA = vidaA,
            ufsVidaB = vidaB,
            notasF2fs = notasF2fs,
            hora = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        )
    }

    // ────────────────────────────────────────────────────────────
    // Render: el panel completo o el instante para el informe.
    // ────────────────────────────────────────────────────────────
    fun panel(context: Context, svc: IShellService): String =
        render(context, muestrear(svc), completo = true)

    fun instante(context: Context, svc: IShellService): String =
        render(context, muestrear(svc), completo = false)

    private fun render(context: Context, l: Lectura, completo: Boolean): String = buildString {
        val hora = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        if (completo) {
            appendLine("PANEL EN TIEMPO REAL — $hora")
            appendLine("vía Shizuku (shell) · ventana de muestreo 1 s · refresco ${REFRESCO_MS / 1000} s")
            appendLine()
            appendLine("DISPOSITIVO")
            appendLine(SEP)
            appendLine(dispositivo(context, l))
            appendLine()
            appendLine(cpu(l))
            appendLine()
            appendLine(gpu(l))
            appendLine()
            appendLine(ram(l))
            appendLine()
            appendLine(bateria(l))
            appendLine()
            appendLine(termometro(l))
            appendLine()
            appendLine(almacenamiento(l, completo = true))
        } else {
            appendLine("PANEL EN VIVO (instante al generar el informe)")
            appendLine(SEP)
            appendLine(cpu(l, compacto = true))
            appendLine(gpu(l, compacto = true))
            appendLine(ram(l, compacto = true))
            appendLine(almacenamiento(l, completo = false))
            appendLine(bateria(l, compacto = true))
            appendLine(termometro(l, compacto = true))
            appendLine()
            appendLine("El uso de CPU/GPU es instantáneo (1 s de ventana):")
            appendLine("dice cómo está el equipo ahora, no quién gasta la")
            appendLine("batería a lo largo del día. Para eso, el resto del")
            appendLine("informe.")
        }
    }

    // ────────────────────────────────────────────────────────────
    // Bloques del panel.
    // ────────────────────────────────────────────────────────────
    private const val SEP = "────────────────────────────────────────"

    private fun dispositivo(context: Context, l: Lectura): String = buildString {
        appendLine(
            "  ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
                " (SDK ${Build.VERSION.SDK_INT})"
        )
        appendLine("  SoC: ${l.hardware}")
        append("  ${l.nucleos.size} núcleos")
        pantalla(context)?.let { (a, h, hz) ->
            append(" · pantalla ${a}×${h} · ${String.format(Locale.US, "%.0f", hz)} Hz")
        }
        appendLine()
        appendLine("  kernel ${System.getProperty("os.version", "?")}")
        appendLine("  encendido desde hace ${tiempoEncendido()}")
    }

    private fun cpu(l: Lectura, compacto: Boolean = false): String = buildString {
        appendLine(if (compacto) "CPU: ${l.usoTotalPct}% de media" else "CPU — ${l.nucleos.size} NÚCLEOS")
        if (!compacto) {
            appendLine(SEP)
            val maxTotal = l.nucleos.maxOfOrNull { it.maxMhz } ?: -1
            l.nucleos.forEach { n ->
                val freq = if (n.curMhz > 0) String.format(Locale.US, "%4d MHz", n.curMhz) else "  ---- MHz"
                appendLine(
                    String.format(
                        Locale.US, "  cpu%d %s %3d%%  %s", n.num, barra(n.usoPct), n.usoPct, freq
                    )
                )
            }
            append("  uso medio ${l.usoTotalPct}%")
            if (maxTotal > 0) append(" · frecuencia de diseño ${maxTotal} MHz")
            appendLine()
        } else {
            val activos = l.nucleos.filter { it.curMhz > 0 }
            val min = activos.minOfOrNull { it.curMhz }
            val max = activos.maxOfOrNull { it.curMhz }
            val maxTotal = l.nucleos.maxOfOrNull { it.maxMhz }
            if (min != null && max != null) {
                append(" · frecuencias ${min}–${max} MHz")
                if (maxTotal != null && maxTotal > 0) append(" (máx $maxTotal)")
            }
            appendLine()
        }
    }

    private fun gpu(l: Lectura, compacto: Boolean = false): String = buildString {
        val nombre = if (l.gpuModelo.isBlank()) "GPU" else l.gpuModelo
        val fuente = l.gpuFuente ?: "sin fuente legible"
        if (compacto) append("GPU ($nombre): ") else {
            appendLine("GPU — $nombre ($fuente)")
            appendLine(SEP)
            append("  ")
        }
        when {
            l.gpuOcupaPct != null -> {
                if (!compacto) append("ocupación ${barra(l.gpuOcupaPct)} ${l.gpuOcupaPct}%")
                else append("${l.gpuOcupaPct}%")
                if (l.gpuCurMhz != null && l.gpuCurMhz > 0) {
                    append(" · ${l.gpuCurMhz} MHz")
                    if (l.gpuMaxMhz != null && l.gpuMaxMhz > 0) append(" (máx ${l.gpuMaxMhz})")
                }
                appendLine()
            }
            else -> appendLine("no legible en este equipo (ni kgsl ni devfreq publican ocupación)")
        }
    }

    private fun ram(l: Lectura, compacto: Boolean = false): String = buildString {
        if (compacto) append("RAM: ") else {
            appendLine("RAM")
            appendLine(SEP)
            append("  ")
        }
        val total = l.memTotalKb
        val disp = l.memAvailKb
        if (total != null && disp != null) {
            val enUso = total - disp
            val pct = if (total > 0) enUso * 100 / total else 0
            append(
                "total ${gb(total)} · en uso ${gb(enUso)} ($pct%) · disponible ${gb(disp)}"
            )
            appendLine()
        } else appendLine("sin dato (no pude leer /proc/meminfo)")
        if (!compacto) {
            val swap = l.swapTotalKb
            val swapL = l.swapLibreKb
            if (swap != null && swapL != null && swap > 0) {
                val usado = swap - swapL
                appendLine(
                    "  swap ${gb(swap)} · en uso ${gb(usado)} (${usado * 100 / swap}%)"
                )
            }
            val orig = l.zramOrigB
            val mem = l.zramMemB
            if (orig != null && mem != null && orig > 0) {
                val ratio = (1.0 - mem.toDouble() / orig) * 100.0
                appendLine(
                    "  zram: ${gb(orig)} de datos comprimidos en ${gb(mem)} de RAM " +
                        String.format(Locale.US, "(%.0f%% ahorrado)", ratio)
                )
            }
        }
    }

    private fun bateria(l: Lectura, compacto: Boolean = false): String = buildString {
        if (compacto) append("BATERÍA: ") else {
            appendLine("BATERÍA")
            appendLine(SEP)
            append("  ")
        }
        append("${l.nivel ?: "?"}% ${l.estadoTxt}")
        l.tempBatC?.let { append(" · ${fmt1(it)} °C") }
        l.corrienteMa?.let { append(" · fluye ≈ ${it} mA") }
        if (!compacto) {
            append(" · salud ${l.saludTxt}")
            l.ciclos?.let { if (it > 0) append(" · ${it} ciclos") }
        }
        appendLine()
    }

    private fun termometro(l: Lectura, compacto: Boolean = false): String = buildString {
        if (compacto) append("CALOR: ") else {
            appendLine("TERMÓMETRO (zonas del sistema)")
            appendLine(SEP)
            append("  ")
        }
        append("CPU ")
        if (l.cpuTempC != null) append("${fmt0(l.cpuTempC)} °C") else append("?")
        if (!compacto) l.cpuTempZona?.let { append(" (${it})") }
        append(" · GPU ")
        if (l.gpuTempC != null) append("${fmt0(l.gpuTempC)} °C") else append("?")
        append(" · batería ")
        if (l.tempBatC != null) append("${fmt1(l.tempBatC)} °C") else append("?")
        if (!compacto) {
            l.tempMaxC?.let { append(String.format(Locale.US, " · zona más caliente %.0f °C", it)) }
            appendLine()
            appendLine("  (zonas que reporta el kernel; el resto no es público)")
        } else appendLine()
    }

    private fun almacenamiento(l: Lectura, completo: Boolean): String = buildString {
        val total = l.discoTotalKb
        val usado = l.discoUsadoKb
        val libre = l.discoLibreKb
        val occ = if (total != null && total > 0 && usado != null) usado * 100 / total else null
        val dias = SystemClock.elapsedRealtime() / 86_400_000.0
        val gbDia = l.escritoDesdeArranqueGb?.let { if (dias >= 0.2) it / dias else null }
        val salud = saludDelDisco(l)

        if (completo) {
            appendLine("ALMACENAMIENTO — /data ${l.fsTipo}")
            appendLine(SEP)
            if (total != null && usado != null && libre != null) {
                appendLine("  ${gb(usado)} usados de ${gb(total)} ($occ% ocupado, ${gb(libre)} libres)")
            } else {
                appendLine("  sin dato de espacio (df ilegible)")
            }
            if (dias >= 0.2) {
                l.escritoDesdeArranqueGb?.let {
                    appendLine("  escrito desde el arranque: ${fmt1(it)} GB · ≈ ${fmt1(gbDia ?: 0.0)} GB/día")
                }
                l.leidoDesdeArranqueGb?.let { appendLine("  leído desde el arranque: ${fmt1(it)} GB") }
            }
            if (l.leerMbS != null || l.escribirMbS != null) {
                appendLine(
                    "  ahora: lectura ≈ " + (l.leerMbS?.let { fmt1(it) } ?: "?") +
                        " MB/s · escritura ≈ " + (l.escribirMbS?.let { fmt1(it) } ?: "?") + " MB/s"
                )
            }
            appendLine()
            appendLine("  SALUD (estimada): ${salud.puntos}/100 — ${salud.veredicto}")
            salud.detalle.forEach { appendLine("  $it") }
            appendLine("  ${fragmentacion(l)}")
            if (l.notasF2fs.isNotEmpty()) {
                appendLine("  f2fs: ${l.notasF2fs.joinToString(" · ")}")
            }
        } else {
            append("DISCO (/data ${l.fsTipo}): ")
            if (occ != null) append("$occ% ocupado") else append("espacio ilegible")
            if (gbDia != null) append(" · ≈ ${fmt1(gbDia)} GB escritos/día")
            appendLine(" · salud ${salud.puntos}/100 · fragmentación: no medible sin root")
        }
    }

    // ────────────────────────────────────────────────────────────
    // La estimación de salud del disco: honesta y con los cálculos
    // a la vista. Lo que no se puede leer, se dice.
    // ────────────────────────────────────────────────────────────
    data class Salud(val puntos: Int, val veredicto: String, val detalle: List<String>)

    private fun saludDelDisco(l: Lectura): Salud {
        val detalle = ArrayList<String>()
        var puntos = 100

        val total = l.discoTotalKb
        val usado = l.discoUsadoKb
        val libre = l.discoLibreKb
        val occ = if (total != null && usado != null && total > 0) usado * 100 / total else null
        if (occ != null) {
            when {
                occ > 95 -> { puntos -= 25; detalle.add("−25 ocupación ${occ}% (crítico: el sistema de archivos ahoga)") }
                occ > 90 -> { puntos -= 15; detalle.add("−15 ocupación ${occ}%") }
                occ > 80 -> { puntos -= 8; detalle.add("−8 ocupación ${occ}%") }
                occ > 70 -> { puntos -= 4; detalle.add("−4 ocupación ${occ}%") }
                else -> detalle.add("ocupación ${occ}%: sin penalización")
            }
        } else detalle.add("ocupación: no pude leerla")

        val dias = SystemClock.elapsedRealtime() / 86_400_000.0
        val gbDia = l.escritoDesdeArranqueGb?.let { if (dias >= 0.2) it / dias else null }
        if (gbDia != null) {
            when {
                gbDia > 60 -> { puntos -= 15; detalle.add(String.format(Locale.US, "−15 ritmo de escritura %.1f GB/día (abusivo)", gbDia)) }
                gbDia > 30 -> { puntos -= 8; detalle.add(String.format(Locale.US, "−8 ritmo de escritura %.1f GB/día", gbDia)) }
                gbDia > 15 -> { puntos -= 4; detalle.add(String.format(Locale.US, "−4 ritmo de escritura %.1f GB/día", gbDia)) }
                else -> detalle.add(String.format(Locale.US, "ritmo de escritura %.1f GB/día: normal", gbDia))
            }
        } else detalle.add("ritmo de escritura: sin dato (equipo recién encendido o disco ilegible)")

        val vidaA = l.ufsVidaA
        val vidaB = l.ufsVidaB
        if (vidaA != null || vidaB != null) {
            val vida = max(vidaA ?: 0, vidaB ?: 0)
            // UFS cuenta la vida en tramos de 10%: 0x00 = 0–10% usado…
            puntos = (100 - vida * 5).coerceIn(0, 100)
            detalle.add(
                "vida del chip (UFS): ≈ ${vida * 10}–${vida * 10 + 10}% consumida " +
                    "(dato real del hardware, manda sobre la heurística)"
            )
            if (l.ufsPreEol == 3) {
                puntos = minOf(puntos, 40)
                detalle.add("preEOLInfo = 3: el chip anuncia fin de vida próximo")
            } else if (l.ufsPreEol == 1) {
                detalle.add("preEOLInfo = 1: normal")
            }
        } else {
            detalle.add("vida del chip UFS: no legible sin root (solo estimación por uso)")
        }
        puntos = puntos.coerceIn(0, 100)
        val veredicto = when {
            puntos >= 85 -> "sano"
            puntos >= 70 -> "aceptable"
            puntos >= 50 -> "para vigilar"
            else -> "desgaste notable"
        }
        return Salud(puntos, veredicto, detalle)
    }

    private fun fragmentacion(l: Lectura): String = when (l.fsTipo) {
        "f2fs" ->
            "Fragmentación: el kernel no la publica sin root; f2fs se" +
            " defragmenta solo (gc), y su peor enemigo es la ocupación alta."
        "ext4" ->
            "Fragmentación: sin e4defrag (root) no hay medida exacta;" +
            " f2fs/ext4 en móvil sufren poco, y la ocupación es el proxy."
        else ->
            "Fragmentación: sin medida exacta en ${l.fsTipo} sin root;" +
            " la ocupación es el mejor proxy disponible."
    }

    // ────────────────────────────────────────────────────────────
    // Panel básico sin Shizuku: lo que Android deja ver a cualquier app.
    // ────────────────────────────────────────────────────────────
    fun panelBasico(context: Context, nota: String): String = buildString {
        appendLine("PANEL BÁSICO — sin Shizuku")
        appendLine(SEP)
        appendLine("  $nota")
        appendLine("  Para el panel completo: abre Shizuku y pulsa «Arrancar».")
        appendLine()
        appendLine("DISPOSITIVO")
        appendLine(SEP)
        appendLine(
            "  ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
                " (SDK ${Build.VERSION.SDK_INT})"
        )
        appendLine("  kernel ${System.getProperty("os.version", "?")}")
        appendLine("  encendido desde hace ${tiempoEncendido()}")
        appendLine()
        appendLine("RAM (lo que Android deja contar)")
        appendLine(SEP)
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val pct = if (mi.totalMem > 0) (mi.totalMem - mi.availMem) * 100 / mi.totalMem else 0
            appendLine(
                "  total ${gbB(mi.totalMem)} · en uso ${gbB(mi.totalMem - mi.availMem)} ($pct%)"
            )
        } catch (e: Throwable) {
            appendLine("  sin dato (${e.message})")
        }
        appendLine()
        appendLine("ALMACENAMIENTO (/data)")
        appendLine(SEP)
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.totalBytes
            val libre = st.availableBytes
            val usado = total - libre
            appendLine(
                "  ${gbB(usado)} usados de ${gbB(total)} " +
                    "(${if (total > 0) usado * 100 / total else 0}% ocupado)"
            )
        } catch (e: Throwable) {
            appendLine("  sin dato (${e.message})")
        }
        appendLine()
        appendLine("BATERÍA")
        appendLine(SEP)
        try {
            val senal = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val nivel = senal?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val escala = senal?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val temp = senal?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val estado = senal?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val estadoTxt = when (estado) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "cargando"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "descargando"
                BatteryManager.BATTERY_STATUS_FULL -> "llena"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "enchufada, sin cargar"
                else -> "desconocido"
            }
            if (nivel >= 0 && escala > 0) {
                append("  ${nivel * 100 / escala}% $estadoTxt")
            } else {
                append("  sin dato de nivel ($estadoTxt)")
            }
            if (temp > 0) append(" · ${fmt1(temp / 10.0)} °C")
            appendLine()
        } catch (e: Throwable) {
            appendLine("  sin dato (${e.message})")
        }
    }

    // ────────────────────────────────────────────────────────────
    // Pequeños ayudantes.
    // ────────────────────────────────────────────────────────────
    private fun secciones(crudo: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val partes = crudo.split("@@")
        for (i in 1 until partes.size) {
            val p = partes[i]
            val nl = p.indexOf('\n')
            if (nl < 0) out[p.trim()] = ""
            else out[p.substring(0, nl).trim()] = p.substring(nl + 1)
        }
        return out
    }

    private fun usoPct(a: StatCpu, b: StatCpu): Int {
        val dTotal = b.total - a.total
        val dIdle = b.idle - a.idle
        return if (dTotal > 0) ((1.0 - dIdle.toDouble() / dTotal) * 100.0).roundToInt().coerceIn(0, 100)
        else 0
    }

    private fun statCpu(lineas: String): Map<String, StatCpu> {
        val m = HashMap<String, StatCpu>()
        lineas.lines().forEach { li ->
            val t = li.trim()
            if (!t.startsWith("cpu")) return@forEach
            val campos = t.split(Regex("\\s+"))
            val nombre = campos[0]
            val clave = when {
                nombre == "cpu" -> "cpu"
                nombre.removePrefix("cpu").toIntOrNull() != null -> nombre
                else -> return@forEach
            }
            val v = campos.drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size < 5) return@forEach
            val total = v.take(8).sum()
            val idle = v[3] + v.getOrElse(4) { 0L }
            m[clave] = StatCpu(idle, total)
        }
        return m
    }

    private fun statBloque(sec: String?): Map<String, StatBloque> {
        val m = HashMap<String, StatBloque>()
        sec?.lines()?.forEach { li ->
            val ix = li.indexOf(": ")
            if (ix < 0) return@forEach
            val ruta = li.substring(0, ix)
            if (!ruta.startsWith("/sys/block/") || !ruta.endsWith("/stat")) return@forEach
            val nums = li.substring(ix + 2).trim().split(Regex("\\s+"))
                .mapNotNull { it.toLongOrNull() }
            if (nums.size < 7) return@forEach
            val disp = ruta.removeSuffix("/stat").substringAfterLast('/')
            m[disp] = StatBloque(nums[2], nums[6])
        }
        return m
    }

    private fun frecuencias(sec: String?): Map<Int, Long> {
        val m = HashMap<Int, Long>()
        sec?.lines()?.forEach { li ->
            val ix = li.indexOf(": ")
            if (ix < 0) return@forEach
            val ruta = li.substring(0, ix)
            val num = Regex("""/cpu(\d+)/cpufreq/""").find(ruta)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val valor = li.substring(ix + 2).trim().toLongOrNull() ?: return@forEach
            m[num] = valor
        }
        return m
    }

    private fun devfreq(sec: String?): Map<String, Map<String, String>> {
        val m = HashMap<String, HashMap<String, String>>()
        sec?.lines()?.forEach { li ->
            val ix = li.indexOf(": ")
            if (ix < 0) return@forEach
            val ruta = li.substring(0, ix)
            if (!ruta.startsWith("/sys/class/devfreq/")) return@forEach
            val disp = ruta.removePrefix("/sys/class/devfreq/").substringBeforeLast('/')
            val tipo = ruta.substringAfterLast('/')
            m.getOrPut(disp) { HashMap() }[tipo] = li.substring(ix + 2).trim()
        }
        return m
    }

    private fun termicas(sec: String?): List<Pair<String, Double>> {
        val tipos = HashMap<String, String>()
        val temps = HashMap<String, Int>()
        sec?.lines()?.forEach { li ->
            val ix = li.indexOf(": ")
            if (ix < 0) return@forEach
            val ruta = li.substring(0, ix)
            val num = Regex("""thermal_zone(\d+)/""").find(ruta)?.groupValues?.get(1) ?: return@forEach
            val valor = li.substring(ix + 2).trim()
            when {
                ruta.endsWith("/type") && valor.isNotBlank() -> tipos[num] = valor
                ruta.endsWith("/temp") -> valor.toIntOrNull()?.let { temps[num] = it }
            }
        }
        val out = ArrayList<Pair<String, Double>>()
        for ((num, tipo) in tipos) {
            val t = temps[num]
            if (t != null && t in -30000..150000) out.add(tipo to (t / 1000.0))
        }
        return out
    }

    private fun valorPorSufijo(sec: String?, sufijo: String): Long? {
        val li = sec?.lines()?.firstOrNull { l ->
            val ix = l.indexOf(": ")
            ix > 0 && l.substring(0, ix).endsWith(sufijo)
        } ?: return null
        return li.substringAfter(": ").trim().toLongOrNull()
    }

    private fun kb(sec: String?, etiqueta: String): Long? =
        sec?.lines()?.firstOrNull { it.startsWith(etiqueta) }
            ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.firstOrNull()

    private fun hardware(sec: String?): String {
        val linea = sec?.lines()?.firstOrNull { it.isNotBlank() } ?: return "sin nombre legible"
        return linea.substringAfter(':').trim().ifBlank { "sin nombre legible" }
    }

    private fun pantalla(context: Context): Triple<Int, Int, Float>? = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            val hz = try {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.refreshRate
            } catch (_: Throwable) { 60f }
            Triple(b.width(), b.height(), hz)
        } else {
            @Suppress("DEPRECATION")
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            val disp = wm.defaultDisplay
            disp.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, disp.refreshRate)
        }
    } catch (_: Throwable) { null }

    private fun tiempoEncendido(): String {
        val ms = SystemClock.elapsedRealtime()
        val d = ms / 86_400_000L
        val h = (ms % 86_400_000L) / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        return if (d > 0) "${d}d $h h $m min"
        else if (h > 0) "$h h $m min" else "$m min"
    }

    private fun barra(pct: Int): String {
        val celdas = (pct.coerceIn(0, 100) / 10.0).roundToInt().coerceIn(0, 10)
        return "[" + "#".repeat(celdas) + "-".repeat(10 - celdas) + "]"
    }

    private fun gb(kb: Long): String = String.format(Locale.US, "%.2f GB", kb / 1048576.0)
    private fun gbB(b: Long): String = String.format(Locale.US, "%.2f GB", b / 1e9)
    private fun fmt1(n: Double): String = String.format(Locale.US, "%.1f", n)
    private fun fmt0(n: Double): String = String.format(Locale.US, "%.0f", n)
}
