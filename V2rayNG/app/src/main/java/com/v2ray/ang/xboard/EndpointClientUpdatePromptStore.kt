package com.v2ray.ang.xboard

import com.tencent.mmkv.MMKV

object EndpointClientUpdatePromptStore {
    private val storage: MMKV by lazy {
        MMKV.mmkvWithID(STORAGE_ID, MMKV.MULTI_PROCESS_MODE)
    }

    fun pendingAndroid(
        payload: EndpointManifestPayload,
        currentBuild: Long,
    ): EndpointClientUpdate? {
        val update = payload.updates?.android ?: return null
        if (!update.isNewerThan(currentBuild)) return null
        if (!update.required && update.build <= storage.decodeLong(KEY_ANDROID_DISMISSED_BUILD, 0L)) {
            return null
        }
        return update
    }

    fun dismissAndroid(update: EndpointClientUpdate) {
        if (update.required) return
        val dismissedBuild = storage.decodeLong(KEY_ANDROID_DISMISSED_BUILD, 0L)
        if (update.build > dismissedBuild) {
            storage.encode(KEY_ANDROID_DISMISSED_BUILD, update.build)
        }
    }

    private const val STORAGE_ID = "MIAOMIAO_CLIENT_UPDATES"
    private const val KEY_ANDROID_DISMISSED_BUILD = "ANDROID_DISMISSED_BUILD"
}
