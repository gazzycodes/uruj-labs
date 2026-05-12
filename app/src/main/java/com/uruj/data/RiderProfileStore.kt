package com.uruj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uruj.domain.RiderProfile
import com.uruj.domain.RidingPosition
import com.uruj.domain.TireType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore("rider_profile")

class RiderProfileStore(context: Context) {
    private val dataStore = context.applicationContext.profileDataStore

    val profile: Flow<RiderProfile> = dataStore.data.map { prefs ->
        RiderProfile(
            riderWeightKg = prefs[KEY_RIDER_WEIGHT_KG] ?: 70f,
            bikeWeightKg = prefs[KEY_BIKE_WEIGHT_KG] ?: 10f,
            ftpWatts = prefs[KEY_FTP_WATTS] ?: 200,
            maxHrBpm = prefs[KEY_MAX_HR_BPM] ?: 190,
            tireType = runCatching { TireType.valueOf(prefs[KEY_TIRE_TYPE] ?: "") }
                .getOrDefault(TireType.Road),
            ridingPosition = runCatching { RidingPosition.valueOf(prefs[KEY_RIDING_POSITION] ?: "") }
                .getOrDefault(RidingPosition.Hoods),
            ageYears = prefs[KEY_AGE_YEARS] ?: 30,
            heightCm = prefs[KEY_HEIGHT_CM] ?: 175,
        )
    }

    suspend fun current(): RiderProfile = profile.first()

    suspend fun save(profile: RiderProfile) {
        dataStore.edit { prefs ->
            prefs[KEY_RIDER_WEIGHT_KG] = profile.riderWeightKg
            prefs[KEY_BIKE_WEIGHT_KG] = profile.bikeWeightKg
            prefs[KEY_FTP_WATTS] = profile.ftpWatts
            prefs[KEY_MAX_HR_BPM] = profile.maxHrBpm
            prefs[KEY_TIRE_TYPE] = profile.tireType.name
            prefs[KEY_RIDING_POSITION] = profile.ridingPosition.name
            prefs[KEY_AGE_YEARS] = profile.ageYears
            prefs[KEY_HEIGHT_CM] = profile.heightCm
        }
    }

    companion object {
        private val KEY_RIDER_WEIGHT_KG = floatPreferencesKey("rider_weight_kg")
        private val KEY_BIKE_WEIGHT_KG = floatPreferencesKey("bike_weight_kg")
        private val KEY_FTP_WATTS = intPreferencesKey("ftp_watts")
        private val KEY_MAX_HR_BPM = intPreferencesKey("max_hr_bpm")
        private val KEY_TIRE_TYPE = stringPreferencesKey("tire_type")
        private val KEY_RIDING_POSITION = stringPreferencesKey("riding_position")
        private val KEY_AGE_YEARS = intPreferencesKey("age_years")
        private val KEY_HEIGHT_CM = intPreferencesKey("height_cm")
    }
}
