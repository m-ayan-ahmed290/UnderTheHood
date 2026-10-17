package dev.underthehood

import android.app.Application
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "nodes", indices = [Index(value=["parent", "name"], unique=true), Index("remote"), Index("trash"), Index("modified")])
data class Node(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val parent: String = "root", val name: String, val folder: Boolean = false,
    val remote: String? = null, val path: String? = null, val size: Long = 0,
    val mime: String = "application/octet-stream", val starred: Boolean = false,
    val trash: Boolean = false, val modified: Long = System.currentTimeMillis(),
    val note: String = "", val tags: String = "", val hash: String? = null
)
@Entity(tableName="jobs", indices=[Index("state")])
data class Job(@PrimaryKey val id: String = UUID.randomUUID().toString(), val kind: String,
    val source: String, val destination: String, val nodeId: String? = null,
    val name: String = "", val parent: String = "root", val state: String = "QUEUED",
    val bytes: Long = 0, val total: Long = 0, val error: String? = null,
    val created: Long = System.currentTimeMillis())
@Entity(tableName="backups")
data class BackupRule(@PrimaryKey val id: String = UUID.randomUUID().toString(), val tree: String,
    val remote: String, val wifi: Boolean = true, val charging: Boolean = false)
@Dao interface HoodDao {
    @Query("SELECT * FROM nodes ORDER BY folder DESC, name COLLATE NOCASE") fun observeNodes(): Flow<List<Node>>
    @Query("SELECT * FROM nodes") suspend fun allNodes(): List<Node>
    @Query("SELECT * FROM nodes WHERE id=:id") suspend fun node(id: String): Node?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(node: Node)
    @Update suspend fun update(node: Node)
    @Query("DELETE FROM nodes WHERE id=:id") suspend fun remove(id: String)
    @Query("SELECT * FROM jobs ORDER BY created DESC") fun jobs(): Flow<List<Job>>
    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): Job?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putJob(job: Job)
    @Query("UPDATE jobs SET state=:state, error=:error WHERE id=:id") suspend fun jobState(id: String, state: String, error: String? = null)
    @Query("UPDATE jobs SET bytes=:bytes, total=:total WHERE id=:id") suspend fun progress(id: String, bytes: Long, total: Long)
    @Query("SELECT * FROM backups") suspend fun backups(): List<BackupRule>
    @Insert suspend fun backup(rule: BackupRule)
    @Query("DELETE FROM backups WHERE id=:id") suspend fun deleteBackup(id: String)
}
@Database(entities=[Node::class, Job::class, BackupRule::class], version=1, exportSchema=true)
abstract class HoodDatabase : RoomDatabase() { abstract fun dao(): HoodDao }
class HoodApplication : Application() {
    val db by lazy { Room.databaseBuilder(this, HoodDatabase::class.java, "hood.db").setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build() }
    val vault by lazy { Vault(this) }
    val engine by lazy { Rclone(this, vault) }
}
