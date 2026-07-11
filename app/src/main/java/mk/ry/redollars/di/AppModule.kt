package mk.ry.redollars.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mk.ry.redollars.data.db.AppDatabase
import mk.ry.redollars.data.db.MessageDao
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime scope for work that must outlive any single ViewModel (WS, DB writes). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // the WS layer sends its own JSON heartbeat
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.get(context)

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): android.content.SharedPreferences =
        context.getSharedPreferences("redollars", Context.MODE_PRIVATE)

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
