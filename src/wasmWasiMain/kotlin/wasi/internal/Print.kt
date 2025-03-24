package wasi.internal

import kotlin.wasm.unsafe.MemoryAllocator
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

private const val STDOUT = 1
private const val STDERR = 2

@OptIn(UnsafeWasmMemoryApi::class)
internal fun wasiPrintImpl(
    allocator: MemoryAllocator,
    data: ByteArray?,
    newLine: Boolean,
    useErrorStream: Boolean
) {
    val dataSize = data?.size ?: 0
    val memorySize = dataSize + (if (newLine) 1 else 0)
    if (memorySize == 0) return

    val ptr = allocator.allocate(memorySize)
    if (data != null) {
        var currentPtr = ptr
        for (el in data) {
            currentPtr.storeByte(el)
            currentPtr += 1
        }
    }
    if (newLine) {
        (ptr + dataSize).storeByte(0x0A)
    }

    val scatterPtr = allocator.allocate(8)
    (scatterPtr + 0).storeInt(ptr.address.toInt())
    (scatterPtr + 4).storeInt(memorySize)

    val rp0 = allocator.allocate(4)

    val ret = wasiRawFdWrite(
        descriptor = if (useErrorStream) STDERR else STDOUT,
        scatterPtr = scatterPtr.address.toInt(),
        scatterSize = 1,
        errorPtr = rp0.address.toInt()
    )

    if (ret != 0) {
        throw WasiError(WasiErrorCode.entries[ret])
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
internal fun printImpl(message: String?, useErrorStream: Boolean, newLine: Boolean) {
    withScopedMemoryAllocator { allocator ->
        wasiPrintImpl(
            allocator = allocator,
            data = message?.encodeToByteArray(),
            newLine = newLine,
            useErrorStream = useErrorStream,
        )
    }
}
