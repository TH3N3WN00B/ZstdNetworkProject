# ZstdNetworkProject

Compresión de paquetes **zstd (Zstandard)** para el ecosistema Minecraft, en lugar del zlib de vanilla.
Sustituye el compresor zlib integrado en la red de Minecraft por Zstandard: **mejor ratio de compresión a
menor coste de CPU** (ver [benchmark](#benchmark-zlib-vs-zstd)), compatible con clientes y servidores vanilla.

- **Versión:** beta-0.2
- **Licencia:** AGPL-3.0-or-later
- **Autor:** Rigorberto

---

## Contenido

1. [Qué ofrece](#qué-ofrece)
2. [Cómo funciona](#cómo-funciona)
3. [Plataformas y versiones soportadas](#plataformas-y-versiones-soportadas)
4. [Instalación](#instalación)
5. [Configuración](#configuración)
6. [Benchmark zlib vs zstd](#benchmark-zlib-vs-zstd)
7. [Ajustes de rendimiento (JVM)](#ajustes-de-rendimiento-jvm)
8. [Compilar desde el código fuente](#compilar-desde-el-código-fuente)
9. [Licencia](#licencia)

---

## Qué ofrece

- **Compresión zstd en el cable** en lugar de zlib: paquetes más pequeños, misma (o menor) latencia de CPU.
- **Totalmente retrocompatible**: los pares que no hablan zstd siguen usando zlib vanilla; nunca se rompe la conexión.
  - El proxy **Velocity** pregunta al cliente durante el login (login plugin message) y solo activa zstd si el cliente confirma.
  - En **servidor/cliente** el encoder solo cambia a zstd cuando el otro extremo demuestra que puede decodificarlo;
    todos los decoders detectan la cabecera del frame zstd (`28 B5 2F FD`) y aceptan ambos formatos.
- **Compatibilidad** con proxy **Krypton** y **PacketFixer** (misma técnica de sustitución de handlers).
- **Aceleración por hardware (multithread)**: los paquetes grandes (≥ 512 KiB) se comprimen con zstd multihilo
  en varios núcleos de CPU.
- **Compresión/descompresión asíncrona**: los paquetes grandes (≥ 64 KiB) se procesan fuera de los event loops
  de Netty, en un pool de workers compartido, preservando el orden de paquetes por conexión (FIFO).
- **`compress-if-beneficial`**: si comprimir no reduce el tamaño (datos incompresibles como texturas ya comprimidas),
  el paquete se envía sin comprimir. Evita que la compresión aumente el tráfico.
- **`config.yml` auto-actualizable**: las opciones nuevas se añaden solas al final del archivo (`config-version`).
- **Librería nativa** zstd-jni **empaquetada** para todas las plataformas comunes
  (Linux, Windows, macOS, FreeBSD, AIX; x86_64, aarch64, arm, ppc64, loongarch64, s390x, i386…).
  Si la plataforma no soporta la librería, el plugin degrada a zlib sin fallar.
- **Estadísticas en vivo**: `zstd-stats.log` registra cada minuto paquetes/bytes comprimidos, descomprimidos por zstd,
  y fallbacks a zlib/raw.
- **Trazas de paquetes** para depuración (opcional, desactivadas por defecto).
- **Banner de arranque** ZSTD en consola.

## Cómo funciona

Minecraft comprime los paquetes mayores que un umbral usando zlib. Este proyecto reemplaza el handler de
compresión de la pipeline de Netty por un encoder/decoder que usa **zstd-jni** (bindings oficiales de
Zstandard para JVM).

- **Formato en el cable**: se mantiene el framing de Minecraft
  (`VarInt(uncompressedSize) + payload`). `uncompressedSize = 0` significa frame crudo sin comprimir;
  en otro caso el payload es un frame zstd o zlib, elegido por la cabecera.
- **Negociación (Velocity)**: durante el login el proxy envía un mensaje `zstdnetworkproject:capable` con un byte
  de versión de protocolo. Los clientes con el mod responden el mismo byte; los vanilla no reconocen el canal y
  lo rechazan (NAK). El proxy usa zstd solo con los que confirmaron.
- **Detección (servidor/cliente)**: `ZstdCapability` recuerda por conexión si el extremo remoto ha enviado
  frames zstd. El encoder solo emite zstd cuando el remoto ya ha demostrado que lo entiende; hasta entonces usa zlib.

## Plataformas y versiones soportadas

| Minecraft | Tipo de build | Java | NeoForge | Fabric | Paper | Velocity |
|---|---|---|---|---|---|---|
| 1.21.4 | ofuscado | 21 | ✓ | ✓ | ✓ | ✓* |
| 1.21.5 | ofuscado | 21 | ✓ | ✓ | ✓ | ✓* |
| 1.21.6 | ofuscado | 21 | ✓ | ✓ | — | ✓* |
| 1.21.7 | ofuscado | 21 | ✓ | ✓ | — | ✓* |
| 1.21.8 | ofuscado | 21 | ✓ | ✓ | ✓ | ✓* |
| 1.21.9 | ofuscado | 21 | ✓ | ✓ | — | ✓* |
| 1.21.10 | ofuscado | 21 | ✓ | ✓ | ✓ | ✓* |
| 1.21.11 | ofuscado | 21 | ✓ | ✓ | ✓ | ✓* |
| 26.1 | sin ofuscar | 25 | ✓ | ✓ | — | ✓* |
| 26.1.2 | sin ofuscar | 25 | ✓ | ✓ | ✓ | ✓* |
| 26.2 | sin ofuscar | 25 | ✓ | ✓ | ✓ | ✓* |

\* **Velocity es independiente de la versión de Minecraft** y se compila contra Java 25; un solo jar sirve para
todos los servidores.

**Artefactos** (en `build/libs` o `dist/` tras compilar):

| Módulo | Archivo |
|---|---|
| NeoForge | `zstd-neoforge-beta-0.2-mc<version>.jar` |
| Fabric | `zstd-fabric-beta-0.2-mc<version>.jar` |
| Paper | `zstd-paper-beta-0.2-mc<version>.jar` |
| Velocity | `zstd-velocity-beta-0.2.jar` |

## Instalación

| Plataforma | Dónde colocar el jar | Config |
|---|---|---|
| **NeoForge** (servidor y cliente) | carpeta `mods/` | `config/zstdnetworkproject/config.yml` |
| **Fabric** (servidor y cliente) | carpeta `mods/` (requiere Fabric API y fabric-loader) | `config/zstdnetworkproject/config.yml` |
| **Paper** (servidor) | carpeta `plugins/` | `plugins/ZstdNetworkProject/config.yml` |
| **Velocity** (proxy) | carpeta `plugins/` | `plugins/zstdnetworkproject/config.yml` |

Para que la negociación funcione de extremo a extremo instala el mod/cliente **ZstdNetworkProject en el cliente**
cuando uses el proxy Velocity; los clientes vanilla simplemente siguen con zlib.

## Configuración

El primer arranque crea automáticamente `config.yml` con los valores por defecto. El archivo es auto-actualizable:
cuando una versión nueva añade opciones, se añaden al final sin tocar lo que ya tenías.

```yaml
# ZstdNetworkProject configuration
# Config files are auto-updated: new settings are appended at the bottom
# as the plugin evolves, so this file stays in sync with the latest version.
config-version: 3

# Compression level used for zstd packet compression (see https://github.com/facebook/zstd).
# The default level (3) is the level recommended by Zstandard.
# Valid range: 1 - 22. Higher levels compress better but use more CPU.
compression-level: 3

# Fast mode, equivalent to the zstd CLI '--fast=#' flag.
# When enabled, zstd uses negative (fast) compression levels, trading
# some compression ratio for much higher speed.
# Disabled by default.
fast: false

# The '#' value used by --fast when fast mode is enabled.
# Higher values are faster (and compress less). Default: 1.
fast-level: 1

# Send an in-game chat message to the player when zstd compression is enabled.
# Enabled by default.
debug-message: true

# CPU hardware acceleration: uses zstd's multithreaded mode to compress large
# packets (>= 512 KiB uncompressed) on multiple CPU cores.
# Only affects packet compression; zstd decompression is inherently single-threaded.
# Enabled by default.
hardware-acceleration: true

# How many CPU worker threads zstd may use per large packet.
# 0 = auto (half of the available processors, capped at 4).
# A larger value can speed up huge packets (e.g. chunks) at the cost of CPU usage.
hardware-acceleration-threads: 0

# Packets smaller than this (uncompressed bytes) are sent uncompressed.
# Must be at least 256, so that every packet this encoder compresses is decoded
# as zstd rather than zlib by peers.
compression-threshold: 256

# Send a packet uncompressed when compression would not actually shrink it.
# Incompressible data (e.g. already-compressed textures or chunk section data)
# can otherwise end up LARGER after zstd than before. Enabled by default.
compress-if-beneficial: true
```

### Opciones explicadas

| Opción | Rango / valores | Por defecto | Qué hace |
|---|---|---|---|
| `compression-level` | 1 – 22 | `3` | Nivel de compresión zstd. Nivel 3 es el recomendado por Zstandard: buen ratio con poco CPU. |
| `fast` | true / false | `false` | Modo rápido (niveles negativos, `--fast`). Cambia ratio por velocidad. |
| `fast-level` | 1 – 99 | `1` | Valor usado por `--fast` cuando `fast: true`. Más alto = más rápido, comprime menos. |
| `debug-message` | true / false | `true` | Mensaje en el chat del jugador al activarse zstd. |
| `hardware-acceleration` | true / false | `true` | Compresión multihilo para paquetes ≥ 512 KiB. |
| `hardware-acceleration-threads` | 0 – 64 | `0` | Workers por paquete grande. `0` = auto (mitad de CPUs, máx. 4). |
| `compression-threshold` | ≥ 256 | `256` | Paquetes menores no se comprimen. Mínimo 256 para distinguir zstd de zlib. |
| `compress-if-beneficial` | true / false | `true` | Si comprimir no encoge el paquete, se envía crudo. |

## Benchmark zlib vs zstd

> Metodología: micro-benchmark local (JDK 25, zstd-jni **1.5.7-14**) que reproduce exactamente la configuración
> del proyecto — zlib `Deflater(nivel 6)` (default vanilla), zstd nivel 3 (default del plugin) con multithread
> en paquetes ≥ 512 KiB y descompresión zstd de un solo hilo — sobre un corpus sintético reproducible
> (mezcla 70/30 de datos estilo chunk/entidad y datos incompresibles estilo textura, semilla fija).
> Los números de velocidad se refieren al coste de CPU; la latencia por paquete se deriva de ellos.

### Ratio de compresión y velocidad (dataset mixto realista)

| Tamaño del paquete | Compresor | Ratio | Compresión | Descompresión | Latencia CPU compresión | Latencia CPU descompresión |
|---|---|---|---|---|---|---|
| 64 KiB | zlib lv6 (vanilla) | 3.064 | 118 MB/s | 732 MB/s | 555 µs | 90 µs |
| 64 KiB | **zstd lv3** | 3.080 | 1458 MB/s | 5221 MB/s | **45 µs** | **13 µs** |
| 512 KiB | zlib lv6 (vanilla) | 3.108 | 88 MB/s | 1338 MB/s | 5967 µs | 392 µs |
| 512 KiB | **zstd lv3** | 3.102 | 969 MB/s | 5180 MB/s | **541 µs** | **101 µs** |
| 1 MiB | zlib lv6 (vanilla) | 3.124 | 79 MB/s | 879 MB/s | 13299 µs | 1193 µs |
| 1 MiB | **zstd lv3** | 3.102 | 553 MB/s | 4171 MB/s | **1897 µs** | **251 µs** |

### Caso extremo: datos incompresibles (1 MiB)

| Compresor | Ratio | Compresión | Descompresión |
|---|---|---|---|
| zlib lv6 | 1.000 | 43 MB/s | 2287 MB/s |
| **zstd lv3** | 1.000 | 598 MB/s | 7874 MB/s |

`compress-if-beneficial` (activado por defecto) hace que estos datos se envíen **sin comprimir**, así que en la
práctica nunca pagan el coste de comprimir lo incompresible.

### Lectura de los números

- **Al mismo ratio** (≈ 3.1), zstd nivel 3 comprime **~7–12× más rápido** y descomprime **~3–5× más rápido** que
  zlib nivel 6. En paquetes de 1 MiB, comprimir pasa de **~13 ms a ~1.9 ms** por paquete.
- **Al mismo presupuesto de CPU**, zstd alcanza un mejor ratio (por ejemplo, texto: ratio 3.28 con zstd lv3 frente
  a 3.14 con zlib lv6 usando menos CPU).
- **Latencia real medida en producción**: el coste de CPU anterior es irrelevante frente a la latencia de red.
  Probado contra dos servidores de producción en centros de datos distintos (direcciones omitidas por seguridad),
  la latencia de ida y vuelta medida fue de **~64–86 ms** (ICMP) y **~74–86 ms** (handshake TCP). La compresión
  zstd nivel 3 añade menos de 2 ms de CPU por paquete de 1 MiB, frente a los ~13 ms de zlib — y al enviar menos
  bytes por paquete, el tiempo de transferencia sobre la red también baja.

## Ajustes de rendimiento (JVM)

Propiedades de sistema / variables de entorno para entornos específicos (especialmente contenedores, donde
`availableProcessors()` puede reportar la CPU del host completo):

| Sysprop / env | Por defecto | Qué hace |
|---|---|---|
| `-Dzstdnetworkproject.workers=N` / `ZSTDNETWORKPROJECT_WORKERS` | `max(2, min(8, cpus/2))` | Tamaño del pool de workers asíncronos (1–64). |
| `-Dzstdnetworkproject.async-threshold=N` / `ZSTDNETWORKPROJECT_ASYNC_THRESHOLD` | `65536` (64 KiB, mín. 256) | Paquetes ≥ este tamaño se (des)comprimen fuera del event loop. |
| `-Dzstdnetworkproject.trace-file=<ruta>` | desactivado | Vuelca trazas de frames (cabeceras/tail hex) al archivo. Solo para depurar. |

## Compilar desde el código fuente

Requiere JDK 21 y 25 (Gradle auto-descarga los toolchains que falten).

```bash
# Todo (todas las versiones de Minecraft + Velocity), recoge los jars en dist/
./build-all.sh                # en Git Bash / WSL
.\build-all.ps1               # en PowerShell (si existe)

# Una versión concreta
./build-all.sh 1.21.4 26.2

# O directamente con Gradle (version por defecto: 26.2)
./gradlew :neoforge:build :fabric:build :paper:build :velocity:build

# Build de una versión de Minecraft específica
./gradlew :neoforge:build :fabric:build -Pminecraft_version=1.21.4 \
  -Pneoforge_version=21.4.157 -Ppaper_version=1.21.4-R0.1-SNAPSHOT \
  -Pfabric_api_version=0.119.4+1.21.4 -Pyarn_mappings=1.21.4+build.8
```

Cada versión de Minecraft tiene un grupo de versiones en `gradle-mc<version>.properties` que `build-all`
utiliza automáticamente.

## Licencia

**AGPL-3.0-or-later** — este proyecto es open source y cualquier uso, modificación o despliegue del mismo (o de
derivados) sobre una red debe publicar su código fuente bajo la misma licencia. Ver `LICENSE.txt`.
