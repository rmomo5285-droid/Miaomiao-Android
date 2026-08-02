package com.v2ray.ang.xboard

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import java.util.concurrent.TimeUnit

object MiaomiaoEndpointUpdater {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateTask>(
            ENDPOINT_REFRESH_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun refreshNow(): EndpointManifestRefreshResult {
        return EndpointManifestRepository().refresh().also { result ->
            EndpointMigrationNoticeStore.capture(result.active)
        }
    }

    class UpdateTask(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            refreshNow()
            return Result.success()
        }
    }

    private const val WORK_NAME = "miaomiao_endpoint_manifest_refresh"
    private const val ENDPOINT_REFRESH_HOURS = 6L
}
