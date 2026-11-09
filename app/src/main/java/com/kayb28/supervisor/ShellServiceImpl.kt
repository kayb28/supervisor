/*
 * ShellServiceImpl — corre DENTRO del proceso de Shizuku, con los
 * privilegios de shell de solo lectura que este concede. Es el patrón
 * oficial "UserService" de Shizuku: la app declara el contrato
 * (IShellService.aidl) y Shizuku carga esta clase desde la propia APK.
 */
package com.kayb28.supervisor

class ShellServiceImpl : IShellService.Stub() {

    override fun exec(cmd: String): String = try {
        val proceso = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val salida = proceso.inputStream.bufferedReader().readText()
        proceso.waitFor()
        salida
    } catch (e: Throwable) {
        "ERROR: ${e.message}"
    }
}
