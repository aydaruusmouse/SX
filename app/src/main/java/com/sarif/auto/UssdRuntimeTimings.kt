package com.sarif.auto

/**
 * Snapshot of all USSD-related timings for the hot path ([UssdPinBridge] mirror of [SecurePrefs]).
 */
data class UssdRuntimeTimings(
    val dismissBeforeAxBalanceMs: Long,
    val carrierFailureBackoffMs: Long,
    val dismissAfterSkipTransferMs: Long,
    val transferPreDelayMinMs: Long,
    val transferPreDelayCapMs: Long,
    val dismissBeforeTransferMs: Long,
    val telephonyCallbackTimeoutMs: Long,
    val balanceMaxExtraSteps: Int,
    val balanceMinDelayBeforePinMs: Long,
    val balancePinRetryAfterFailMs: Long,
    val balanceMenuMergeReadTimeoutMs: Long,
    val balancePinAfterOpenTimeoutMs: Long,
    val balanceOpenerReadFallbackTimeoutMs: Long,
    val balanceDeferMenuRefreshMs: Long,
    val balanceReadMenuAfterPinTimeoutMs: Long,
    val balanceAxPinStepDelayMs: Long,
    val balanceAxPinStepTimeoutMs: Long,
    val balancePostOpenPauseQuickMinMs: Long,
    val balancePostOpenPauseQuickMaxMs: Long,
    val balanceMenuOnePreAwaitQuickMs: Long,
    val balanceMenuOnePreAwaitSlowMs: Long,
    val balanceMenuOneRetryLongQuickMs: Long,
    val balanceMenuOneRetryLongSlowMs: Long,
    val balanceMenuOneRetryShortQuickMs: Long,
    val balanceMenuOneRetryShortSlowMs: Long,
    val balanceMenuOneAxTimeoutFirstMs: Long,
    val balanceMenuOneAxTimeoutRetryMs: Long,
    val balanceInteractiveStepFloorMs: Long,
    val sendStepDelayFloorMs: Long,
    val sendBetweenStepMenuMinMs: Long,
    val sendBetweenStepMenuMaxMs: Long,
    val sendOpenerPinDelayFastMs: Long,
    val sendOpenerPinDelaySlowMs: Long,
    val sendPinAfterOpenTimeoutMs: Long,
    val sendAxFollowTimeoutMs: Long,
    val sendStaleMenuRetryMs: Long,
    val sendArmDelayNonPinMs: Long,
    val sendArmDelayPinFastLongMs: Long,
    val sendArmDelayPinFastShortMs: Long,
    val sendArmDelayPinSlowMs: Long,
    val axInjectDebounceMenuMs: Long,
    val axInjectDebouncePinMs: Long,
    val axCaptureSecondPassMs: Long,
    val axCaptureDelayAfterPinMs: Long,
    val axCaptureFastPinMenuSecondMs: Long,
    val axCaptureDelayMenuMs: Long,
    val axCaptureSecondMenuMs: Long,
    val axCaptureThirdMenuMs: Long,
    val axFastXferThirdMenuMs: Long,
    val axSessionMaxMs: Long,
    val axSkipInjectDeliverFastMs: Long,
    val axSkipInjectDeliverSlowMs: Long,
    val axImplausibleRetryBaseMs: Long,
    val axImplausibleRetryStepMs: Long,
    val axMaxFailStreak: Int,
    val axFastXferMenuCaptureFirstMs: Long,
    val axFastXferMenuCaptureSecondMs: Long,
    val axFastXferAfterPinFirstMs: Long,
    val axFastXferAfterPinSecondMs: Long,
    val axImplausibleSlowBaseMs: Long,
    val axImplausibleSlowStepMs: Long,
    val axImplausibleSlowCapMs: Long,
    val axImplausibleFastCapMs: Long,
    val axMenuMaxCaptureAttemptsFast: Int,
    val axMenuMaxCaptureAttemptsSlow: Int,
    val axInjectKickDelaysMs: LongArray,
    val axReadCaptureTickMs: LongArray,
    val axReadCaptureFinalizeMs: LongArray,
) {
    companion object {
        private val DEF_BY_KEY = UssdTimingKeys.DEFINITIONS.associateBy { it.key }

        private fun build(
            readMs: (UssdTimingKeys.Def) -> Long,
            readCsv: (String, String) -> String,
        ): UssdRuntimeTimings {
            fun m(key: String): Long = readMs(DEF_BY_KEY.getValue(key))
            fun csv(key: String, defaultCsv: String): LongArray {
                val fb = parseLongCsv(defaultCsv, longArrayOf(0L))
                return parseLongCsv(readCsv(key, defaultCsv), fb)
            }
            return UssdRuntimeTimings(
                dismissBeforeAxBalanceMs = m("ussd_t_dismiss_before_ax_balance_ms"),
                carrierFailureBackoffMs = m("ussd_t_carrier_failure_backoff_ms"),
                dismissAfterSkipTransferMs = m("ussd_t_dismiss_after_skip_transfer_ms"),
                transferPreDelayMinMs = m("ussd_t_transfer_pre_delay_min_ms"),
                transferPreDelayCapMs = m("ussd_t_transfer_pre_delay_cap_ms"),
                dismissBeforeTransferMs = m("ussd_t_dismiss_before_transfer_ms"),
                telephonyCallbackTimeoutMs = m("ussd_t_telephony_callback_timeout_ms"),
                balanceMaxExtraSteps = m("ussd_t_balance_max_extra_steps").toInt(),
                balanceMinDelayBeforePinMs = m("ussd_t_balance_min_delay_before_pin_ms"),
                balancePinRetryAfterFailMs = m("ussd_t_balance_pin_retry_after_fail_ms"),
                balanceMenuMergeReadTimeoutMs = m("ussd_t_balance_menu_merge_read_timeout_ms"),
                balancePinAfterOpenTimeoutMs = m("ussd_t_balance_pin_after_open_timeout_ms"),
                balanceOpenerReadFallbackTimeoutMs = m("ussd_t_balance_opener_read_fallback_timeout_ms"),
                balanceDeferMenuRefreshMs = m("ussd_t_balance_defer_menu_refresh_ms"),
                balanceReadMenuAfterPinTimeoutMs = m("ussd_t_balance_read_menu_after_pin_timeout_ms"),
                balanceAxPinStepDelayMs = m("ussd_t_balance_ax_pin_step_delay_ms"),
                balanceAxPinStepTimeoutMs = m("ussd_t_balance_ax_pin_step_timeout_ms"),
                balancePostOpenPauseQuickMinMs = m("ussd_t_balance_post_open_pause_quick_min_ms"),
                balancePostOpenPauseQuickMaxMs = m("ussd_t_balance_post_open_pause_quick_max_ms"),
                balanceMenuOnePreAwaitQuickMs = m("ussd_t_balance_menu_one_pre_await_quick_ms"),
                balanceMenuOnePreAwaitSlowMs = m("ussd_t_balance_menu_one_pre_await_slow_ms"),
                balanceMenuOneRetryLongQuickMs = m("ussd_t_balance_menu_one_retry_long_quick_ms"),
                balanceMenuOneRetryLongSlowMs = m("ussd_t_balance_menu_one_retry_long_slow_ms"),
                balanceMenuOneRetryShortQuickMs = m("ussd_t_balance_menu_one_retry_short_quick_ms"),
                balanceMenuOneRetryShortSlowMs = m("ussd_t_balance_menu_one_retry_short_slow_ms"),
                balanceMenuOneAxTimeoutFirstMs = m("ussd_t_balance_menu_one_ax_timeout_first_ms"),
                balanceMenuOneAxTimeoutRetryMs = m("ussd_t_balance_menu_one_ax_timeout_retry_ms"),
                balanceInteractiveStepFloorMs = m("ussd_t_balance_interactive_step_floor_ms"),
                sendStepDelayFloorMs = m("ussd_t_send_step_delay_floor_ms"),
                sendBetweenStepMenuMinMs = m("ussd_t_send_between_step_menu_min_ms"),
                sendBetweenStepMenuMaxMs = m("ussd_t_send_between_step_menu_max_ms"),
                sendOpenerPinDelayFastMs = m("ussd_t_send_opener_pin_delay_fast_ms"),
                sendOpenerPinDelaySlowMs = m("ussd_t_send_opener_pin_delay_slow_ms"),
                sendPinAfterOpenTimeoutMs = m("ussd_t_send_pin_after_open_timeout_ms"),
                sendAxFollowTimeoutMs = m("ussd_t_send_ax_follow_timeout_ms"),
                sendStaleMenuRetryMs = m("ussd_t_send_stale_menu_retry_ms"),
                sendArmDelayNonPinMs = m("ussd_t_send_arm_delay_non_pin_ms"),
                sendArmDelayPinFastLongMs = m("ussd_t_send_arm_delay_pin_fast_long_ms"),
                sendArmDelayPinFastShortMs = m("ussd_t_send_arm_delay_pin_fast_short_ms"),
                sendArmDelayPinSlowMs = m("ussd_t_send_arm_delay_pin_slow_ms"),
                axInjectDebounceMenuMs = m("ussd_t_ax_inject_debounce_menu_ms"),
                axInjectDebouncePinMs = m("ussd_t_ax_inject_debounce_pin_ms"),
                axCaptureSecondPassMs = m("ussd_t_ax_capture_second_pass_ms"),
                axCaptureDelayAfterPinMs = m("ussd_t_ax_capture_delay_after_pin_ms"),
                axCaptureFastPinMenuSecondMs = m("ussd_t_ax_capture_fast_pin_menu_second_ms"),
                axCaptureDelayMenuMs = m("ussd_t_ax_capture_delay_menu_ms"),
                axCaptureSecondMenuMs = m("ussd_t_ax_capture_second_menu_ms"),
                axCaptureThirdMenuMs = m("ussd_t_ax_capture_third_menu_ms"),
                axFastXferThirdMenuMs = m("ussd_t_ax_fast_xfer_third_menu_ms"),
                axSessionMaxMs = m("ussd_t_ax_session_max_ms"),
                axSkipInjectDeliverFastMs = m("ussd_t_ax_skip_inject_deliver_fast_ms"),
                axSkipInjectDeliverSlowMs = m("ussd_t_ax_skip_inject_deliver_slow_ms"),
                axImplausibleRetryBaseMs = m("ussd_t_ax_implausible_retry_base_ms"),
                axImplausibleRetryStepMs = m("ussd_t_ax_implausible_retry_step_ms"),
                axMaxFailStreak = m("ussd_t_ax_max_fail_streak").toInt(),
                axFastXferMenuCaptureFirstMs = m("ussd_t_ax_fast_xfer_menu_capture_first_ms"),
                axFastXferMenuCaptureSecondMs = m("ussd_t_ax_fast_xfer_menu_capture_second_ms"),
                axFastXferAfterPinFirstMs = m("ussd_t_ax_fast_xfer_after_pin_first_ms"),
                axFastXferAfterPinSecondMs = m("ussd_t_ax_fast_xfer_after_pin_second_ms"),
                axImplausibleSlowBaseMs = m("ussd_t_ax_implausible_slow_base_ms"),
                axImplausibleSlowStepMs = m("ussd_t_ax_implausible_slow_step_ms"),
                axImplausibleSlowCapMs = m("ussd_t_ax_implausible_slow_cap_ms"),
                axImplausibleFastCapMs = m("ussd_t_ax_implausible_fast_cap_ms"),
                axMenuMaxCaptureAttemptsFast = m("ussd_t_ax_menu_max_capture_attempts_fast").toInt(),
                axMenuMaxCaptureAttemptsSlow = m("ussd_t_ax_menu_max_capture_attempts_slow").toInt(),
                axInjectKickDelaysMs = csv(
                    UssdTimingKeys.KEY_AX_INJECT_KICK_DELAYS_MS,
                    UssdTimingKeys.DEFAULT_AX_INJECT_KICK_DELAYS_MS
                ),
                axReadCaptureTickMs = csv(
                    UssdTimingKeys.KEY_AX_READ_CAPTURE_TICK_MS,
                    UssdTimingKeys.DEFAULT_AX_READ_CAPTURE_TICK_MS
                ),
                axReadCaptureFinalizeMs = csv(
                    UssdTimingKeys.KEY_AX_READ_CAPTURE_FINALIZE_MS,
                    UssdTimingKeys.DEFAULT_AX_READ_CAPTURE_FINALIZE_MS
                ),
            )
        }

        fun fromSecurePrefs(p: SecurePrefs): UssdRuntimeTimings =
            build({ d -> p.ussdTimingMs(d) }, { key, def -> p.ussdTimingCsv(key, def) })

        fun fromKeyDefaults(): UssdRuntimeTimings =
            build({ d -> d.defaultMs }, { _, def -> def })
    }
}

fun parseLongCsv(csv: String, fallback: LongArray): LongArray {
    val parts = csv.split(',').mapNotNull { chunk ->
        chunk.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    }
    return if (parts.isNotEmpty()) parts.toLongArray() else fallback
}
