package cryptonic.synchere.com.di

import cryptonic.synchere.com.data.remote.WebSocketClient
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }

    @Provides
    @Singleton
    fun provideWebSocketClient(gson: Gson): WebSocketClient {
        return WebSocketClient(gson)
    }
}
