package dev.surdy.hazri.data

/**
 * A flat namespace of small text documents.
 *
 * The persistence seam. SQLDelight is the obvious answer and is not used here — see
 * `app/README.md` for why — so what the repository writes is three JSON documents, and
 * this is the whole platform surface that needs an `actual` per target: on Android the
 * app's `filesDir`, on iOS the Documents directory, and in tests a map.
 *
 * Implementations are expected to be safe to call from any thread but not transactional:
 * a half-written document is possible if the process dies mid-write, and the repository
 * treats an unparseable document as an absent one.
 */
interface FileStore {
    /** The document's contents, or `null` if it has never been written. */
    fun read(name: String): String?

    /** Replaces the document. */
    fun write(name: String, content: String)

    /** Removes the document. No-op if it does not exist. */
    fun delete(name: String)

    /** Every document name currently stored. */
    fun list(): List<String>
}

/** A [FileStore] in a map. The one used by tests, and by previews with no filesystem. */
class InMemoryFileStore(initial: Map<String, String> = emptyMap()) : FileStore {
    private val documents = LinkedHashMap<String, String>(initial)

    override fun read(name: String): String? = documents[name]
    override fun write(name: String, content: String) {
        documents[name] = content
    }

    override fun delete(name: String) {
        documents.remove(name)
    }

    override fun list(): List<String> = documents.keys.toList()
}
