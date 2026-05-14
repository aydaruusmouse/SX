package com.sarif.auto

import androidx.annotation.StringRes

/**
 * All configurable USSD-related timings (milliseconds unless noted).
 * [SecurePrefs] stores each [key]. Built-in defaults are **aggressive / minimum‑ish** for speed;
 * increase values if you see modem errors, missed PIN, or flaky Accessibility capture.
 */
object UssdTimingKeys {

    data class Def(
        val key: String,
        @StringRes val labelRes: Int,
        val defaultMs: Long,
        val minMs: Long = 0L,
        /** When true, [minMs] is treated as a floor of 1 for integer fields. */
        val isIntCount: Boolean = false
    )

    /** Order shown in Settings (advanced USSD timings card). */
    val DEFINITIONS: List<Def> = listOf(
        // —— Balance monitor (run cycle) ——
        Def("ussd_t_dismiss_before_ax_balance_ms", R.string.ussd_timing_dismiss_before_ax_balance, 80L, 0L),
        Def("ussd_t_carrier_failure_backoff_ms", R.string.ussd_timing_carrier_failure_backoff, 2_500L, 0L),
        Def("ussd_t_dismiss_after_skip_transfer_ms", R.string.ussd_timing_dismiss_after_skip_transfer, 100L, 0L),
        Def("ussd_t_transfer_pre_delay_min_ms", R.string.ussd_timing_transfer_pre_delay_min, 40L, 0L),
        Def("ussd_t_transfer_pre_delay_cap_ms", R.string.ussd_timing_transfer_pre_delay_cap, 160L, 0L),
        Def("ussd_t_dismiss_before_transfer_ms", R.string.ussd_timing_dismiss_before_transfer, 220L, 0L),
        // —— Telephony ——
        Def("ussd_t_telephony_callback_timeout_ms", R.string.ussd_timing_telephony_callback_timeout, 12_000L, 5_000L),
        // —— Balance interactive ——
        Def("ussd_t_balance_max_extra_steps", R.string.ussd_timing_balance_max_extra_steps, 8L, 1L, isIntCount = true),
        Def("ussd_t_balance_min_delay_before_pin_ms", R.string.ussd_timing_balance_min_delay_before_pin, 500L, 0L),
        Def("ussd_t_balance_pin_retry_after_fail_ms", R.string.ussd_timing_balance_pin_retry_after_fail, 700L, 0L),
        Def("ussd_t_balance_menu_merge_read_timeout_ms", R.string.ussd_timing_balance_menu_merge_read_timeout, 4_000L, 0L),
        Def("ussd_t_balance_pin_after_open_timeout_ms", R.string.ussd_timing_balance_pin_after_open_timeout, 14_000L, 0L),
        Def("ussd_t_balance_opener_read_fallback_timeout_ms", R.string.ussd_timing_balance_opener_read_fallback_timeout, 5_500L, 0L),
        Def("ussd_t_balance_defer_menu_refresh_ms", R.string.ussd_timing_balance_defer_menu_refresh, 100L, 0L),
        Def("ussd_t_balance_read_menu_after_pin_timeout_ms", R.string.ussd_timing_balance_read_menu_after_pin_timeout, 4_500L, 0L),
        Def("ussd_t_balance_ax_pin_step_delay_ms", R.string.ussd_timing_balance_ax_pin_step_delay, 100L, 0L),
        Def("ussd_t_balance_ax_pin_step_timeout_ms", R.string.ussd_timing_balance_ax_pin_step_timeout, 11_000L, 0L),
        Def("ussd_t_balance_post_open_pause_quick_min_ms", R.string.ussd_timing_balance_post_open_quick_min, 70L, 0L),
        Def("ussd_t_balance_post_open_pause_quick_max_ms", R.string.ussd_timing_balance_post_open_quick_max, 180L, 0L),
        Def("ussd_t_balance_menu_one_pre_await_quick_ms", R.string.ussd_timing_balance_menu_one_pre_await_quick, 70L, 0L),
        Def("ussd_t_balance_menu_one_pre_await_slow_ms", R.string.ussd_timing_balance_menu_one_pre_await_slow, 140L, 0L),
        Def("ussd_t_balance_menu_one_retry_long_quick_ms", R.string.ussd_timing_balance_menu_one_retry_long_quick, 100L, 0L),
        Def("ussd_t_balance_menu_one_retry_long_slow_ms", R.string.ussd_timing_balance_menu_one_retry_long_slow, 400L, 0L),
        Def("ussd_t_balance_menu_one_retry_short_quick_ms", R.string.ussd_timing_balance_menu_one_retry_short_quick, 50L, 0L),
        Def("ussd_t_balance_menu_one_retry_short_slow_ms", R.string.ussd_timing_balance_menu_one_retry_short_slow, 90L, 0L),
        Def("ussd_t_balance_menu_one_ax_timeout_first_ms", R.string.ussd_timing_balance_menu_one_ax_timeout_first, 8_000L, 0L),
        Def("ussd_t_balance_menu_one_ax_timeout_retry_ms", R.string.ussd_timing_balance_menu_one_ax_timeout_retry, 6_500L, 0L),
        Def("ussd_t_balance_interactive_step_floor_ms", R.string.ussd_timing_balance_interactive_step_floor, 30L, 30L),
        // —— Send money (AX chain) ——
        Def("ussd_t_send_step_delay_floor_ms", R.string.ussd_timing_send_step_delay_floor, 30L, 30L),
        Def("ussd_t_send_between_step_menu_min_ms", R.string.ussd_timing_send_between_step_menu_min, 30L, 0L),
        Def("ussd_t_send_between_step_menu_max_ms", R.string.ussd_timing_send_between_step_menu_max, 110L, 0L),
        Def("ussd_t_send_opener_pin_delay_fast_ms", R.string.ussd_timing_send_opener_pin_delay_fast, 50L, 0L),
        Def("ussd_t_send_opener_pin_delay_slow_ms", R.string.ussd_timing_send_opener_pin_delay_slow, 90L, 0L),
        Def("ussd_t_send_pin_after_open_timeout_ms", R.string.ussd_timing_send_pin_after_open_timeout, 14_000L, 0L),
        Def("ussd_t_send_ax_follow_timeout_ms", R.string.ussd_timing_send_ax_follow_timeout, 10_000L, 0L),
        Def("ussd_t_send_stale_menu_retry_ms", R.string.ussd_timing_send_stale_menu_retry, 200L, 0L),
        Def("ussd_t_send_arm_delay_non_pin_ms", R.string.ussd_timing_send_arm_delay_non_pin, 18L, 0L),
        Def("ussd_t_send_arm_delay_pin_fast_long_ms", R.string.ussd_timing_send_arm_delay_pin_fast_long, 70L, 0L),
        Def("ussd_t_send_arm_delay_pin_fast_short_ms", R.string.ussd_timing_send_arm_delay_pin_fast_short, 45L, 0L),
        Def("ussd_t_send_arm_delay_pin_slow_ms", R.string.ussd_timing_send_arm_delay_pin_slow, 40L, 0L),
        // —— Accessibility capture / inject ——
        Def("ussd_t_ax_inject_debounce_menu_ms", R.string.ussd_timing_ax_inject_debounce_menu, 12L, 0L),
        Def("ussd_t_ax_inject_debounce_pin_ms", R.string.ussd_timing_ax_inject_debounce_pin, 90L, 0L),
        Def("ussd_t_ax_capture_second_pass_ms", R.string.ussd_timing_ax_capture_second_pass, 200L, 0L),
        Def("ussd_t_ax_capture_delay_after_pin_ms", R.string.ussd_timing_ax_capture_delay_after_pin, 120L, 0L),
        Def("ussd_t_ax_capture_fast_pin_menu_second_ms", R.string.ussd_timing_ax_capture_fast_pin_menu_second, 20L, 0L),
        Def("ussd_t_ax_capture_delay_menu_ms", R.string.ussd_timing_ax_capture_delay_menu, 100L, 0L),
        Def("ussd_t_ax_capture_second_menu_ms", R.string.ussd_timing_ax_capture_second_menu, 100L, 0L),
        Def("ussd_t_ax_capture_third_menu_ms", R.string.ussd_timing_ax_capture_third_menu, 240L, 0L),
        Def("ussd_t_ax_fast_xfer_third_menu_ms", R.string.ussd_timing_ax_fast_xfer_third_menu, 35L, 0L),
        Def("ussd_t_ax_session_max_ms", R.string.ussd_timing_ax_session_max, 8_000L, 5_000L),
        Def("ussd_t_ax_skip_inject_deliver_fast_ms", R.string.ussd_timing_ax_skip_inject_deliver_fast, 35L, 0L),
        Def("ussd_t_ax_skip_inject_deliver_slow_ms", R.string.ussd_timing_ax_skip_inject_deliver_slow, 55L, 0L),
        Def("ussd_t_ax_implausible_retry_base_ms", R.string.ussd_timing_ax_implausible_retry_base, 35L, 0L),
        Def("ussd_t_ax_implausible_retry_step_ms", R.string.ussd_timing_ax_implausible_retry_step, 22L, 0L),
        Def("ussd_t_ax_max_fail_streak", R.string.ussd_timing_ax_max_fail_streak, 5L, 1L, isIntCount = true),
        Def("ussd_t_ax_fast_xfer_menu_capture_first_ms", R.string.ussd_timing_ax_fast_xfer_menu_first, 28L, 0L),
        Def("ussd_t_ax_fast_xfer_menu_capture_second_ms", R.string.ussd_timing_ax_fast_xfer_menu_second, 18L, 0L),
        Def("ussd_t_ax_fast_xfer_after_pin_first_ms", R.string.ussd_timing_ax_fast_xfer_after_pin_first, 55L, 0L),
        Def("ussd_t_ax_fast_xfer_after_pin_second_ms", R.string.ussd_timing_ax_fast_xfer_after_pin_second, 65L, 0L),
        Def("ussd_t_ax_implausible_slow_base_ms", R.string.ussd_timing_ax_implausible_slow_base, 100L, 0L),
        Def("ussd_t_ax_implausible_slow_step_ms", R.string.ussd_timing_ax_implausible_slow_step, 55L, 0L),
        Def("ussd_t_ax_implausible_slow_cap_ms", R.string.ussd_timing_ax_implausible_slow_cap, 400L, 0L),
        Def("ussd_t_ax_implausible_fast_cap_ms", R.string.ussd_timing_ax_implausible_fast_cap, 200L, 0L),
        Def("ussd_t_ax_menu_max_capture_attempts_fast", R.string.ussd_timing_ax_menu_max_attempts_fast, 4L, 1L, isIntCount = true),
        Def("ussd_t_ax_menu_max_capture_attempts_slow", R.string.ussd_timing_ax_menu_max_attempts_slow, 3L, 1L, isIntCount = true),
    )

    const val KEY_AX_INJECT_KICK_DELAYS_MS = "ussd_t_ax_inject_kick_delays_csv"
    const val DEFAULT_AX_INJECT_KICK_DELAYS_MS = "4,10,18,32,52,85,140"

    const val KEY_AX_READ_CAPTURE_TICK_MS = "ussd_t_ax_read_capture_tick_csv"
    const val DEFAULT_AX_READ_CAPTURE_TICK_MS = "100,280,600,1100"

    const val KEY_AX_READ_CAPTURE_FINALIZE_MS = "ussd_t_ax_read_capture_finalize_csv"
    const val DEFAULT_AX_READ_CAPTURE_FINALIZE_MS = "1800,4000"

    fun defaultForKey(key: String): Long = DEFINITIONS.firstOrNull { it.key == key }?.defaultMs ?: 0L

    fun minForKey(key: String): Long = DEFINITIONS.firstOrNull { it.key == key }?.minMs ?: 0L

    fun isIntCountKey(key: String): Boolean =
        DEFINITIONS.firstOrNull { it.key == key }?.isIntCount == true
}
