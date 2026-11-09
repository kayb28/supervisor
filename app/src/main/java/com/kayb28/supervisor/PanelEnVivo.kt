/*
 * PanelEnVivo — el monitor con pulso.
 *
 * Muestra el panel de LiveMetrics refrescado cada 2 segundos mientras
 * la pantalla está abierta, con dos controles: pausar (congela la
 * última muestra) y copiar (guarda la muestra en el portapapeles
 * para llevársela al asistente). Se pausa sola al salir de pantalla:
 * un instrumento que solo lee no debe gastar batería mirando.
 *
 * El canal es el mismo UserService de Shizuku que usa MainActivity:
 * si Shizuku duerme, el panel degrada a lo básico y va reintengando
 * conexión cada pocos segundos por su cuenta.
 */
package com.kayb28.supervisor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PanelEnVivo : Activity() {

    companion object {
        private const val INTERVALO_MS = LiveMetrics.REFRESCO_MS
        private const val REINTENTO_MS = 4000L
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** Generación del bucle: cada onResume crea una nueva; los bucles viejos se mueren solos. */
    private val generacion = AtomicInteger(0)

    /** El servicio del lado Shizuku; nulo si no hay conexión. */
    @Volatile
    private var shell: IShellService? = null

    @Volatile
    private var corriendo = false

    @Volatile
    private var pausado = false

    @Volatile
    private var ultimaMuestra = ""

    private lateinit var vista: TextView
    private lateinit var botonPausa: Button

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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
        }
    }

    private fun dp(n: Float): Int =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, n, resources.displayMetrics
        ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(24f), dp(16f), dp(16f))
        }

        raiz.addView(TextView(this).apply {
            text = "Panel en tiempo real"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        raiz.addView(TextView(this).apply {
            text = "CPU, GPU, RAM, disco y temperaturas, muestreados\n" +
                "en vivo vía Shizuku. Se pausa sola al salir."
            textSize = 13f
            setPadding(0, dp(4f), 0, dp(8f))
        })

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        botonPausa = Button(this).apply {
            text = "Pausar"
            setAllCaps(false)
            setOnClickListener {
                pausado = !pausado
                text = if (pausado) "Reanudar" else "Pausar"
            }
        }
        fila.addView(
            botonPausa,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val botonCopiar = Button(this).apply {
            text = "Copiar muestra"
            setAllCaps(false)
            setOnClickListener {
                if (ultimaMuestra.isBlank()) {
                    Toast.makeText(this@PanelEnVivo, "Aún no hay ninguna muestra", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("supervisor", ultimaMuestra))
                Toast.makeText(this@PanelEnVivo, "Muestra copiada al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }
        fila.addView(
            botonCopiar,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        raiz.addView(fila)

        val scroll = ScrollView(this)
        vista = TextView(this).apply {
            setTypeface(Typeface.MONOSPACE)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(12f), 0, dp(24f))
            text = "Conectando con el sistema…"
        }
        scroll.addView(vista)
        raiz.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        corriendo = true
        pausado = false
        botonPausa.text = "Pausar"
        intentarEnlazar()
        val miGeneracion = generacion.incrementAndGet()
        executor.execute { bucle(miGeneracion) }
    }

    override fun onPause() {
        corriendo = false
        pausado = false
        desenlazar()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun intentarEnlazar() {
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (_: Throwable) {
            // Shizuku dormido; el bucle reintenta cada pocos segundos.
        }
    }

    private fun desenlazar() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Throwable) {
            // Ya estaba suelto; nada que hacer.
        }
        shell = null
    }

    private fun bucle(mi: Int) {
        var ultimoReintento = 0L
        while (corriendo && generacion.get() == mi) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            if (!pausado) {
                val svc = shell
                if (svc != null) {
                    try {
                        val texto = LiveMetrics.panel(this@PanelEnVivo, svc)
                        ultimaMuestra = texto
                        runOnUiThread { if (corriendo && !pausado) vista.text = texto }
                    } catch (e: Throwable) {
                        // El canal murió a mitad de muestreo: a reconectar.
                        shell = null
                        runOnUiThread {
                            if (corriendo && !pausado) {
                                vista.text = LiveMetrics.panelBasico(
                                    this@PanelEnVivo,
                                    "Conexión perdida con Shizuku (${e.message})."
                                )
                            }
                        }
                    }
                } else {
                    val ahora = android.os.SystemClock.elapsedRealtime()
                    if (ahora - ultimoReintento > REINTENTO_MS) {
                        ultimoReintento = ahora
                        intentarEnlazar()
                    }
                    runOnUiThread {
                        if (corriendo && !pausado) {
                            vista.text = LiveMetrics.panelBasico(
                                this@PanelEnVivo,
                                "Shizuku está dormido: sin él solo veo lo básico."
                            )
                        }
                    }
                }
            }
            val resto = INTERVALO_MS - (android.os.SystemClock.elapsedRealtime() - t0)
            if (resto > 0) {
                try {
                    Thread.sleep(resto)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }
}
