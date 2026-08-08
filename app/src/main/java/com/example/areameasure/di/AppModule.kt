package com.example.areameasure.di

import android.content.Context
import androidx.room.Room
import com.example.areameasure.data.local.AppDatabase
import com.example.areameasure.data.local.MeasurementDao
import com.example.areameasure.data.local.PeopleCountDao
import com.example.areameasure.data.local.SpeedDao
import com.example.areameasure.data.repository.MeasurementRepository
import com.example.areameasure.data.repository.PeopleCountRepository
import com.example.areameasure.data.repository.SpeedRepository
import com.example.areameasure.processing.ImageProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "area_measure_db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideMeasurementDao(database: AppDatabase): MeasurementDao {
        return database.measurementDao()
    }

    @Provides
    @Singleton
    fun provideSpeedDao(database: AppDatabase): SpeedDao {
        return database.speedDao()
    }

    @Provides
    @Singleton
    fun providePeopleCountDao(database: AppDatabase): PeopleCountDao {
        return database.peopleCountDao()
    }

    @Provides
    @Singleton
    fun provideMeasurementRepository(dao: MeasurementDao): MeasurementRepository {
        return MeasurementRepository(dao)
    }

    @Provides
    @Singleton
    fun provideSpeedRepository(dao: SpeedDao): SpeedRepository {
        return SpeedRepository(dao)
    }

    @Provides
    @Singleton
    fun providePeopleCountRepository(dao: PeopleCountDao): PeopleCountRepository {
        return PeopleCountRepository(dao)
    }

    @Provides
    @Singleton
    fun provideImageProcessor(): ImageProcessor {
        return ImageProcessor()
    }
}
