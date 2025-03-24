import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import wasi.Wasi.println


// We need it to run WasmEdge with the _initialize function
@WasmExport
fun dummy() {
    println("Exported dummy function")
}


@OptIn(UnsafeWasmMemoryApi::class)
fun main() {
    println("Hello from Kotlin via WASI")

    // Fix in Rust
    // createFile("hello.txt", "Hello, WASI!")

}
