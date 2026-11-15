package io.github.akashgupta662642.clipdrop.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.akashgupta662642.clipdrop.data.ClipDropDatabase
import io.github.akashgupta662642.clipdrop.data.PairedHostDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** A scope that lives as long as the process, for work that must outlive any one screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ClipDropDatabase =
        Room.databaseBuilder(context, ClipDropDatabase::class.java, "clipdrop.db").build()

    @Provides
    fun pairedHostDao(database: ClipDropDatabase): PairedHostDao = database.pairedHosts()
}
