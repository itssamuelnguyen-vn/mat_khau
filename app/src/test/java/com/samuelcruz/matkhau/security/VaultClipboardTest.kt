package com.samuelcruz.matkhau.security

import androidx.compose.ui.platform.AndroidClipboard
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultClipboardTest {
    /** 0.5.0 bị dừng khi bôi đen chữ: Compose chỉ nhận Clipboard là AndroidClipboard. */
    @Test
    fun vaultClipboard_isAndroidClipboard() {
        assertTrue(AndroidClipboard::class.java.isAssignableFrom(VaultClipboard::class.java))
    }
}
