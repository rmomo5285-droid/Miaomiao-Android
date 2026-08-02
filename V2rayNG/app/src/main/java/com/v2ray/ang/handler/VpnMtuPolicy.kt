package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

object VpnMtuPolicy {
    const val MIN_MTU = 1280
    const val MAX_MTU = 9000

    fun normalize(rawValue: String?): Int {
        return rawValue?.toIntOrNull()
            ?.takeIf { it in MIN_MTU..MAX_MTU }
            ?: AppConfig.VPN_MTU
    }
}
