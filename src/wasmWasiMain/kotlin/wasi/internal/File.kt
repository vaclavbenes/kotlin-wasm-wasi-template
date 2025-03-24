package wasi.internal

import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// File open flags
internal const val O_CREAT = 0x0001  // Create file if it doesn't exist
internal const val O_TRUNC = 0x0040  // Truncate file to size 0

// File rights
internal const val FD_READ = 0x0000000000000001L
internal const val FD_WRITE = 0x0000000000000002L
internal const val FD_SEEK = 0x0000000000000004L

// Common preopen directory descriptor
internal const val PREOPENED_DIRECTORY = 3


/**
 * Opens a file with the specified options and returns the file descriptor.
 */
@OptIn(UnsafeWasmMemoryApi::class)
fun openFile(
    directory: Fd,
    path: String,
    createIfNotExists: Boolean = false,
    truncateIfExists: Boolean = false
): Fd = withScopedMemoryAllocator { allocator ->

    val pathBytes = path.encodeToByteArray()
    val pathPtr = allocator.allocate(pathBytes.size)

    // Copy path bytes to memory
    var currentPtr = pathPtr
    for (byte in pathBytes) {
        currentPtr.storeByte(byte)
        currentPtr += 1
    }

    // Result pointer for the new file descriptor
    val resultPtr = allocator.allocate(4)

    // Set flags based on options
    var oflags = 0
    if (createIfNotExists) oflags = oflags or O_CREAT
    if (truncateIfExists) oflags = oflags or O_TRUNC

    // File rights for read/write/seek
    val fsRightsBase = FD_WRITE or FD_READ or FD_SEEK


    println("Opening file")
    val ret = path_open(
        fd = directory,
        dirflags = 0,
        pathPtr = pathPtr.address.toInt(),
        pathLen = pathBytes.size,
        oflags = oflags,
        fsRightsBase = fsRightsBase,
        fsRightsInheriting = 0L,
        fdFlags = 0.toShort(),
        resultPtr = resultPtr.address.toInt()
    )

    if (ret != 0) {
        println("Return code: $ret : ${WasiErrorCode.entries[ret]}")
        throw WasiError(WasiErrorCode.entries[ret])
    }

    resultPtr.loadInt()
}

/**
 * Writes data to a file descriptor.
 */
@OptIn(UnsafeWasmMemoryApi::class)
fun writeToFile(fd: Fd, data: ByteArray): Int = withScopedMemoryAllocator { allocator ->
    val dataPtr = allocator.allocate(data.size)

    // Copy data to memory
    var currentPtr = dataPtr
    for (byte in data) {
        currentPtr.storeByte(byte)
        currentPtr += 1
    }

    // Set up iovec structure (pointer + length)
    val iovecPtr = allocator.allocate(8)
    (iovecPtr + 0).storeInt(dataPtr.address.toInt())
    (iovecPtr + 4).storeInt(data.size)

    val resultPtr = allocator.allocate(4)

    val ret = fd_write(
        fd = fd,
        iovecPtr = iovecPtr.address.toInt(),
        iovecLen = 1,
        resultPtr = resultPtr.address.toInt()
    )

    if (ret != 0) {
        throw WasiError(WasiErrorCode.entries[ret])
    }

    resultPtr.loadInt()
}

/**
 * Closes a file descriptor.
 */
fun closeFile(fd: Fd) {
    val ret = fd_close(fd)
    if (ret != 0) {
        throw WasiError(WasiErrorCode.entries[ret])
    }
}
