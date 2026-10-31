package com.sonarmap.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The single Room database. Version 1: pins + routes + route points, the
 * exact scope the MVP needs.
 *
 * Migration strategy:
 * - Schema JSON for every version is exported (see ksp room.schemaLocation
 *   in app/build.gradle.kts) and committed under app/schemas/.
 * - Every future schema change MUST bump [DB_VERSION] and register a
 *   hand-written androidx.room.migration.Migration in [SonarMapDatabase.migrations]
 *   (diff the exported schema JSONs, move data with SQL) — pin/route data
 *   is user data; a destructive fallback is not a migration strategy.
 * - Room validates the schema at runtime when the first connection opens,
 *   so a missing migration fails loudly instead of silently losing data.
 * - When the first real migration lands, add a
 *   `androidx.room:room-testing` MigrationTestHelper instrumentation test
 *   against the committed schema exports.
 */
@Database(
    entities = [MusicPinEntity::class, RouteEntity::class, RoutePointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SonarMapDatabase : RoomDatabase() {

    abstract fun musicPinDao(): MusicPinDao
    abstract fun routeDao(): RouteDao

    companion object {
        /** Bump with every schema change and commit the new export. */
        const val DB_VERSION = 1

        /** File name in application-private storage. */
        const val DATABASE_FILE = "sonarmap.db"

        /**
         * Registered migrations; empty at version 1 — the first schema
         * change opens this list. Room's runtime schema validation makes a
         * forgotten entry crash loudly instead of silently corrupting.
         */
        val migrations: Array<androidx.room.migration.Migration> = emptyArray()
    }
}

/**
 * One Room database per process: a single write connection, one shared
 * InvalidationTracker. Separately built RoomDatabase instances on the same
 * file would each rebuild their own tracker and risk write races.
 */
object SonarMapDatabaseProvider {

    @Volatile
    private var database: SonarMapDatabase? = null

    fun database(context: Context): SonarMapDatabase = database ?: synchronized(this) {
        database ?: Room.databaseBuilder(
            context.applicationContext,
            SonarMapDatabase::class.java,
            SonarMapDatabase.DATABASE_FILE,
        )
            // Version 1 ships with no migrations; every future schema change
            // must extend SonarMapDatabase.migrations (never a destructive
            // fallback — see the SonarMapDatabase kdoc).
            .addMigrations(*SonarMapDatabase.migrations)
            .build()
            .also { database = it }
    }
}
