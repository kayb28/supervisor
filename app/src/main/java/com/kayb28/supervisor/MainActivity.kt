/*
 * Supervisor — Fase 1: el estetoscopio.
 * Una sola pantalla, ocho botones, cero adornos. Solo lee, no toca.
 *
 * El canal con el sistema es el UserService oficial de Shizuku:
 * Shizuku carga ShellServiceImpl (que vive en esta misma APK) y lo
 * corre con privilegios de shell. La app solo recoge lecturas.
 *
 * Repo: github.com/kayb28/supervisor
 */
package com.kayb28.supervisor

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val CODIGO_PERMISO = 4200
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** El servicio del lado Shizuku; nulo mientras no haya conexión. */
    @Volatile
    private var shell: IShellService? = null

    /** Diagnóstico pendiente mientras el servicio se conecta. */
    private var pendiente: String? = null

    private lateinit var estado: TextView
    private lateinit var reporte: TextView
    private lateinit var botonPermiso: Button
    private lateinit var estadoUso: TextView
    private lateinit var botonUso: Button
    private lateinit var estadoLogs: TextView

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, ShellServiceImpl::class.java.name)
        )
            .processNameSuffix("shell")
            .version(1)
            .daemon(false)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shell = IShellService.Stub.asInterface(binder)
            runOnUiThread {
                val clave = pendiente
                pendiente = null
                if (clave != null) ejecutar(clave) else actualizarEstado()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
        }
    }

    private val permisoListener: Shizuku.OnRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            runOnUiThread { actualizarEstado() }
        }

    private fun dp(n: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, n, resources.displayMetrics
        ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(24f), dp(16f), dp(16f))
        }

        raiz.addView(TextView(this).apply {
            text = "Supervisor"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        raiz.addView(TextView(this).apply {
            text = "Fase 1 — el estetoscopio. Solo lee el sistema;\nno toca, no cambia, no rompe nada."
            textSize = 13f
            setPadding(0, dp(4f), 0, dp(8f))
        })

        estado = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8f), 0, dp(8f))
        }
        raiz.addView(estado)

        botonPermiso = Button(this).apply {
            text = "Conceder permiso a Supervisor"
            setAllCaps(false)
            setOnClickListener {
                try {
                    Shizuku.requestPermission(CODIGO_PERMISO)
                } catch (_: Throwable) {
                    // Shizuku se durmió a mitad de camino; el estado lo refleja.
                }
            }
        }
        raiz.addView(botonPermiso)

        // ── Permisos de métricas ────────────────────────────────
        // «Acceso a datos de uso» es un permiso especial: no hay
        // diálogo, hay un interruptor en Ajustes. La app solo puede
        // llevarte hasta la pantalla correcta; el interruptor lo
        // mueve el dueño.
        estadoUso = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8f), 0, dp(2f))
        }
        raiz.addView(estadoUso)

        botonUso = Button(this).apply {
            text = "Otorgar «Acceso a datos de uso»"
            setAllCaps(false)
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Throwable) {
                    // Alguna capa no tiene esta pantalla; el estado lo refleja.
                }
            }
        }
        raiz.addView(botonUso)

        estadoLogs = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8f), 0, dp(8f))
        }
        raiz.addView(estadoLogs)

        val botones = listOf(
            "0. Panel en tiempo real (CPU · GPU · RAM · disco)" to "panel",
            "1. ¿Quién mató mi app?" to "muertes",
            "2. Batería" to "bateria",
            "3. Despertares (alarmas)" to "alarmas",
            "4. Memoria" to "memoria",
            "5. Uso de apps (últimas 24 h)" to "uso",
            "6. Informe completo (copiárselo al asistente)" to "todo"
        )
        botones.forEach { (texto, clave) ->
            raiz.addView(Button(this).apply {
                this.text = texto
                setAllCaps(false)
                setOnClickListener { ejecutar(clave) }
            })
        }

        val scroll = ScrollView(this)
        reporte = TextView(this).apply {
            setTypeface(Typeface.MONOSPACE)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(12f), 0, dp(24f))
            text = "Para empezar:\n\n" +
                "1) Abre la app Shizuku y presiona «Arrancar».\n" +
                "2) Vuelve aquí; el estado de arriba debe decirlo todo.\n" +
                "3) Otorga «Acceso a datos de uso» (botón de arriba):\n" +
                "   sin él, la sección 5 sale vacía.\n" +
                "4) Toca cualquier botón y lee la verdad del sistema."
        }
        scroll.addView(reporte)
        raiz.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(raiz)
        Shizuku.addRequestPermissionResultListener(permisoListener)
    }

    override fun onResume() {
        super.onResume()
        actualizarEstado()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permisoListener)
        shell = null
        executor.shutdown()
        super.onDestroy()
    }

    private fun actualizarEstado() {
        // Permisos de métricas: se muestran aunque Shizuku duerma,
        // porque el de datos de uso funciona igual sin él.
        estadoUso.text = if (Diagnostics.tieneAccesoUso(this)) {
            "● Acceso a datos de uso: concedido"
        } else {
            "● Acceso a datos de uso: FALTA — toca el botón de abajo\n" +
                "  (Ajustes → Privacidad → Permisos especiales)"
        }
        estadoLogs.text = if (Diagnostics.tieneReadLogs(this)) {
            "● Lectura del registro del sistema: concedida"
        } else {
            "● Lectura del registro (READ_LOGS): falta — opcional, vía ADB:\n" +
                "  adb shell pm grant com.kayb28.supervisor android.permission.READ_LOGS"
        }

        val vivo = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) { false }

        if (!vivo) {
            estado.text = "● Shizuku está dormido. Ábrelo y presiona «Arrancar»."
            return
        }

        val concedido = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }

        estado.text = if (concedido) {
            "● Shizuku despierto y permiso concedido. Todo listo."
        } else {
            "● Shizuku corre, pero Supervisor todavía no tiene permiso."
        }
    }

    private fun ejecutar(clave: String) {
        // El panel en vivo es otra pantalla: se abre y se muestrea solo.
        if (clave == "panel") {
            startActivity(Intent(this, PanelEnVivo::class.java))
            return
        }

        // La sección de uso no necesita Shizuku: usa el permiso de
        // datos de uso de la propia app. Funciona hasta dormido.
        if (clave == "uso") {
            reporte.text = "Consultando el uso de apps…"
            executor.execute {
                val texto = try {
                    Diagnostics.usoDeApps(this, shell)
                } catch (e: Throwable) {
                    "No pude consultar el uso.\n\nCausa: ${e.message}"
                }
                runOnUiThread { reporte.text = texto }
            }
            return
        }

        val svc = shell
        if (svc == null) {
            pendiente = clave
            reporte.text = "Conectando con el sistema…"
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (e: Throwable) {
                pendiente = null
                reporte.text = "No pude conectar con Shizuku.\n\n" +
                    "Causa: ${e.message}\n\n" +
                    "¿Está Shizuku despierto y el permiso concedido?"
            }
            return
        }

        reporte.text = "Consultando al sistema… (unos segundos)"
        executor.execute {
            val texto = try {
                when (clave) {
                    "muertes" -> Diagnostics.quienMatoMisApps(svc)
                    "bateria" -> Diagnostics.bateria(svc)
                    "alarmas" -> Diagnostics.despertares(svc)
                    "memoria" -> Diagnostics.memoria(svc)
                    "todo" -> Diagnostics.informeCompleto(this, svc)
                    else -> "Botón sin diagnóstico asignado."
                }
            } catch (e: Throwable) {
                "No pude consultar al sistema.\n\n" +
                    "Causa: ${e.message}\n\n" +
                    "Shizuku puede haberse dormido; despiértalo y reintenta."
            }
            runOnUiThread { reporte.text = texto }
        }
    }
}
