package dev.surdy.hazri.data

import dev.surdy.hazri.domain.NodeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dispatcher that runs what it is given in the *worst* order.
 *
 * The point is not that any real dispatcher behaves like this. It is that the repository's
 * ordering must be a property of the repository rather than a property of the thread pool
 * it happens to be handed — a pool resuming two coroutines in the order it feels like is
 * exactly what turned "rename, then hide" into a persisted pre-hide document.
 */
private class ReversingDispatcher : CoroutineDispatcher() {
    private val queued = mutableListOf<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued += block
    }

    /** Runs everything queued so far, newest first. */
    fun drainInReverse() {
        while (queued.isNotEmpty()) {
            val batch = queued.toList().asReversed()
            queued.clear()
            batch.forEach { it.run() }
        }
    }
}

/** A store that remembers the order it was written in. */
private class RecordingFileStore : FileStore {
    private val documents = mutableMapOf<String, String>()
    val writes = mutableListOf<String>()

    override fun read(name: String): String? = documents[name]

    override fun write(name: String, content: String) {
        documents[name] = content
        writes += name
    }

    override fun delete(name: String) {
        documents.remove(name)
    }

    override fun list(): List<String> = documents.keys.toList()
}

class RepositoryWriteOrderTest {

    private val kitchen = NodeId("kitchen")

    @Test
    fun `the last edit is the one that persists, whatever order the writers run in`() {
        val store = RecordingFileStore()
        val dispatcher = ReversingDispatcher()
        val repository = HazriRepository(store, CoroutineScope(SupervisorJob() + dispatcher))

        repository.noteNode(kitchen)
        repository.renameNode(kitchen, "Bread bin")
        repository.updateNode(kitchen) { it.copy(hidden = true) }

        dispatcher.drainInReverse()

        // Reopening is the only assertion that matters: it is what a restart does.
        val reopened = HazriRepository(store, CoroutineScope(SupervisorJob() + dispatcher))
        val record = reopened.node(kitchen)!!
        assertEquals("Bread bin", record.displayName)
        assertTrue(record.hidden, "the hide was the last edit and must be the one on disk")
    }

    @Test
    fun `many rapid updates persist the final state`() {
        val store = RecordingFileStore()
        val dispatcher = ReversingDispatcher()
        val repository = HazriRepository(store, CoroutineScope(SupervisorJob() + dispatcher))

        repository.noteNode(kitchen)
        repeat(50) { index ->
            repository.updateNode(kitchen) { it.copy(displayName = "Name $index") }
        }
        dispatcher.drainInReverse()

        val reopened = HazriRepository(store, CoroutineScope(SupervisorJob() + dispatcher))
        assertEquals("Name 49", reopened.node(kitchen)!!.displayName)
    }

    @Test
    fun `a superseded write does not touch the file at all`() {
        val store = RecordingFileStore()
        val dispatcher = ReversingDispatcher()
        val repository = HazriRepository(store, CoroutineScope(SupervisorJob() + dispatcher))

        repository.noteNode(kitchen)
        repeat(10) { index ->
            repository.updateNode(kitchen) { it.copy(displayName = "Name $index") }
        }
        dispatcher.drainInReverse()

        // Eleven edits, but only the newest reaches the disk: the ten it superseded are
        // dropped at the lock rather than written and overwritten.
        assertEquals(listOf("nodes.json"), store.writes.distinct())
        assertEquals(1, store.writes.size)
    }

    @Test
    fun `different documents do not supersede each other`() {
        val store = RecordingFileStore()
        val dispatcher = ReversingDispatcher()
        val repository = HazriRepository(store, CoroutineScope(SupervisorJob() + dispatcher))

        repository.noteNode(kitchen)
        repository.addRoom("Kitchen")
        repository.updateSettings { it.copy(phoneId = "iBeacon:x-1-2") }
        dispatcher.drainInReverse()

        val reopened = HazriRepository(store, CoroutineScope(SupervisorJob() + dispatcher))
        assertEquals(1, reopened.nodes.value.size)
        assertEquals(listOf("Kitchen"), reopened.rooms.value)
        assertEquals("iBeacon:x-1-2", reopened.settings.value.phoneId)
    }
}
