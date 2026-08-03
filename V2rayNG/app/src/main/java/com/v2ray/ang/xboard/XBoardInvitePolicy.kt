package com.v2ray.ang.xboard

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object XBoardInvitePolicy {
    fun preferredCode(info: XBoardInviteInfo): XBoardInviteCode? {
        return info.codes.firstOrNull { it.active } ?: info.codes.firstOrNull()
    }

    fun buildRegistrationUrl(registrationUrl: String, code: String): String? {
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty() || normalizedCode.length > 256) return null
        val uri = runCatching { URI(registrationUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null

        val encoded = URLEncoder.encode(normalizedCode, StandardCharsets.UTF_8)
            .replace("+", "%20")
        val fragment = uri.rawFragment
        return if (fragment != null) {
            val base = registrationUrl.substringBefore('#')
            val separator = if ('?' in fragment) '&' else '?'
            "$base#$fragment${separator}code=$encoded"
        } else {
            val separator = if (uri.rawQuery == null) '?' else '&'
            "$registrationUrl${separator}code=$encoded"
        }
    }
}
