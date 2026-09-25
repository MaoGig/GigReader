package com.maogig.gigreader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.maogig.gigreader.core.database.dao.AnnotationDao
import com.maogig.gigreader.core.database.dao.BookmarkDao
import com.maogig.gigreader.core.database.dao.DocumentDao
import com.maogig.gigreader.core.database.dao.FolderDao
import com.maogig.gigreader.core.database.dao.NoteDao
import com.maogig.gigreader.core.database.dao.PageMetricsDao
import com.maogig.gigreader.core.database.dao.ReadingPositionDao
import com.maogig.gigreader.core.database.dao.TagDao
import com.maogig.gigreader.core.database.entity.BookmarkEntity
import com.maogig.gigreader.core.database.entity.DocumentEntity
import com.maogig.gigreader.core.database.entity.DocumentTagEntity
import com.maogig.gigreader.core.database.entity.FolderEntity
import com.maogig.gigreader.core.database.entity.NoteEntity
import com.maogig.gigreader.core.database.entity.PageMetricsEntity
import com.maogig.gigreader.core.database.entity.ReadingPositionEntity
import com.maogig.gigreader.core.database.entity.TagEntity
import com.maogig.gigreader.core.database.entity.TextAnnotationEntity

@Database(
    entities = [
        FolderEntity::class,
        DocumentEntity::class,
        ReadingPositionEntity::class,
        PageMetricsEntity::class,
        NoteEntity::class,
        TextAnnotationEntity::class,
        BookmarkEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GigReaderDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun pageMetricsDao(): PageMetricsDao
    abstract fun noteDao(): NoteDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tagDao(): TagDao

    companion object {
        const val NAME = "gigreader.db"

        /**
         * WAL journaling (Room's default on API 16+ devices with enough RAM) lets the reader persist
         * its position while list queries run concurrently. The database is opened lazily on first
         * query, never on the main thread, and never during Application.onCreate.
         */
        fun create(context: Context): GigReaderDatabase =
            Room.databaseBuilder(context.applicationContext, GigReaderDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        fun inMemory(context: Context): GigReaderDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, GigReaderDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
