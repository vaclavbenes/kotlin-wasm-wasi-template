//import kotlinx.io.buffered
//import kotlinx.io.files.Path
//import kotlinx.io.files.SystemFileSystem
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

fun main() {
    println("Hello from Kotlin via WASI")
    println("Main function")
    println("Current 'realtime' timestamp is: ${wasiRealTime()}")
    println("Current 'monotonic' timestamp is: ${wasiMonotonicTime()}")

//    SystemFileSystem.sink(Path("example.txt")).buffered().write("Hello from Kotlin via WASI".encodeToByteArray())

}

@WasmImport("wasi_snapshot_preview1", "clock_time_get")
private external fun wasiRawClockTimeGet(clockId: Int, precision: Long, resultPtr: Int): Int

private const val REALTIME = 0
private const val MONOTONIC = 1

@OptIn(UnsafeWasmMemoryApi::class)
fun wasiGetTime(clockId: Int): Long = withScopedMemoryAllocator { allocator ->
    val rp0 = allocator.allocate(8)
    val ret = wasiRawClockTimeGet(
        clockId = clockId,
        precision = 1,
        resultPtr = rp0.address.toInt()
    )
    check(ret == 0) {
        "Invalid WASI return code $ret"
    }
    (Pointer(rp0.address.toInt().toUInt())).loadLong()
}

fun wasiRealTime(): Long = wasiGetTime(REALTIME)

fun wasiMonotonicTime(): Long = wasiGetTime(MONOTONIC)

// We need it to run WasmEdge with the _initialize function
@WasmExport
fun dummy() {
    println("Exported dummy function")
}
