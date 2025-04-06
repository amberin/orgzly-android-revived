package com.orgzly.android.repos

import android.content.Context
import com.orgzly.android.data.DataRepository
import com.orgzly.android.sync.SyncState

interface IntegrallySyncedRepo {
    @Throws(Exception::class)
    fun syncRepo(context: Context, dataRepository: DataRepository): SyncState?
}