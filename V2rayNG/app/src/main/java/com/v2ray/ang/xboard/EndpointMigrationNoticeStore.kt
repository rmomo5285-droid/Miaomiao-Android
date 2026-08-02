package com.v2ray.ang.xboard

import com.tencent.mmkv.MMKV

object EndpointMigrationNoticeStore {
    private val storage: MMKV by lazy {
        MMKV.mmkvWithID(STORAGE_ID, MMKV.MULTI_PROCESS_MODE)
    }

    fun capture(payload: EndpointManifestPayload) {
        val notice = payload.migrationNotice ?: return
        val displayText = listOf(notice.title.trim(), notice.message.trim())
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
        if (displayText.isEmpty()) return
        val dismissedVersion = storage.decodeLong(KEY_DISMISSED_VERSION, 0L)
        if (payload.version <= dismissedVersion) return

        storage.encode(KEY_PENDING_VERSION, payload.version)
        storage.encode(KEY_PENDING_NOTICE, displayText.take(MAX_NOTICE_CHARS))
    }

    fun pendingNotice(): String? {
        val pendingVersion = storage.decodeLong(KEY_PENDING_VERSION, 0L)
        val dismissedVersion = storage.decodeLong(KEY_DISMISSED_VERSION, 0L)
        if (pendingVersion <= dismissedVersion) return null
        return storage.decodeString(KEY_PENDING_NOTICE)?.takeIf(String::isNotBlank)
    }

    fun dismissPending() {
        val pendingVersion = storage.decodeLong(KEY_PENDING_VERSION, 0L)
        if (pendingVersion > 0L) {
            storage.encode(KEY_DISMISSED_VERSION, pendingVersion)
        }
        storage.removeValuesForKeys(arrayOf(KEY_PENDING_VERSION, KEY_PENDING_NOTICE))
    }

    private const val STORAGE_ID = "MIAOMIAO_ENDPOINT_NOTICES"
    private const val KEY_PENDING_VERSION = "PENDING_VERSION"
    private const val KEY_PENDING_NOTICE = "PENDING_NOTICE"
    private const val KEY_DISMISSED_VERSION = "DISMISSED_VERSION"
    private const val MAX_NOTICE_CHARS = 4_200
}
