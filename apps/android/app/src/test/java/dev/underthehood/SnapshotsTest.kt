package dev.underthehood
import org.junit.Assert.*
import org.junit.Test
class SnapshotsTest {
    @Test fun restorePreservesUnicodeNestedTrashAndNotes() {
        val folder=Node(id="folder",name="大学",folder=true,trash=true)
        val file=Node(id="file",name="résumé.txt",parent=folder.id,remote="drive",path="folder/thing",size=45,note="note",tags="uni")
        assertEquals(listOf(folder,file),Snapshots.decode(Snapshots.encode(listOf(folder,file))))
    }
    @Test fun cyclesAndMissingParentsAreRejectedBeforeRestore() {
        val node=Node(id="a",name="A",parent="missing",folder=true)
        try{Snapshots.decode(Snapshots.encode(listOf(node)));fail("Accepted orphan")}catch(_:IllegalStateException){}
        try{Snapshots.decode(Snapshots.encode(listOf(node.copy(parent="a"))));fail("Accepted cycle")}catch(_:IllegalArgumentException){}
    }
}
