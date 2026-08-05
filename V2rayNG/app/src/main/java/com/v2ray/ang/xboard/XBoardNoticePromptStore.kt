package com.v2ray.ang.xboard

import com.tencent.mmkv.MMKV

object XBoardNoticePromptPolicy {
    fun pending(notices: List<XBoardNotice>, dismissedIds: Set<Int>): XBoardNotice? {
        return notices.asSequence()
            .filter { it.id > 0 && it.id !in dismissedIds }
            .filter { it.show != 0 }
            .filter { it.title.isNotBlank() || it.content.isNotBlank() }
            .maxByOrNull(XBoardNotice::id)
    }
}

object XBoardNoticePromptStore {
    private val storage: MMKV by lazy {
        MMKV.mmkvWithID(STORAGE_ID, MMKV.MULTI_PROCESS_MODE)
    }

    fun pending(notices: List<XBoardNotice>): XBoardNotice? {
        return XBoardNoticePromptPolicy.pending(notices, dismissedIds())
    }

    fun dismiss(id: Int) {
        if (id <= 0) return
        val updated = (dismissedIds() + id)
            .sortedDescending()
            .take(MAX_DISMISSED_IDS)
            .joinToString(",")
        storage.encode(KEY_DISMISSED_IDS, updated)
    }

    private fun dismissedIds(): Set<Int> {
        return storage.decodeString(KEY_DISMISSED_IDS)
            .orEmpty()
            .split(',')
            .mapNotNull(String::toIntOrNull)
            .filter { it > 0 }
            .toSet()
    }

    private const val STORAGE_ID = "MIAOMIAO_XBOARD_NOTICES"
    private const val KEY_DISMISSED_IDS = "DISMISSED_IDS"
    private const val MAX_DISMISSED_IDS = 256
}
