# Supervisor

**Estetoscopio y dieta para HyperOS.** Una app mínima que audita el sistema de tu
tablet o teléfono y responde, en español claro, las preguntas que HyperOS no
quiere contestar: quién mata tus apps de fondo, quién despierta el equipo
dormido y cuánta memoria se desperdicia sola.

## Filosofía: solo lee, no toca

- No modifica **nada** del sistema. No cambia ajustes, no mata procesos, no
  desactiva paquetes. Sus comandos son de lectura (`logcat`, `dumpsys`, `ps`).
- Nada de root: usa **Shizuku** en modo shell, el mismo canal que ya usan las
  herramientas de la comunidad.
- **Por qué no se cae:** cada botón ejecuta comandos de solo lectura y muestra
  la salida cruda junto al resumen. Si algo falla, la app lo dice y se sigue
  de pie. No hay nada que romper porque no hay nada que escribir.

## Los cinco botones

1. **¿Quién mató mi app?** — lee el registro de eventos del sistema
   (`am_kill`) y cuenta qué apps han sido asesinadas desde el último reinicio.
2. **Batería** — nivel, salud y temperatura, del propio `dumpsys battery`.
3. **Despertares (alarmas)** — quién tiene alarmas puestas y dispara
   despertares del dispositivo dormido (`dumpsys alarm`).
4. **Memoria** — RAM total, en uso, libre y *perdida* (`dumpsys meminfo`).
5. **TODO: informe completo** — los cuatro juntos, con fecha y modelo,
   formateado para copiarlo y llevárselo al asistente para el análisis.

## Cómo obtener la APK (sin compilar nada)

1. Entra a la pestaña **Actions** de este repositorio.
2. Abre la última ejecución de **Compilar APK**.
3. Descarga el artefacto **supervisor-debug-apk**, descomprime el ZIP.
4. Instala `app-debug.apk` (Android pedirá permiso para instalar apps
   desconocidas; es la APK de tu propio repo).

## Requisitos

- Android 10 o superior.
- **Shizuku** instalado y despierto (botón «Arrancar») en el dispositivo.
  La primera vez, Supervisor pedirá su permiso con un botón.

## Hoja de ruta

- **Fase 1 — el estetoscopio** (esta versión): diagnóstico de solo lectura.
- **Fase 2 — la dieta**: desactivar bloatware desde una whitelist curada de la
  comunidad, con botón de deshacer por paquete. Se construye con datos de la
  semana de observación, no a ciegas.
- **Fase 3 — los perfiles**: combinaciones de ajustes (juego, lectura, noche)
  aplicadas con un toque.

## Estructura del código (para el dueño que no programa)

- `MainActivity.kt` — la pantalla y los botones. Lo que ves.
- `Diagnostics.kt` — los comandos y la traducción a español. Lo que sabe.
- `AndroidManifest.xml` — la declaración de lo que la app es y no es.
- `.github/workflows/build.yml` — el CI que compila la APK en cada push.

## Desinstalar

Se desinstala como cualquier app y no deja rastro: como nunca escribió nada,
no hay nada que revertir.

## Autoría y licencia

Proyecto dirigido por **kayb28**; construido con la asistencia de GLM (Z.ai).
Licencia MIT — ver `LICENSE`.
