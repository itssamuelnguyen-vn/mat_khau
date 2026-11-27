package com.samuelcruz.matkhau.security

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.samuelcruz.matkhau.MatKhauApp

/**
 * Ô "Khóa Mật Khẩu" trong Cài đặt nhanh (spec 4.4): sáng khi vault đang mở, bấm là khóa ngay
 * (xóa khóa giải mã khỏi RAM, đóng mọi màn hình chứa dữ liệu, xóa clipboard nếu app vừa copy).
 * Chạy trong cùng process với app nên khóa đúng vault đang mở; process đã chết thì vault vốn đã khóa.
 * Bấm được cả khi điện thoại đang khóa màn hình (khóa thì không cần xác thực).
 */
class PanicLockTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        (application as MatKhauApp).lockNow()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val unlocked = (application as MatKhauApp).repository.isUnlocked()
        tile.state = if (unlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Khóa Mật Khẩu"
        tile.contentDescription = if (unlocked) "Vault đang mở. Bấm để khóa ngay" else "Vault đang khóa"
        tile.updateTile()
    }
}
