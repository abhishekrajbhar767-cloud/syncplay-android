package com.syncplay.android

import android.app.Application
import com.syncplay.android.data.repository.PartyRepository

class SyncPlayApp : Application() {
    lateinit var partyRepository: PartyRepository
        private set

    override fun onCreate() {
        super.onCreate()
        partyRepository = PartyRepository(this)
    }
}
