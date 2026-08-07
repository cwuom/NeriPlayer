package moe.ouom.neriplayer.data.local.database

import android.app.Application
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistDao
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncReplicaCheckpointEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity

@Database(
    entities = [
        LocalPlaylistEntity::class,
        TrackEntity::class,
        PlaylistMemberEntity::class,
        PlaylistMemberTokenEntity::class,
        SyncOutboxEntity::class,
        SyncReplicaCheckpointEntity::class,
        MigrationMetadataEntity::class
    ],
    version = 1,
    exportSchema = true
)
internal abstract class NeriUserDataDatabase : RoomDatabase() {
    abstract fun localPlaylistDao(): LocalPlaylistDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    companion object {
        const val DATABASE_NAME = "neri_user_data.db"

        @Volatile
        private var instance: NeriUserDataDatabase? = null

        fun getInstance(context: Context): NeriUserDataDatabase {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { database ->
                    instance = database
                }
            }
        }

        internal fun create(context: Context): NeriUserDataDatabase {
            checkMainProcess(context)
            return Room.databaseBuilder(
                context.applicationContext,
                NeriUserDataDatabase::class.java,
                DATABASE_NAME
            ).build()
        }

        private fun checkMainProcess(context: Context) {
            val expectedProcess = context.applicationInfo.processName
            val currentProcess = Application.getProcessName()
            check(currentProcess == expectedProcess) {
                "Neri user database may only be opened in the main process: " +
                    "current=$currentProcess expected=$expectedProcess"
            }
        }
    }
}
