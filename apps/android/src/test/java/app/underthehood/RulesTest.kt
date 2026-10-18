package app.underthehood

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test
    fun namesPreserveUnicode() {
        assertEquals("文件 café.txt", LibraryRules.name(" 文件 café.txt "))
    }

    @Test
    fun unsafeNamesRejected() {
        listOf("", " ", ".", "..", "a/b", "a\u0000b", "x".repeat(256)).forEach { n ->
            try {
                LibraryRules.name(n)
                fail(n)
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun folderCyclesRejected() {
        val a = Node(id = "a", name = "A", folder = true)
        val b = Node(id = "b", parent = "a", name = "B", folder = true)
        assertFalse(LibraryRules.canMove("a", "b", listOf(a, b)))
        assertTrue(LibraryRules.canMove("b", "", listOf(a, b)))
        assertFalse(LibraryRules.canMove("a", "missing", listOf(a, b)))
    }

    @Test
    fun trashHidesDescendants() {
        val a = Node(id = "a", name = "A", folder = true, trashed = true)
        val b = Node(id = "b", parent = "a", name = "B")
        assertFalse(LibraryRules.visible(b, listOf(a, b)))
        assertTrue(LibraryRules.visible(b, listOf(a.copy(trashed = false), b)))
    }

    @Test
    fun storagePlannerRespectsHealthCapacityAndReadOnly() {
        val a = Remote("a", "A", "local", true, free = 100)
        val b = Remote("b", "B", "local", true, free = 1000)
        val bad = Remote("c", "C", "local", false, free = 10000)
        val ro = Remote("d", "D", "local", true, free = 10000, readOnly = true)
        assertEquals("b", LibraryRules.choose(listOf(a, b, bad, ro), 500, "most-free").id)
        assertEquals("a", LibraryRules.choose(listOf(a, b), 50, "round-robin", round = 2).id)
        try {
            LibraryRules.choose(listOf(a), 200, "most-free")
            fail()
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun manualDestinationDoesNotSilentlyFallback() {
        try {
            LibraryRules.choose(listOf(Remote("b", "B", "local", true)), 0, "manual", "a")
            fail()
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun stableIdentifiersCannotEscapeCachePaths() {
        LibraryRules.stableId("00000000-0000-0000-0000-000000000001")
        LibraryRules.stableId("backup-00000000-0000-0000-0000-000000000001", true)
        listOf("../rclone.conf", "1-1-1-1-1", "/tmp/a").forEach { value ->
            try {
                LibraryRules.stableId(value)
                fail()
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun remotePathsRejectTraversal() {
        LibraryRules.relativePath("Documents/文件 café.txt")
        listOf("../secrets", "/absolute", "folder/../file", "a\\b", "a\u0000b").forEach { path ->
            try {
                LibraryRules.relativePath(path)
                fail()
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun archiveInputIsBoundedBeforeParsing() {
        assertEquals("café", readLimited(java.io.ByteArrayInputStream("café".toByteArray()), 16))
        try {
            readLimited(java.io.ByteArrayInputStream(ByteArray(1025)), 1024)
            fail()
        } catch (_: IllegalArgumentException) {}
    }
}
