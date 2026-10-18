package app.underthehood

import android.app.Application
import android.content.Context
import androidx.room.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

fun uuid() = UUID.randomUUID().toString()

@Entity(
    tableName = "nodes",
    indices = [Index("parent"), Index("name"), Index(value = ["remote", "path"])],
)
data class Node(
    @PrimaryKey val id: String = uuid(),
    val parent: String = "",
    val name: String,
    val folder: Boolean = false,
    val remote: String = "",
    val path: String = "",
    val size: Long = 0,
    val mime: String = "application/octet-stream",
    val hash: String = "",
    val manifest: String = "",
    val starred: Boolean = false,
    val trashed: Boolean = false,
    val notes: String = "",
    val tags: String = "",
    val modified: Long = System.currentTimeMillis(),
    val revision: Long = 1,
) {
    fun toJson() =
        json(
            "id" to id,
            "parent" to parent,
            "name" to name,
            "folder" to folder,
            "remote" to remote,
            "path" to path,
            "size" to size,
            "mime" to mime,
            "hash" to hash,
            "manifest" to manifest,
            "starred" to starred,
            "trashed" to trashed,
            "notes" to notes,
            "tags" to tags,
            "modified" to modified,
            "revision" to revision,
        )

    companion object {
        fun fromJson(o: JSONObject) =
            Node(
                o.getString("id"),
                o.optString("parent"),
                o.getString("name"),
                o.optBoolean("folder"),
                o.optString("remote"),
                o.optString("path"),
                o.optLong("size"),
                o.optString("mime", "application/octet-stream"),
                o.optString("hash"),
                o.optString("manifest"),
                o.optBoolean("starred"),
                o.optBoolean("trashed"),
                o.optString("notes"),
                o.optString("tags"),
                o.optLong("modified"),
                o.optLong("revision", 1),
            )
    }
}

@Entity(tableName = "remotes")
data class Remote(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val healthy: Boolean = false,
    val total: Long = -1,
    val used: Long = -1,
    val free: Long = -1,
    val checked: Long = 0,
    val readOnly: Boolean = false,
)

@Entity(tableName = "jobs", indices = [Index("state")])
data class Transfer(
    @PrimaryKey val id: String = uuid(),
    val operation: String,
    val node: String = "",
    val uri: String = "",
    val name: String = "",
    val parent: String = "",
    val remote: String = "",
    val state: String = "QUEUED",
    val done: Long = 0,
    val total: Long = 0,
    val error: String = "",
    val created: Long = System.currentTimeMillis(),
    val workId: String = "",
    val chunked: Boolean = false,
    val protected: Boolean = false,
    @ColumnInfo(defaultValue = "0") val distributed: Boolean = false,
)

@Entity(tableName = "backups")
data class Backup(
    @PrimaryKey val id: String = uuid(),
    val uri: String,
    val remote: String,
    val label: String,
    val wifi: Boolean = true,
    val charging: Boolean = false,
    val enabled: Boolean = true,
)

@Entity(tableName = "backup_files", primaryKeys = ["rule", "uri"])
data class BackupFile(val rule: String, val uri: String, val stamp: Long, val size: Long)

@Entity(tableName = "activity")
data class Event(
    @PrimaryKey val id: String = uuid(),
    val message: String,
    val created: Long = System.currentTimeMillis(),
)

@Dao
interface Store {
    @Query("SELECT * FROM nodes ORDER BY folder DESC, name COLLATE NOCASE")
    fun observeNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes") suspend fun nodes(): List<Node>

    @Query("SELECT * FROM nodes WHERE id=:id") suspend fun node(id: String): Node?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(node: Node)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putNodes(nodes: List<Node>)

    @Query("DELETE FROM nodes WHERE id=:id") suspend fun deleteNode(id: String)

    @Query("SELECT * FROM remotes ORDER BY name") fun observeRemotes(): Flow<List<Remote>>

    @Query("SELECT * FROM remotes ORDER BY name") suspend fun remotes(): List<Remote>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRemote(remote: Remote)

    @Query("DELETE FROM remotes WHERE id=:id") suspend fun deleteRemote(id: String)

    @Query("SELECT * FROM jobs ORDER BY created DESC") fun observeJobs(): Flow<List<Transfer>>

    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): Transfer?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putJob(job: Transfer)

    @Query("UPDATE jobs SET state=:state, done=:done, total=:total, error=:error WHERE id=:id")
    suspend fun progress(id: String, state: String, done: Long, total: Long, error: String = "")

    @Query(
        "UPDATE jobs SET state=:state, done=:done, total=:total, error=:error WHERE id=:id AND state NOT IN ('PAUSED','CANCELLED')"
    )
    suspend fun workerProgress(
        id: String,
        state: String,
        done: Long,
        total: Long,
        error: String = "",
    )

    @Query("SELECT * FROM backups") fun observeBackups(): Flow<List<Backup>>

    @Query("SELECT * FROM backups WHERE id=:id") suspend fun backup(id: String): Backup?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBackup(rule: Backup)

    @Query("DELETE FROM backups WHERE id=:id") suspend fun deleteBackup(id: String)

    @Query("SELECT * FROM backup_files WHERE rule=:rule AND uri=:uri")
    suspend fun backupFile(rule: String, uri: String): BackupFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBackupFile(file: BackupFile)

    @Insert suspend fun event(event: Event)

    @Query("SELECT * FROM activity ORDER BY created DESC LIMIT 100")
    fun observeEvents(): Flow<List<Event>>
}

@Database(
    entities =
        [
            Node::class,
            Remote::class,
            Transfer::class,
            Backup::class,
            BackupFile::class,
            Event::class,
        ],
    version = 2,
    exportSchema = true,
)
abstract class HoodDatabase : RoomDatabase() {
    abstract fun store(): Store
}

class HoodApp : Application() {
    lateinit var db: HoodDatabase
    lateinit var vault: Vault

    override fun onCreate() {
        super.onCreate()
        db =
            Room.databaseBuilder(this, HoodDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_1_2)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        vault = Vault(this)
    }
}

val Context.hood
    get() = applicationContext as HoodApp

object LibraryRules {
    fun name(value: String): String {
        val n = value.trim()
        require(
            n.isNotEmpty() && n != "." && n != ".." && !n.contains('/') && !n.contains('\u0000')
        ) {
            "Use a name without slashes or null characters"
        }
        require(n.toByteArray().size <= 255) { "Name is too long" }
        return n
    }

    fun canMove(id: String, parent: String, nodes: List<Node>): Boolean {
        var p = parent
        val seen = mutableSetOf<String>()
        while (p.isNotEmpty()) {
            if (p == id || !seen.add(p)) return false
            p = nodes.find { it.id == p }?.parent ?: return false
        }
        return true
    }

    fun visible(node: Node, nodes: List<Node>): Boolean {
        var n: Node? = node
        val seen = mutableSetOf<String>()
        while (n != null) {
            if (n.trashed || !seen.add(n.id)) return false
            n = nodes.find { it.id == n!!.parent }
        }
        return true
    }

    fun choose(
        remotes: List<Remote>,
        bytes: Long,
        policy: String,
        preferred: String = "",
        round: Int = 0,
    ): Remote {
        val available =
            remotes.filter { it.healthy && !it.readOnly && (it.free < 0 || it.free >= bytes) }
        require(available.isNotEmpty()) {
            "No healthy writable account has enough reported space. Test a connection first."
        }
        return when (policy) {
            "manual" ->
                available.find { it.id == preferred } ?: error("Selected account is unavailable")
            "round-robin" -> available[round.mod(available.size)]
            else -> available.maxBy { if (it.free < 0) 0 else it.free }
        }
    }
}

val MIGRATION_1_2 =
    object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN distributed INTEGER NOT NULL DEFAULT 0")
        }
    }
