package com.samuelcruz.matkhau

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Khóa khẩn cấp (spec 4.4) từ shortcut "Khóa ngay" (giữ biểu tượng app). Không có giao diện: khóa vault,
 * xóa clipboard nếu app vừa copy, rồi đóng ngay. Ai gọi được activity này cũng chỉ làm vault khóa lại.
 */
class LockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as MatKhauApp).lockNow()
        Toast.makeText(this, "Đã khóa Mật Khẩu", Toast.LENGTH_SHORT).show()
        finish()
    }
}
