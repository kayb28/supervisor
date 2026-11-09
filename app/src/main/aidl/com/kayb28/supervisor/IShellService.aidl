// Contrato AIDL entre la app y el pequeño servicio que corre del lado
// de Shizuku con privilegios de shell. Solo un método: ejecutar y leer.
package com.kayb28.supervisor;

interface IShellService {
    String exec(String cmd);
}
