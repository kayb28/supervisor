/*
 * Supervisor — Fase 1: el estetoscopio.
 * Una sola pantalla, siete botones, cero adornos. Solo lee, no toca.
 *
 * Repo: github.com/kayb28/supervisor
 */
package com.kayb28.supervisor

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
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

    private lateinit var estado: TextView
    private lateinit var reporte: TextView
    private lateinit var botonPermiso: Button

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

        val botones = listOf(
            "1. ¿Quién mató mi app?" to "muertes",
            "2. Batería" to "bateria",
            "3. Despertares (alarmas)" to "alarmas",
            "4. Memoria" to "memoria",
            "TODO: informe completo (copiárselo al asistente)" to "todo"
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
                "3) Toca cualquier botón y lee la verdad del sistema."
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
        executor.shutdown()
        super.onDestroy()
    }

    private fun actualizarEstado() {
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
        reporte.text = "Consultando al sistema… (unos segundos)"
        executor.execute {
            val texto = try {
                when (clave) {
                    "muertes" -> Diagnostics.quienMatoMisApps()
                    "bateria" -> Diagnostics.bateria()
                    "alarmas" -> Diagnostics.despertares()
                    "memoria" -> Diagnostics.memoria()
                    "todo" -> Diagnostics.informeCompleto()
                    else -> "Botón sin diagnóstico asignado."
                }
            } catch (e: Throwable) {
                "No pude consultar al sistema.\n\n" +
                    "Causa probable: ${e.message}\n\n" +
                    "¿Está Shizuku despierto? Ábrelo, presiona «Arrancar»\n" +
                    "y vuelve a intentarlo."
            }
            runOnUiThread { reporte.text = texto }
        }
    }
}
