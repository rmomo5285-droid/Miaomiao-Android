package com.v2ray.ang.xboard

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

class XBoardBooleanAdapter : JsonDeserializer<Boolean?> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?,
    ): Boolean? {
        if (json == null || json.isJsonNull || !json.isJsonPrimitive) return null
        val value = json.asJsonPrimitive
        return when {
            value.isBoolean -> value.asBoolean
            value.isNumber -> value.asInt != 0
            value.isString -> when (value.asString.trim().lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off", "" -> false
                else -> null
            }
            else -> null
        }
    }
}

data class XBoardSubscription(
    @SerializedName("plan_id") val planId: Int? = null,
    val token: String? = null,
    @SerializedName("expired_at") val expiredAt: Long? = null,
    val u: Long = 0L,
    val d: Long = 0L,
    @SerializedName("transfer_enable") val transferEnable: Long = 0L,
    @SerializedName("device_limit") val deviceLimit: Int? = null,
    @SerializedName("subscribe_url") val subscribeUrl: String? = null,
    @SerializedName("reset_day") val resetDay: Int? = null,
) {
    val usedTraffic: Long
        get() = u + d

    val remainingTraffic: Long
        get() = (transferEnable - usedTraffic).coerceAtLeast(0L)
}

data class XBoardPlan(
    val id: Int,
    @SerializedName("group_id") val groupId: Int? = null,
    val name: String = "",
    val content: String? = null,
    @SerializedName("transfer_enable") val transferEnable: Long = 0L,
    @SerializedName("speed_limit") val speedLimit: Int? = null,
    @SerializedName("device_limit") val deviceLimit: Int? = null,
    @JsonAdapter(XBoardBooleanAdapter::class) val show: Boolean? = null,
    @JsonAdapter(XBoardBooleanAdapter::class) val sell: Boolean? = null,
    @JsonAdapter(XBoardBooleanAdapter::class) val renew: Boolean? = null,
    @SerializedName("month_price") val monthPrice: Long? = null,
    @SerializedName("quarter_price") val quarterPrice: Long? = null,
    @SerializedName("half_year_price") val halfYearPrice: Long? = null,
    @SerializedName("year_price") val yearPrice: Long? = null,
    @SerializedName("two_year_price") val twoYearPrice: Long? = null,
    @SerializedName("three_year_price") val threeYearPrice: Long? = null,
    @SerializedName("onetime_price") val onetimePrice: Long? = null,
    @SerializedName("reset_price") val resetPrice: Long? = null,
)

data class XBoardNotice(
    val id: Int,
    val title: String = "",
    val content: String = "",
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
    @JsonAdapter(XBoardBooleanAdapter::class) val show: Boolean? = null,
)

data class XBoardInviteCode(
    val code: String,
    val views: Int = 0,
    val active: Boolean = true,
)

data class XBoardInviteInfo(
    val codes: List<XBoardInviteCode> = emptyList(),
    val stats: List<Long> = emptyList(),
) {
    val totalInvites: Long
        get() = stats.getOrElse(0) { 0L }

    val commissionRate: Long
        get() = stats.getOrElse(3) { 0L }
}

data class XBoardSaveOrderRequest(
    val planId: Int,
    val period: String,
    val couponCode: String? = null,
)

data class XBoardOrder(
    val tradeNo: String,
)

data class XBoardOrderRecord(
    val id: Int? = null,
    @SerializedName("trade_no") val tradeNo: String = "",
    @SerializedName("plan_id") val planId: Int? = null,
    @SerializedName("payment_id") val paymentId: Int? = null,
    val period: String? = null,
    val status: Int? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
)

data class XBoardPaymentMethod(
    val id: Int,
    val name: String = "",
    val payment: String? = null,
    val icon: String? = null,
    @SerializedName("handling_fee_fixed") val handlingFeeFixed: Long? = null,
    @SerializedName("handling_fee_percent") val handlingFeePercent: Double? = null,
)

data class XBoardCheckoutRequest(
    val tradeNo: String,
    val methodId: Int,
)

data class XBoardCheckoutResult(
    val type: Int? = null,
    val completed: Boolean = false,
    val paymentUrl: String? = null,
    val rawData: JsonElement? = null,
)

data class XBoardOrderStatus(
    val paid: Boolean,
    val statusCode: Int? = null,
    val rawData: JsonElement? = null,
)

enum class XBoardOperationState {
    IDLE,
    LOADING,
    READY,
    ERROR,
}

data class XBoardAccountState(
    val authenticated: Boolean = false,
    val operation: XBoardOperationState = XBoardOperationState.IDLE,
    val subscription: XBoardSubscription? = null,
    val plans: List<XBoardPlan> = emptyList(),
    val notices: List<XBoardNotice> = emptyList(),
    val orders: List<XBoardOrderRecord> = emptyList(),
    val errorMessage: String? = null,
)
