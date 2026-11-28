package com.samuelcruz.matkhau.core.vault

/**
 * Chậm dần khi nhập sai mật khẩu chính / Recovery Key (chống người cầm máy đoán thử). Không xóa vault khi sai nhiều
 * (dễ tự xóa nhầm). Kẻ đã chép được file DB ra ngoài không bị lớp này cản, chỉ bị Argon2id làm chậm.
 *
 * Đo thời gian bằng đồng hồ tính từ lúc khởi động máy (elapsedRealtime): đổi giờ máy không rút ngắn được thời gian chờ.
 * Khởi động lại máy (đồng hồ về 0) => thời gian chờ tính lại TỪ ĐẦU, không được miễn.
 */
object UnlockThrottle {
    /** Số lần sai được thử thoải mái trước khi phải chờ. */
    const val FREE_ATTEMPTS = 4

    fun delayMs(failures: Int): Long = when {
        failures <= FREE_ATTEMPTS -> 0L
        failures == 5 -> 30_000L
        failures == 6 -> 60_000L
        failures == 7 -> 5 * 60_000L
        failures == 8 -> 15 * 60_000L
        else -> 30 * 60_000L
    }

    /**
     * Thời gian còn phải chờ. [lastFailureAt] = elapsedRealtime lúc sai lần cuối, [lastBoot] / [boot] = số lần khởi
     * động máy lúc đó / bây giờ. Khác lần khởi động hoặc đồng hồ chạy lùi => chờ lại đủ thời gian (người gọi ghi lại
     * mốc mới bằng [rebase]).
     */
    fun remainingMs(failures: Int, lastFailureAt: Long, lastBoot: Int, now: Long, boot: Int): Long {
        val delay = delayMs(failures)
        if (delay == 0L) return 0L
        if (boot != lastBoot || now < lastFailureAt) return delay
        return (lastFailureAt + delay - now).coerceAtLeast(0L)
    }

    /** Có cần ghi lại mốc thời gian (máy vừa khởi động lại) không. */
    fun needsRebase(lastFailureAt: Long, lastBoot: Int, now: Long, boot: Int): Boolean = boot != lastBoot || now < lastFailureAt
}
