package wasi

import wasi.internal.Fd
import wasi.internal.PREOPENED_DIRECTORY
import wasi.internal.closeFile
import wasi.internal.openFile
import wasi.internal.printImpl
import wasi.internal.writeToFile

object Wasi {
    fun println(message: String) = printImpl(message, false, true)
    fun print(message: String) = printImpl(message, false, false)

    /**
     * Creates a file at the specified path and optionally writes content to it.
     *
     * @param path The path to the file
     * @param content Optional content to write to the file
     * @param directory File descriptor for the directory (defaults to preopen directory)
     */
    fun createFile(path: String, content: String = "", directory: Fd = PREOPENED_DIRECTORY) {
        val fd = openFile(directory, path, createIfNotExists = true, truncateIfExists = true)
        try {
            if (content.isNotEmpty()) {
                writeToFile(fd, content.encodeToByteArray())
            }
        } finally {
            closeFile(fd)
        }
    }

}
