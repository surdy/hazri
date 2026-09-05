package dev.surdy.hazri.data

import java.io.File

/**
 * A [FileStore] over a directory, normally the app's `filesDir`.
 *
 * Writes go through a temporary file and a rename, so a process death mid-write leaves the
 * previous document intact rather than a truncated one.
 */
class AndroidFileStore(private val directory: File) : FileStore {

    init {
        directory.mkdirs()
    }

    override fun read(name: String): String? {
        val file = File(directory, name)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    override fun write(name: String, content: String) {
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            target.writeText(content)
            temporary.delete()
        }
    }

    override fun delete(name: String) {
        File(directory, name).delete()
    }

    override fun list(): List<String> =
        directory.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()
}
