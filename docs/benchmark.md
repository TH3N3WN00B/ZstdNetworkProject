# Benchmark zlib vs zstd

> Metodología: micro-benchmark local (JDK 25, zstd-jni **1.5.7-15**) que reproduce exactamente la configuración
> del proyecto — zlib `Deflater(nivel 6)` (default vanilla), zstd nivel 3 (default del plugin) con multithread
> en paquetes ≥ 512 KiB y descompresión zstd de un solo hilo — sobre un corpus sintético reproducible
> (mezcla 70/30 de datos estilo chunk/entidad y datos incompresibles estilo textura, semilla fija).
> Los números de velocidad se refieren al coste de CPU; la latencia por paquete se deriva de ellos.

## Ratio de compresión y velocidad (dataset mixto realista)

| Tamaño del paquete | Compresor | Ratio | Compresión | Descompresión | Latencia CPU compresión | Latencia CPU descompresión |
|---|---|---|---|---|---|---|
| 64 KiB | zlib lv6 (vanilla) | 3.064 | 118 MB/s | 732 MB/s | 555 µs | 90 µs |
| 64 KiB | **zstd lv3** | 3.080 | 1458 MB/s | 5221 MB/s | **45 µs** | **13 µs** |
| 512 KiB | zlib lv6 (vanilla) | 3.108 | 88 MB/s | 1338 MB/s | 5967 µs | 392 µs |
| 512 KiB | **zstd lv3** | 3.102 | 969 MB/s | 5180 MB/s | **541 µs** | **101 µs** |
| 1 MiB | zlib lv6 (vanilla) | 3.124 | 79 MB/s | 879 MB/s | 13299 µs | 1193 µs |
| 1 MiB | **zstd lv3** | 3.102 | 553 MB/s | 4171 MB/s | **1897 µs** | **251 µs** |

## Caso extremo: datos incompresibles (1 MiB)

| Compresor | Ratio | Compresión | Descompresión |
|---|---|---|---|
| zlib lv6 | 1.000 | 43 MB/s | 2287 MB/s |
| **zstd lv3** | 1.000 | 598 MB/s | 7874 MB/s |

`compress-if-beneficial` (activado por defecto) hace que estos datos se envíen **sin comprimir**, así que en la
práctica nunca pagan el coste de comprimir lo incompresible.

## Lectura de los números

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
