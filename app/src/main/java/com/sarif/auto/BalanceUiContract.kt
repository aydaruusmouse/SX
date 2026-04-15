package com.sarif.auto

object BalanceUiContract {
    const val ACTION_BALANCE_UPDATE = "com.sarif.auto.ACTION_BALANCE_UPDATE"
    const val EXTRA_BALANCE_PLAIN = "balance_plain"

    /** Foreground monitor is executing USSD (show running overlay). */
    const val ACTION_USSD_BUSY = "com.sarif.auto.ACTION_USSD_BUSY"
    const val EXTRA_BUSY = "busy"

    /** One interactive step finished (opener + index + result text). */
    const val ACTION_USSD_STEP = "com.sarif.auto.ACTION_USSD_STEP"
    const val EXTRA_STEP_INDEX = "step_index"
    const val EXTRA_RESULT_IS_FAILURE = "result_is_failure"
    const val EXTRA_STEP_BODY = "step_body"
    const val EXTRA_REQUEST_OPENER = "request_opener"
    const val EXTRA_SIM_LABEL = "sim_label"
}
