# Guía de entornos: Linux/Docker y Windows

Detalles de optimización y diagnóstico por entorno. Lo esencial está resumido en el README; aquí va el detalle completo.

## Linux y Docker (Paper/Velocity en hostings compartidos)

- **Detección de CPUs consciente de cgroups**: el tamaño del pool de workers se calcula con
  `availableProcessors()`, que respeta la cuota de CPU del contenedor en JVMs modernas. Al arrancar,
  Paper y Velocity registran en el log lo detectado (`cpus=`, `async-workers=`, `native=`) para poder
  verificarlo sin herramientas extra.
- **Ajuste fino sin recompilar** (útil si el hosting reporta más cores de los reales o quieres limitar CPU):
  ```
  -Dzstdnetworkproject.workers=N          # workers asíncronos (default: entre 2 y 8, mitad de los cores)
  -Dzstdnetworkproject.async-threshold=N  # umbral async en bytes (default: 65536)
  ```
  equivalentes a las variables de entorno `ZSTDNETWORKPROJECT_WORKERS` / `ZSTDNETWORKPROJECT_ASYNC_THRESHOLD`.
- **Memoria acotada**: los buffers de trabajo por hilo tienen un tope de crecimiento (4 MiB); las tramas
  gigantes usan arrays transitorios que el GC recluta al momento, evitando retener decenas de MB por hilo
  en contenedores con poca RAM.
- **Inyección thread-safe**: Paper programa el reemplazo de handlers en el event loop de Netty de cada
  canal (requisito de Netty), igual que Velocity, sin bloquear el hilo principal del servidor.
- **Imágenes Alpine/musl**: los nativos de zstd-jni van compilados contra glibc. En imágenes Alpine pueden
  fallar al cargar; el plugin avisa en el log al arrancar y cae automáticamente a zlib vanilla. Para tener
  zstd en Alpine instala el paquete del sistema `java-zstd-jni` o usa una imagen basada en glibc.
- **Eclipse Temurin y otros JDKs en Docker**: las imágenes `eclipse-temurin:*-jammy/-noble` (Ubuntu, glibc)
  funcionan sin pasos extra; solo las variantes `*-alpine` (musl) necesitan lo del punto anterior.
- **Compatibilidad con allocators alternativos** (jemalloc/mimalloc/tcmalloc, p. ej. las imágenes
  [native-leak-profiling](https://github.com/Skullians/native-leak-profiling)): totalmente compatible.
  Este plugin está diseñado para no generar fugas nativas: los contextos zstd y los `Deflater` de zlib se
  crean una sola vez por hilo y se reutilizan (nunca por paquete/mensaje, que es la causa habitual de estas
  fugas), los buffers de tramas gigantes son transitorios y el consumo nativo es constante respecto al
  número de hilos, no al tráfico. Al leer perfiles jeprof, las asignaciones de `libzstd-jni` deben verse
  como estado estable por hilo; si crecieran con el tráfico sería un bug: repórtalo con el log de arranque.
- **Dimensionado de memoria en contenedores**: deja hueco en el límite para heap + memoria directa de Netty
  + nativos de zstd (contextos y workspaces). `-XX:MaxRAMPercentage` ajusta el heap al límite del contenedor;
  la memoria directa se acota con `-XX:MaxDirectMemorySize`.

## Windows (Fabric/NeoForge de escritorio)

Fabric y NeoForge se usan sobre todo en escritorio (Windows 10/11, cualquier edición: Home, Pro, Enterprise
o LTSC — para el mod todas son idénticas). Ajustes específicos ya integrados:

- **Prioridad de los workers por debajo de lo normal**: Windows es el único de los tres grandes SO que honra
  las prioridades de hilo de Java (las mapea a prioridades reales de Win32), así que el pool que comprime
  paquetes grandes cede CPU al hilo del juego/render. Resultado: menos micro-stutters en equipos de 4-8 núcleos
  sin renunciar a la compresión.
- **Nativos incluidos** para `win-amd64`, `win-x86` y `win-aarch64` (portables ARM con Windows on ARM también
  soportados). Si el DLL no pudiera cargarse, el mod cae a zlib vanilla sin romper nada.
- **Windows Defender**: extrae el `zstd-jni.dll` a `%TEMP%` en cada arranque de la JVM y Defender lo escanea;
  si notas arranques lentos, excluye la carpeta temporal de Java o añade una exclusión para
  `libzstd-jni*.dll`. En servidores dedicados Windows, excluir la carpeta del server/`.minecraft` del
  escaneo en tiempo real reduce I/O (hazlo solo si sabes lo que haces).
- **GC del cliente**: la compresión/descompresión apenas genera basura (buffers reutilizados), así que los
  pauses que sientas son del GC estándar de Minecraft; probar `-XX:+UseZGC` (o G1 con pausas cortas) suele
  ayudar más que tocar cualquier opción de este mod.
