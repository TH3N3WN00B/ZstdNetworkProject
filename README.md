# ZstdNetworkProject

Compresión de paquetes **zstd (Zstandard)** para Minecraft en lugar del zlib de vanilla:
**mismo ratio con ~7–12× menos CPU**, retrocompatible con clientes y servidores vanilla.

**Versión** beta-1.0 · **Licencia** AGPL-3.0-or-later · **Autores** Rigorberto & Bick Pickle (OpenCode)

## Qué ofrece

- **zstd en el hilo** en vez de zlib; los pares sin el mod siguen en zlib y nunca se rompe nada.
- **Negociación segura**: zstd solo se activa cuando ambos extremos lo demuestran (Velocity y Paper preguntan
  durante el login/play; servidor/cliente detectan los frames zstd del otro).
- **Compatible** con Krypton, PacketFixer y forks custom. Tolera tramas malformadas de proxies no vanilla sin desconectarse.
- **Asíncrono y multihilo**: paquetes grandes fuera de los event loops, con orden FIFO garantizado;
  workers con prioridad reducida para no robar CPU al juego (relevante en Windows).
- **`compress-if-beneficial`**: datos incompresibles se envían crudos en lugar de crecer.
- **Nativos empaquetados** para todas las plataformas comunes (Linux/Windows/macOS/FreeBSD/AIX,
  x86_64/aarch64/arm…); si no hay nativo, degrada a zlib sin fallar.
- **Estadísticas**: `zstd-stats.log` por minuto + overlay opcional en F3+3 (`debug-overlay`).

## Cómo funciona

Minecraft comprime con zlib los paquetes que superan un umbral. Este proyecto sustituye ese handler de Netty
por uno basado en zstd-jni manteniendo el mismo framing (`VarInt(uncompressedSize) + payload`); el decoder
detecta la cabecera y acepta zstd o zlib indistintamente.

## Compatibilidad

| Minecraft | Java | NeoForge | Fabric | Paper | Velocity |
|---|---|---|---|---|---|
| 1.21.4 – 1.21.11 (ofuscado) | 21 | ✓ | ✓ | ✓* | ✓ |
| 26.1 / 26.1.2 / 26.2 (sin ofuscar) | 25 | ✓ | ✓ | ✓* | ✓ |

\* Paper solo en las versiones con build disponible: 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1.2 y 26.2.

Artefactos: `zstd-neoforge-beta-1.0-mc<version>.jar`, `zstd-fabric-beta-1.0-mc<version>.jar`,
`zstd-paper-beta-1.0-mc<version>.jar`, `zstd-velocity-beta-1.0.jar` (Velocity es independiente de la versión
de MC). En `dist/` tras compilar.

## Instalación

| Plataforma | Dónde va el jar | Config |
|---|---|---|
| **NeoForge/Fabric** (servidor y cliente) | `mods/` (Fabric requiere Fabric API) | `config/zstdnetworkproject/config.yml` |
| **Paper** (servidor) | `plugins/` | `plugins/ZstdNetworkProject/config.yml` |
| **Velocity** (proxy) | `plugins/` | `plugins/zstdnetworkproject/config.yml` |

Con Velocity instala también el mod en el cliente para que negocie zstd; los vanilla siguen en zlib.

> **Limitación conocida (beta-1.0)**: detrás de un proxy Velocity, el **cliente Fabric** responde a la
> consulta de capacidad de zstd durante el login y obtiene zstd; el **cliente NeoForge** todavía no responde
> esa consulta (NeoForge no expone API de red en fase de login), así que los jugadores NeoForge conectados
> *a través del proxy* usan zlib vanilla. El servidor dedicado NeoForge (sin proxy) sí comprime con zstd.
> Se abordará en una sesión dedicada.

## Configuración

El primer arranque genera `config.yml` comentado y auto-actualizable (las opciones nuevas se añaden solas).

| Opción | Default | Qué hace |
|---|---|---|
| `compression-level` | `3` | Nivel zstd (1–22). El recomendado. |
| `fast` / `fast-level` | `false` / `1` | Modo `--fast`: más velocidad, menos ratio. |
| `hardware-acceleration` / `-threads` | `true` / `0` | Multihilo para paquetes ≥ 512 KiB. `0` = auto. |
| `compression-threshold` | `256` | No comprimir paquetes menores. |
| `compress-if-beneficial` | `true` | Si no encoge, se envía crudo. |
| `debug-message` | `true` | Mensaje en chat al activarse zstd. |
| `debug-overlay` | `false` | Stats zstd en la vista de ancho de banda (F3+3), solo cliente. |
| `disabled-servers` | *(vacío)* | Servidores donde el mod queda pasivo (subcadenas separadas por comas). |
| `auto-disable-mods` | *(vacío)* | Mods cuya presencia desactiva este mod automáticamente (subcadenas separadas por comas). |
| `hex-dump` | `false` | Volcado hex de todas las tramas a `zstd-hexdump.log` (solo diagnóstico). |

## Rendimiento

Al mismo ratio (~3.1×), zstd nivel 3 comprime **~7–12× más rápido** y descomprime **~3–5× más rápido**
que zlib nivel 6 (un paquete de 1 MiB pasa de ~13 ms a ~1.9 ms de CPU). Datos completos, metodología y
tuning por JVM: [docs/benchmark.md](docs/benchmark.md).

## Entornos

- **Linux/Docker**: detección de CPUs consciente de cgroups, memoria acotada, ajuste fino por
  sysprops/env vars, compatible con Alpine (con aviso), Temurin e imágenes jemalloc/mimalloc.
- **Windows** (10/11, Home/Pro/Enterprise/LTSC): workers ceden CPU al juego (prioridad reducida),
  nativos amd64/x86/aarch64 incluidos.

Detalles y diagnóstico: [docs/entornos.md](docs/entornos.md).

## Compilar desde el código fuente

Requiere JDK 21 y 25 (Gradle descarga los toolchains que falten).

```bash
./build-all.sh                     # todo → dist/
./build-all.sh 1.21.4 26.2         # versiones concretas
./gradlew :neoforge:build :fabric:build :paper:build :velocity:build
```

Cada versión de MC tiene su grupo en `gradle-mc<version>.properties`.

## Licencia

**AGPL-3.0-or-later**: cualquier uso, modificación o despliegue en red debe publicar el código fuente bajo
la misma licencia. Ver `LICENSE.txt`.
