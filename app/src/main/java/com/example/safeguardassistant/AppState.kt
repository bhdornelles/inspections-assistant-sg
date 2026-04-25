package com.example.safeguardassistant

object AppState {

    @Volatile
    var selectedProfile: InspectionProfile? = null
        private set

    fun setProfile(profile: InspectionProfile) {
        selectedProfile = profile
    }
}
