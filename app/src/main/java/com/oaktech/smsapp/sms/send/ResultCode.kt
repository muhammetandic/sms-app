package com.oaktech.smsapp.sms.send

import android.app.Activity
import android.telephony.SmsManager

/**
 * Platform send-result codes, mapped to stable app-level values.
 *
 * The numeric values are the ones the platform actually delivers, verified
 * against `android.telephony.SmsManager` in the API 36 sources. Note that
 * `RESULT_CANCELED` (0) collides with `RESULT_ERROR_NONE`, which is why success
 * is keyed on `Activity.RESULT_OK` and every other code is treated as failure
 * rather than the other way round.
 */
enum class ResultCode(val platformCode: Int) {
    SENT(Activity.RESULT_OK),
    DELIVERED(-2),
    NONE(SmsManager.RESULT_ERROR_NONE),
    GENERIC_FAILURE(SmsManager.RESULT_ERROR_GENERIC_FAILURE),
    RADIO_OFF(SmsManager.RESULT_ERROR_RADIO_OFF),
    NULL_PDU(SmsManager.RESULT_ERROR_NULL_PDU),
    NO_SERVICE(SmsManager.RESULT_ERROR_NO_SERVICE),
    LIMIT_EXCEEDED(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED),
    FDN_CHECK_FAILURE(SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE),
    SHORT_CODE_NOT_ALLOWED(SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED),
    SHORT_CODE_NEVER_ALLOWED(SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED),
    INTERNAL_ERROR(SmsManager.RESULT_INTERNAL_ERROR),
    CANCELLED(SmsManager.RESULT_CANCELLED),
    NO_DEFAULT_SMS_APP(SmsManager.RESULT_NO_DEFAULT_SMS_APP),
    UNKNOWN(Int.MAX_VALUE);

    val isFailure: Boolean
        get() = this != SENT && this != DELIVERED

    companion object {
        /**
         * `RESULT_CANCELED` (0) and `RESULT_ERROR_NONE` (0) share a value, so
         * only `RESULT_OK` counts as success. Anything unrecognised is a
         * failure: silently treating an unknown code as success would mark
         * undelivered messages as sent.
         */
        fun fromPlatformCode(code: Int): ResultCode =
            entries.firstOrNull { it.platformCode == code } ?: UNKNOWN
    }
}