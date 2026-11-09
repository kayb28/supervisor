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

## Los botones

0. **Panel en tiempo real** — abre el monitor con pulso: CPU por
   núcleo (uso y frecuencia de cada uno), GPU (ocupación y frecuencia,
   Adreno vía kgsl con respaldo devfreq), RAM y swap, batería
   instantánea (nivel, temperatura y miliamperios que fluyen ahora
   mismo), temperaturas por zona del kernel y una **estimación de
   salud del almacenamiento** con su fragmentación como proxy. Se
   refresca cada 2 s, se pausa con un botón (o sola al salir de la
   pantalla) y «Copiar muestra» lleva la lectura al portapapeles.
1. **¿Quién mató mi app?** — lee el registro de eventos del sistema
   (`am_kill`) y el histórico persistente `exit-info`: muertes por
   el sistema, ANR y caídas desde el último arranque.
2. **Batería** — nivel, estado, salud, temperatura, capacidad
   aprendida y % de sueño profundo, del propio `dumpsys battery`.
3. **Despertares (alarmas)** — cuenta las alarmas pendientes de
   verdad y quién las tiene puestas (`dumpsys alarm`).
4. **Memoria** — RAM total, en uso, libre y *perdida* (resumen de
   `dumpsys meminfo` + `/proc/meminfo` de respaldo).
5. **Uso de apps (últimas 24 h)** — quién tuvo la pantalla y cuántas
   veces la abrió, con el permiso de datos de uso. No necesita
   Shizuku encendido.
6. **Informe completo** — todo lo anterior, con fecha, modelo,
   estado de permisos y una instantánea del panel en vivo,
   formateado para copiarlo y llevárselo al asistente para el análisis.

## Permisos que pide (y por qué)

- **Acceso a datos de uso** (`PACKAGE_USAGE_STATS`): sin él, el sistema no
  deja ver quién usó la pantalla. No es un diálogo: es un interruptor en
  *Ajustes → Privacidad → Permisos especiales → Acceso a datos de uso*.
  La app te lleva a esa pantalla con un botón. También por ADB:
  `adb shell appops set com.kayb28.supervisor android:get_usage_stats allow`.
- **Lista completa de apps** (`QUERY_ALL_PACKAGES`): para poder nombrar
  a todas las apps en las lecturas, no solo a las visibles.
- **Lectura del registro del sistema** (`READ_LOGS`, opcional): permite
  leer `logcat` sin Shizuku. Solo se concede por ADB:
  `adb shell pm grant com.kayb28.supervisor android.permission.READ_LOGS`.
- **Shizuku**: el canal privilegiado de solo lectura (`dumpsys`, `logcat`,
  `ps`) para los extractos crudos. Con él despierto, los permisos de
  arriba son mejoras, no requisitos.

El informe completo abre con la sección «PERMISOS DEL INSTRUMENTO»:

dice qué canales están abiertos, para que sepas qué significa cada hueco.

## Cómo obtener la APK (sin compilar nada)

1. Entra a la pestaña **Actions** de este repositorio.
2. Abre la última ejecución de **Compilar APK**.
3. Descarga el artefacto **supervisor-debug-apk**, descomprime el ZIP.
4. Instala `app-debug.apk` (Android pedirá permiso para instalar apps
   desconocidas; es la APK de tu propio repo).

Nota: la 0.1.x de CI iba firmada con una clave de depuración efímera, así
que al pasar a la 0.2.0 hay que desinstalar la vieja primero. A partir
de la firma estable (cuando el CI tenga los secrets configurados), las
actualizaciones se instalan encima sin perder nada.

## Cómo estima la salud del almacenamiento (y qué no puede saber)

Sin root, el kernel no publica ni la fragmentación fina ni los contadores
UFS de vida. El panel calcula una **estimación transparente**: parte de
100, penaliza la ocupación del disco (cuanto más lleno, más fragmenta y
más desgasta) y el ritmo de escritura desde el arranque (GB/día), y si el
kernel deja leer el `health_descriptor` de UFS, usa el **dato real de vida
del chip** (tramos de 10%) por encima de la heurística. Cada penalización
se muestra junto a la puntuación: nada de cajas negras. La fragmentación
exacta en f2fs no se puede medir sin root, y el panel lo dice en vez de
inventarla.

## Requisitos

- Android 10 o superior.
- **Shizuku** instalado y despierto (botón «Arrancar») en el dispositivo,
  para los extractos crudos privilegiados y el panel en tiempo real. La
  primera vez, Supervisor pedirá su permiso con un botón. Si Shizuku
  duerme, el panel muestra lo básico (RAM, disco y batería) sin quejarse.
- **Acceso a datos de uso** concedido a Supervisor para la sección de uso
  de apps (la app te lleva a la pantalla con un botón; sin él, esa
  sección sale vacía pero el resto sigue funcionando).

## Hoja de ruta

- **Fase 1 — el estetoscopio** (0.1): diagnóstico de solo lectura.
- **0.2.0 — permisos**: la app ya pide el acceso a datos de uso y declara
  el resto de permisos de métricas; además arregla el estado de batería
  (3 = descargando), los totales de RAM, el conteo real de alarmas y el
  histórico de muertes (`exit-info`), y añade % de sueño profundo y la
  sección de uso de apps.
- **0.3.0 — tiempo real**: panel en vivo con CPU por núcleo y
  frecuencia, GPU, RAM y swap, batería instantánea, temperaturas y la
  estimación de salud del almacenamiento; el informe completo gana una
  instantánea del panel.
- **Fase 2 — la dieta**: desactivar bloatware desde una whitelist curada de la
  comunidad, con botón de deshacer por paquete. Se construye con datos de la
  semana de observación, no a ciegas.
- **Fase 3 — los perfiles**: combinaciones de ajustes (juego, lectura, noche)
  aplicadas con un toque.

## Estructura del código (para el dueño que no programa)

- `MainActivity.kt` — la pantalla y los botones. Lo que ves.
- `PanelEnVivo.kt` — el monitor en tiempo real y su bucle de refresco.
- `LiveMetrics.kt` — el muestreo en vivo y la traducción a español.
- `Diagnostics.kt` — los comandos y la traducción a español. Lo que sabe.
- `AndroidManifest.xml` — la declaración de lo que la app es y no es.
- `.github/workflows/build.yml` — el CI que compila la APK en cada push.

## Desinstalar

Se desinstala como cualquier app y no deja rastro: como nunca escribió nada,
no hay nada que revertir.

## Autoría y licencia

Proyecto dirigido por **kayb28**; construido con la asistencia de GLM (Z.ai).
Licencia MIT — ver `LICENSE`.
