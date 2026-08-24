# Conexión de múltiples usuarios al servidor TCP

Este documento explica qué causaba que solo un cliente pudiera conectarse a la
vez, qué se modificó para soportar varios usuarios simultáneos, y cómo fluye
la información entre servidor y clientes.

## 1. Por qué solo se conectaba un usuario

Archivo: `src/main/java/org/vinni/servidor/gui/PrincipalSrv.java`

El método `iniciarServidor()` original hacía esto dentro de un único hilo:

```java
while (true) {
    clientSocket = serverSocket.accept();     // (1) espera un cliente
    in  = new BufferedReader(...);
    out = new PrintWriter(...);
    while ((linea = in.readLine()) != null) {  // (2) se queda leyendo ESE cliente
        ...
    }
    // solo vuelve a (1) cuando el cliente (2) se desconecta
}
```

`serverSocket.accept()` y la lectura del socket (`in.readLine()`) se ejecutaban
en el **mismo hilo**. Mientras el primer cliente seguía conectado, el bucle
`while ((linea = in.readLine()) != null)` nunca terminaba, así que el
programa jamás volvía a llamar a `accept()`. Un segundo cliente que intentara
conectarse se quedaba esperando indefinidamente en la cola de conexiones del
sistema operativo, sin que el servidor lo aceptara.

Además, `clientSocket`, `in` y `out` eran **campos de instancia únicos**
compartidos por todas las conexiones. Aunque se hubiera resuelto el bloqueo,
dos clientes conectados a la vez habrían pisado esas variables entre sí
(condición de carrera), mezclando o perdiendo mensajes.

## 2. Qué se modificó

### 2.1 Servidor (`PrincipalSrv.java`)

- **Un hilo "aceptador" y un hilo por cliente.** El hilo que corre
  `serverSocket.accept()` ahora solo hace eso: aceptar y delegar. Por cada
  conexión nueva crea una instancia de la clase interna `ClienteHandler` y la
  lanza en su propio `Thread`. Así el bucle vuelve inmediatamente a
  `accept()` y puede admitir al siguiente usuario sin esperar a que el
  anterior se desconecte.

- **Clase interna `ClienteHandler implements Runnable`.** Encapsula el
  `Socket`, el `BufferedReader` y el `PrintWriter` de **un solo cliente**, en
  vez de usar campos compartidos por todo el servidor. Cada hilo lee y
  escribe solo en su propio socket, eliminando la condición de carrera.

- **Lista de clientes conectados.** `clientesConectados` es un
  `CopyOnWriteArrayList<ClienteHandler>` (seguro para ser leído/escrito por
  varios hilos a la vez) que guarda a todos los usuarios activos. Se usa
  para:
  - hacer **broadcast**: cuando un cliente envía un mensaje, el servidor se
    lo reenvía al resto de clientes conectados (`broadcast(mensaje, origen)`),
    de forma que todos ven la conversación, no solo el servidor.
  - eliminar al cliente de la lista cuando se desconecta.

- **`contadorClientes` (`AtomicInteger`).** Asigna un identificador numérico
  incremental a cada cliente que se conecta, para poder distinguirlos en el
  registro de mensajes (`Cliente 1: hola`, `Cliente 2: hola`, ...).

- **`appendMensaje(String)`.** Como ahora varios hilos pueden querer escribir
  en el `JTextArea` del servidor al mismo tiempo, esta función centraliza la
  escritura y la envuelve en `SwingUtilities.invokeLater(...)` para que
  siempre se ejecute en el hilo de eventos de Swing (EDT), evitando errores
  de concurrencia en la interfaz gráfica.

### 2.2 Cliente (`PrincipalCli.java`)

El cliente ya era compatible con múltiples usuarios "de fábrica": cada
instancia de `PrincipalCli` (cada ejecución del programa) tiene sus propios
campos `socket`, `in`, `out`, así que abrir varias ventanas/instancias ya
generaba conexiones independientes hacia el servidor. El problema estaba
enteramente del lado del servidor.

Se hizo un único ajuste:

- **Manejo de errores de conexión.** El `catch (IOException e) { }` que
  silenciaba cualquier fallo al conectar (por ejemplo, si el servidor no está
  iniciado) ahora muestra un `JOptionPane` con el mensaje de error, para que
  el usuario sepa que la conexión falló en vez de quedarse con una ventana
  que aparenta estar conectada sin estarlo.

### 2.3 Tabla comparativa: antes vs. después

| Aspecto | Versión original | Versión modificada |
|---|---|---|
| Hilos en el servidor | 1 solo hilo hace `accept()` **y** lee mensajes | 1 hilo "aceptador" + 1 hilo nuevo (`ClienteHandler`) por cada cliente |
| `Socket`/`in`/`out` | Campos únicos de la clase, compartidos por todas las conexiones | Campos privados de cada `ClienteHandler`, uno por cliente |
| Clientes simultáneos soportados | 1 (el segundo se quedaba esperando indefinidamente) | N (sin límite fijado por el código) |
| Reenvío de mensajes | El servidor solo respondía al mismo cliente que escribió | `broadcast()` reenvía el mensaje a todos los demás clientes conectados |
| Registro de quién envía qué | No distinguía clientes (`"Cliente: " + linea`) | Cada cliente tiene un id (`contadorClientes`) → `"Cliente 2: " + linea` |
| Escritura en la GUI del servidor | Directa (`mensajesTxt.append(...)`), sin problema porque solo había un hilo trabajando | A través de `appendMensaje()` con `SwingUtilities.invokeLater`, porque ahora hay varios hilos escribiendo a la vez |
| Manejo de desconexión de un cliente | No existía lógica explícita de limpieza | `desconectar()` cierra el socket, lo saca de la lista y lo informa en la GUI |
| Error al conectar el cliente | `catch (IOException e) { }` (silencioso) | Muestra un `JOptionPane` con el motivo del fallo |

## 3. Flujo de la información

### 3.1 Arranque del servidor

```
Usuario pulsa "INICIAR SERVIDOR"
        │
        ▼
bIniciarActionPerformed()
        │
        ▼
iniciarServidor()  ──► lanza Hilo "Aceptador"
                              │
                              ▼
                     serverSocket = new ServerSocket(12345)
                              │
                              ▼
                     while (true) { serverSocket.accept() }  <- se bloquea
                                                                  aquí hasta
                                                                  que llegue
                                                                  un cliente
```

### 3.2 Conexión de N clientes

```
Cliente A conecta ──┐
Cliente B conecta ──┼──► Hilo Aceptador (accept() en bucle)
Cliente C conecta ──┘
        │                     │                     │
        ▼                     ▼                     ▼
 ClienteHandler(A)     ClienteHandler(B)     ClienteHandler(C)
   (Hilo propio)          (Hilo propio)         (Hilo propio)
        │                     │                     │
        └─────────────┬───────┴──────────┬──────────┘
                       ▼                  ▼
              clientesConectados.add(handler)
        (lista compartida y segura entre hilos)
```

Cada `ClienteHandler` corre de forma totalmente independiente: bloquearse
leyendo del cliente A no afecta en nada al hilo que atiende a B o C, porque
cada uno tiene su propio `Socket`/`BufferedReader`/`PrintWriter`.

### 3.3 Envío de un mensaje (broadcast)

```
Cliente A escribe "hola" y pulsa "Enviar"
        │
        ▼
socket.getOutputStream() ──► out.println("hola")
        │  (viaja por la red / localhost)
        ▼
ClienteHandler(A).run(): in.readLine() devuelve "hola"
        │
        ├──► appendMensaje("Cliente A: hola")     → se ve en la GUI del servidor
        ├──► out.println("Mensaje recibido...")   → respuesta directa a A (ack)
        └──► broadcast("Cliente A: hola", origen=A)
                     │
                     ├──► ClienteHandler(B).enviar(...) → Cliente B lo recibe
                     └──► ClienteHandler(C).enviar(...) → Cliente C lo recibe
```

En el lado del cliente, un hilo separado (creado en `conectar()`) está todo
el tiempo bloqueado en `in.readLine()` esperando mensajes del servidor; en
cuanto llega uno, lo agrega al `JTextArea` de la ventana.

### 3.4 Desconexión

```
Cliente cierra la conexión (o falla la red)
        │
        ▼
in.readLine() devuelve null / lanza IOException
        │
        ▼
ClienteHandler.run() sale del while y ejecuta finally → desconectar()
        │
        ├──► clientesConectados.remove(this)
        ├──► socket.close()
        └──► appendMensaje("Cliente N desconectado. Usuarios conectados: X")
```

## 4. Funciones principales explicadas

### Servidor (`PrincipalSrv.java`)

- **`bIniciarActionPerformed(evt)`** — Callback del botón "INICIAR SERVIDOR".
  Solo llama a `iniciarServidor()`. Existe porque así lo generó el editor de
  formularios de NetBeans/IntelliJ (patrón típico: un método `xxxActionPerformed`
  por cada botón).

- **`iniciarServidor()`** — Lanza el **hilo aceptador**. Este hilo abre el
  `ServerSocket` en el puerto `PORT` y entra en un bucle infinito
  `while (true) { serverSocket.accept(); ... }`. Por cada conexión entrante crea
  un `ClienteHandler` con un id nuevo, lo agrega a `clientesConectados` y lo
  arranca en su propio `Thread`. Es la función que antes contenía también la
  lectura de mensajes (el bug); ahora solo hace "aceptar y delegar", por eso
  nunca se bloquea esperando a un cliente en particular.

- **`ClienteHandler.run()`** — El "loop de vida" de un cliente individual.
  Abre `in`/`out` sobre el socket de ese cliente y se queda bloqueado en
  `in.readLine()`. Por cada línea recibida: la muestra en la GUI, le responde
  un acuse de recibo solo a ese cliente, y hace `broadcast()` al resto. Cuando
  `readLine()` devuelve `null` (el cliente cerró la conexión) o lanza
  `IOException`, sale del bucle y ejecuta `desconectar()` en el `finally`.

- **`broadcast(mensaje, origen)`** — Recorre `clientesConectados` y llama a
  `enviar(mensaje)` en todos menos en el que originó el mensaje. Es lo que
  convierte al servidor en un "chat" en vez de una simple conexión 1 a 1.

- **`appendMensaje(mensaje)`** — Único punto por el que cualquier hilo escribe
  en el `JTextArea` del servidor. Envuelve la escritura en
  `SwingUtilities.invokeLater` porque Swing no es *thread-safe*: si dos
  `ClienteHandler` escribieran directamente a la vez, la GUI podría corromperse
  o lanzar excepciones.

- **`desconectar()`** — Limpieza cuando un cliente se va: lo quita de
  `clientesConectados`, cierra su `socket` y deja constancia en la GUI.

### Cliente (`PrincipalCli.java`)

- **`bConectarActionPerformed(evt)` → `conectar()`** — Abre el `Socket` hacia
  `"localhost"` y `PORT`, crea `out` (para enviar) y `in` (para recibir), y
  lanza un **hilo lector**: un bucle `while ((fromServer = in.readLine()) != null)`
  que se queda esperando mensajes del servidor todo el tiempo que dure la
  conexión, y los va agregando al `JTextArea` de la ventana. Este hilo es
  necesario porque si se leyera en el mismo hilo de la GUI, la ventana se
  congelaría esperando datos.

- **`btEnviarActionPerformed(evt)` → `enviarMensaje()`** — Toma el texto de
  `mensajeTxt`, lo manda con `out.println(...)` y limpia el campo de texto.

## 5. Cómo sería conectar a varios servidores distintos (diseño, sin implementar)

Esto **no está implementado** — es la explicación conceptual de cómo se
podría hacer, ya que la pediste sin querer que lo aplicara todavía.

### El problema de fondo

Ahora mismo `PORT` es `private final int PORT = 12345;` tanto en el servidor
como en el cliente: un valor fijo, igual en ambos lados. Para tener **varios
servidores distintos** (por ejemplo, uno en el puerto 12345 y otro en el
12346, o en máquinas distintas) y que el cliente **elija** a cuál conectarse,
hacen falta dos cambios de diseño, ninguno estructuralmente complicado:

1. **El servidor debe poder arrancar en un puerto configurable**, no fijo en
   el código.
2. **El cliente debe poder elegir host + puerto antes de conectar**, en vez
   de tenerlos hardcodeados.

### 5.1 Servidor con puerto configurable

Opciones, de más simple a más flexible:

- **Argumento de línea de comandos**: cambiar `PORT` fijo por
  `int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 12345;` en
  `main(String[] args)`. Así podrías levantar dos servidores distintos solo
  cambiando el comando:
  ```powershell
  java -cp out org.vinni.servidor.gui.PrincipalSrv 12345
  java -cp out org.vinni.servidor.gui.PrincipalSrv 12346
  ```
  Cada uno es un proceso Java independiente, con su propio `ServerSocket` y su
  propia lista `clientesConectados` — no comparten nada entre sí.

- **Campo de texto en la GUI**: agregar un `JTextField` para que quien inicia
  el servidor escriba el puerto antes de pulsar "INICIAR SERVIDOR", en vez de
  (o además de) pasarlo por consola. Útil si vas a lanzar el servidor
  siempre a mano y no por script.

Cualquiera de las dos formas te permite tener, por ejemplo, un "Servidor A"
en el puerto 12345 y un "Servidor B" en el puerto 12346, corriendo al mismo
tiempo, cada uno en su propia ventana/proceso.

### 5.2 Cliente con selección de servidor

El cliente necesita dejar de asumir `"localhost"` y `PORT` fijos. Dos formas
típicas de resolverlo:

- **Campos de texto libres**: agregar un `JTextField` para el host (IP o
  nombre) y otro para el puerto, y que `conectar()` los lea en vez de usar
  las constantes:
  ```java
  socket = new Socket(hostTxt.getText(), Integer.parseInt(puertoTxt.getText()));
  ```
  Es lo más flexible (permite conectarse a cualquier servidor sin tocar
  código), pero exige que el usuario sepa la IP/puerto de memoria.

- **Lista desplegable (`JComboBox`) de servidores conocidos**: mantener una
  lista fija o cargada de un archivo de configuración con pares
  `nombre → host:puerto` (p. ej. `"Servidor A" → 127.0.0.1:12345`,
  `"Servidor B" → 127.0.0.1:12346`), mostrarla en un `JComboBox` en la
  ventana del cliente, y que al pulsar "Conectar" se tome el host/puerto del
  elemento seleccionado. Es más amigable para el usuario final porque no
  tiene que recordar direcciones ni puertos, a costa de tener que mantener
  esa lista actualizada en el cliente.

En ambos casos, la parte de **sockets** no cambia: `new Socket(host, puerto)`
sigue funcionando igual, cada cliente sigue teniendo su propio `Socket`/`in`/
`out`, y el servidor al que apunte seguirá recibiendo la conexión por su
`ServerSocket.accept()` normalmente. Lo único que cambia es **de dónde sale
el host/puerto** (de una constante fija, a una entrada del usuario o una
lista de configuración). No haría falta ningún "servidor de servidores" ni
componente adicional: cada servidor sigue siendo completamente independiente
y el cliente simplemente decide, antes de conectar, con cuál de ellos abrir
el socket.

## 5.3 Mensajes privados (cliente → cliente) y difusión (cliente → todos), implementado

Antes, cada mensaje que un cliente enviaba se difundía siempre a todos los
demás (`broadcast`); no existía forma de dirigirlo a un único cliente. Se
agregó un mini-protocolo de texto para que el propio cliente indique, en cada
línea que envía, si el mensaje es para todos o para un destinatario puntual.

### Formato de la línea que el cliente envía

```
<destino><SEPARADOR_DESTINO><mensaje>
```

- `SEPARADOR_DESTINO` es el carácter `"|"`.
- `<destino>` es `DESTINO_TODOS` (el texto `"TODOS"`) para difundir a todos
  los demás clientes, o el **id numérico** de un cliente (el mismo id que el
  servidor asigna con `ID_ASIGNADO:`) para un mensaje privado.

Ejemplos de líneas reales que viajan por el socket:

```
TODOS|Hola a todos
3|Hola solo para el cliente 3
```

Estas dos constantes (`DESTINO_TODOS`, `SEPARADOR_DESTINO`) están definidas
igual en `PrincipalSrv` y en `PrincipalCli` — es el mismo patrón que ya se
usaba para `PREFIJO_ID_CLIENTE`: como no hay un módulo compartido entre
cliente y servidor, el "contrato" del protocolo se mantiene sincronizado a
mano entre ambos archivos.

### Qué se modificó para esto

- **`PrincipalCli.java` — GUI**: se agregó un `JTextField` (`destinoTxt`) con
  su etiqueta (`jLabelDestino`, texto "Destino (ID o vacío = TODOS):"),
  ubicado arriba del campo de mensaje. Los demás componentes (`Mensaje:`,
  el campo de texto, el botón `Enviar` y el área de mensajes) se corrieron
  30px hacia abajo para hacerle espacio, y la ventana creció de 375px a
  405px de alto.

- **`PrincipalCli.enviarMensaje()`**: ahora arma la línea con el formato de
  arriba (usa `TODOS` si el campo destino está vacío) y la envía con
  `out.println(...)`. Además, **agrega el mensaje al propio `JTextArea` de
  inmediato** (eco local), mostrando `"Yo (Cliente <id>) -> <destino>:
  <mensaje>"`, en vez de esperar a que el servidor se lo reenvíe de vuelta —
  así el remitente siempre ve, sin demora, qué mandó y a quién.

- **`PrincipalSrv.ClienteHandler.run()` / `procesarLinea(String)`**: el
  bucle que antes solo hacía `broadcast()` de cada línea ahora la pasa a
  `procesarLinea(linea)`, que:
  - separa `<destino>` de `<mensaje>` por el primer `"|"`,
  - si `<destino>` es `TODOS`, hace `broadcast()` igual que antes (etiquetando
    el mensaje como `"Cliente N (a todos): ..."`),
  - si `<destino>` es un id numérico, busca ese `ClienteHandler` con el nuevo
    método `buscarPorId(String)` y le entrega el mensaje **solo a él**
    (`"Cliente N (privado): ..."`),
  - si el id no existe (cliente desconectado o inexistente), le responde al
    remitente `"ERROR: Cliente X no encontrado o desconectado"`,
  - si la línea no trae `"|"` (compatibilidad con clientes viejos), la trata
    como si fuera para `TODOS`.

- **Lista de clientes conectados, visible para el usuario**: para que un
  cliente sepa a qué id puede escribirle en privado, ahora el servidor:
  - al conectarse un cliente nuevo, le envía (solo a él) la lista de ids ya
    conectados: `"Clientes conectados actualmente: [1, 2]"` (o `"No hay
    otros clientes conectados todavía."` si es el primero);
  - avisa a **todos los demás** clientes cuando alguien se conecta
    (`"Cliente N se ha conectado."`) o se desconecta (`"Cliente N se ha
    desconectado."`), vía `broadcast()` desde `run()` y desde `desconectar()`
    respectivamente. Antes esto solo se registraba en la GUI del servidor,
    nunca llegaba a los demás clientes.

- **Se quitó el acuse de recibo genérico** (`out.println("Mensaje recibido en
  el servidor")`) que el servidor mandaba tras cada línea: ahora que el
  propio cliente hace el eco local de lo que envió, ese aviso genérico ya no
  aportaba información y solo ensuciaba el área de mensajes.

### Ejemplo de lo que ve cada ventana

Con el Cliente 1 y el Cliente 2 conectados, si el Cliente 1 escribe `hola` en
"Destino" `2` y pulsa Enviar:

- **Ventana del Cliente 1** (remitente): `Yo (Cliente 1) -> Cliente 2: hola`
  (eco local, aparece al instante, sin ida y vuelta al servidor).
- **Ventana del Cliente 2** (destinatario): `Servidor: Cliente 1 (privado):
  hola`.
- **Ventana del Cliente 3** (si existe): no ve nada — el mensaje no le llegó,
  porque fue privado.
- **GUI del servidor**: `Cliente 1 -> Cliente 2 (privado): hola`.

Si en cambio el Cliente 1 deja "Destino" vacío (o escribe `TODOS`):

- **Ventana del Cliente 1**: `Yo (Cliente 1) -> TODOS: hola`.
- **Ventanas de todos los demás clientes conectados**: `Servidor: Cliente 1
  (a todos): hola`.
- **GUI del servidor**: `Cliente 1 (a todos): hola`.

En ambos casos, cada mensaje mostrado deja claro **quién lo envió** (el id de
cliente que aparece en el texto) y **qué se envió** (el texto tal cual se
escribió), tanto para el remitente como para el o los destinatarios.

## 5.3.1 Identificación del cliente ante sí mismo (implementado)

Cada `ClienteHandler`, apenas se conecta, le envía a **ese mismo cliente**
(no al resto) una línea especial con su propio número, antes de entrar al
bucle normal de chat:

```java
out.println(PREFIJO_ID_CLIENTE + id);   // ej: "ID_ASIGNADO:3"
```

`PREFIJO_ID_CLIENTE` (`"ID_ASIGNADO:"`) es una constante que existe **igual
en servidor y cliente**, a modo de mini-protocolo: cualquier línea que llegue
al cliente empezando con ese prefijo no es un mensaje de chat, sino la
notificación de su propio id.

En `PrincipalCli.conectar()`, el hilo lector ahora distingue ambos casos:

```java
while ((fromServer = in.readLine()) != null) {
    if (fromServer.startsWith(PREFIJO_ID_CLIENTE)) {
        idCliente = fromServer.substring(PREFIJO_ID_CLIENTE.length());
        // actualiza título de la ventana, jLabel1 y el área de mensajes
    } else {
        // mensaje normal → "Servidor: " + fromServer
    }
}
```

Al recibirlo, el cliente:
- cambia el título de la ventana a `"Cliente 3 - Cliente TCP: DFRACK"`,
- actualiza la etiqueta superior a `"CLIENTE TCP : DFRACK (Cliente 3)"`,
- y agrega `"Eres el Cliente 3"` al área de mensajes.

Como la actualización toca componentes Swing desde el hilo lector (que no es
el hilo de eventos), se hace con `SwingUtilities.invokeLater(...)`, igual que
`appendMensaje()` en el servidor.

## 6. Protocolo de comunicación: TCP sobre sockets, y por qué la conexión queda abierta

### ¿Se mantiene la conexión abierta? Sí.

El medio de comunicación es **TCP** puro, usado directamente vía
`java.net.Socket` (cliente) y `java.net.ServerSocket` (servidor) — no hay
HTTP, WebSocket, ni ningún framework de mensajería de por medio. TCP es un
protocolo **orientado a conexión**: `new Socket(host, puerto)` en el cliente
y `serverSocket.accept()` en el servidor establecen un canal bidireccional
una sola vez (el *handshake* de TCP lo hace el sistema operativo por
debajo), y ese mismo canal se reutiliza para todos los mensajes que se
intercambien mientras dure la sesión — no se abre una conexión nueva por
cada mensaje, a diferencia de, por ejemplo, HTTP sin *keep-alive*.

La razón por la que la conexión permanece abierta "todo el tiempo" es que
**ambos lados se quedan bloqueados leyendo en bucle sobre el mismo socket**:

- En el servidor, cada `ClienteHandler.run()` corre `while ((linea =
  in.readLine()) != null) { ... }` — ese hilo no hace nada más que esperar la
  siguiente línea de *ese* cliente, indefinidamente, hasta que el cliente
  cierra la conexión o falla la red (momento en que `readLine()` devuelve
  `null` o lanza `IOException`).
- En el cliente, el hilo lector lanzado desde `conectar()` hace exactamente
  lo mismo sobre las líneas que manda el servidor: `while ((fromServer =
  in.readLine()) != null) { ... }`.

Por eso un mensaje enviado por el Cliente B le llega al Cliente A "al
instante": el servidor simplemente escribe (`out.println(...)`) en el
`Socket` de A, que ya estaba abierto y cuyo hilo lector ya estaba esperando
ahí — no hay que reconectar, ni A tiene que "preguntar" (hacer *polling*) si
hay mensajes nuevos.

### ¿Qué protocolo de aplicación se usa por encima de TCP?

TCP por sí solo solo garantiza la entrega ordenada y fiable de *bytes*; no
define qué significan esos bytes. Este proyecto **no usa un protocolo de
aplicación estándar** (no es HTTP, ni FTP, ni MQTT, ni nada con nombre
propio) — define su propio mini-protocolo de texto plano, línea por línea,
en el mismo espíritu que protocolos clásicos como SMTP o IRC (comandos de
texto separados por saltos de línea sobre una conexión TCP persistente).

Las piezas del protocolo son:

1. **Delimitador de mensaje: el salto de línea.** El servidor y el cliente
   escriben con `new PrintWriter(socket.getOutputStream(), true)` — el
   segundo parámetro `true` activa *autoFlush*, así que cada
   `out.println(...)` se envía de inmediato, terminado en `\n`. Del otro
   lado, `BufferedReader.readLine()` bloquea hasta reunir una línea completa.
   No se manda ningún encabezado de longitud ni delimitador binario: el
   límite de cada "mensaje" del protocolo es, literalmente, el fin de línea.
2. **`ID_ASIGNADO:<id>`** (servidor → cliente, una sola vez, justo tras
   `accept()`) — le informa al cliente su propio número. Ver sección 5.3.1.
3. **`<destino>|<mensaje>`** (cliente → servidor, en cada línea que el
   usuario envía) — le indica al servidor si el mensaje es para `TODOS` o
   para el id de un cliente puntual. Ver sección 5.3.
4. **Texto plano sin prefijo** (servidor → cliente) — cualquier otra línea es
   contenido para mostrar tal cual, con distintos orígenes posibles:
   mensajes reenviados (`"Cliente 3 (a todos): ..."`, `"Cliente 3
   (privado): ..."`), errores (`"ERROR: Cliente 9 no encontrado o
   desconectado"`), o avisos de conexión/desconexión
   (`"Cliente 3 se ha conectado."`). El cliente no distingue estos casos
   entre sí: simplemente les antepone `"Servidor: "` y los muestra en el
   área de mensajes.

No hay cifrado, autenticación, ni control de versión del protocolo — es
texto plano sobre TCP, pensado para uso local/confianza total entre las
partes (por ejemplo, no serviría tal cual para exponerlo a Internet sin
agregar TLS y autenticación).

## 7. Cómo iniciar el proyecto

### Opción A — sin Maven, con `javac`/`java` directos (PowerShell)

Compilar (genera las clases en `out\`):

```powershell
javac -d out -encoding UTF-8 src\main\java\org\vinni\App.java src\main\java\org\vinni\servidor\gui\PrincipalSrv.java src\main\java\org\vinni\cliente\gui\PrincipalCli.java
```

Iniciar el servidor (una ventana/proceso):

```powershell
java -cp out org.vinni.servidor.gui.PrincipalSrv
```

Iniciar cada cliente (repetir este comando en una terminal nueva por cada
cliente que quieras abrir):

```powershell
java -cp out org.vinni.cliente.gui.PrincipalCli
```

### Opción B — con Maven (si `mvn` está instalado y en el `PATH`)

```powershell
mvn compile
java -cp target\classes org.vinni.servidor.gui.PrincipalSrv
java -cp target\classes org.vinni.cliente.gui.PrincipalCli
```

> Nota: el `pom.xml` actual solo declara `packaging=jar` sin un
> *main-class* ni el `maven-shade-plugin`/`exec-plugin` configurados, así que
> `mvn package` no produce un `.jar` ejecutable con `java -jar`; por eso
> arriba se ejecuta con `java -cp target\classes <clase>` en vez de
> `java -jar`.

### Orden recomendado para probar

1. Ejecutar `PrincipalSrv` y pulsar **INICIAR SERVIDOR**.
2. Ejecutar `PrincipalCli` dos o más veces (varias terminales/ventanas, o
   desde equipos distintos apuntando a la IP del servidor si no es
   `localhost`) y pulsar **CONECTAR CON SERVIDOR** en cada una.
3. Cada ventana de cliente debe mostrar de inmediato `"Eres el Cliente N"` y
   `"Clientes conectados actualmente: [...]"` (o el aviso de que no hay
   otros aún) en su área de mensajes, con un número de cliente distinto por
   ventana.
4. **Probar broadcast**: dejar el campo "Destino" vacío (o escribir `TODOS`)
   en un cliente y enviar un mensaje — debe aparecer como `"Yo (Cliente N)
   -> TODOS: ..."` en la ventana que lo envió, y como `"Servidor: Cliente N
   (a todos): ..."` en todas las demás ventanas conectadas, además de en la
   GUI del servidor.
5. **Probar mensaje privado**: escribir el id de otro cliente conectado en
   "Destino" (por ejemplo `2`) y enviar — debe aparecer como `"Yo (Cliente N)
   -> Cliente 2: ..."` solo en la ventana que lo envió, y como `"Servidor:
   Cliente N (privado): ..."` únicamente en la ventana del Cliente 2; el
   resto de clientes no debe ver nada.
6. **Probar destino inexistente**: escribir un id que no corresponda a
   ningún cliente conectado (por ejemplo `99`) — quien envió debe recibir
   `"Servidor: ERROR: Cliente 99 no encontrado o desconectado"`.
7. Cerrar uno de los clientes: el servidor debe registrar su desconexión y
   avisarle al resto de clientes (`"Cliente N se ha desconectado."`), sin
   afectar a los demás.

## 8. Posibles mejoras futuras (no implementadas)

- Cerrar `serverSocket` y todos los sockets de `clientesConectados` al cerrar
  la ventana del servidor (actualmente se cierran al terminar el proceso).
- Puerto configurable en servidor y selección de host/puerto en cliente (ver
  sección 5), para poder elegir a qué servidor conectarse.
- Actualizar en vivo la lista de conectados que ve cada cliente (hoy solo se
  envía una vez, al conectarse; si se conecta o desconecta alguien más
  después, el aviso llega como texto suelto pero no se ofrece una lista
  reconsultable en la GUI).
- Configurar `maven-shade-plugin` (o equivalente) en el `pom.xml` para poder
  generar un `.jar` ejecutable con `mvn package` + `java -jar`.
