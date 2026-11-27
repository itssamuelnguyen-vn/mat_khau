# Mật Khẩu

Trình quản lý mật khẩu chạy hoàn toàn offline trên Android. Không server, không tài khoản, không quyền Internet.
Mọi dữ liệu được mã hóa ngay trên điện thoại bằng mật khẩu chính của bạn.

- Tài khoản đăng nhập (username, mật khẩu, URL, mã 2FA/TOTP, ghi chú, tag, trường tùy chỉnh) và ghi chú bảo mật
- Mã 2FA tự cập nhật, có đếm ngược; thêm bằng **quét mã QR** hoặc dán secret / `otpauth://`
- Tìm kiếm, lọc theo phân loại, **yêu thích** (ghim lên đầu), sinh mật khẩu / cụm từ (passphrase)
- **Lịch sử mật khẩu** (dùng lại bản cũ), **sức khỏe mật khẩu**: yếu, dùng chung, lâu chưa đổi, thiếu 2FA
- **Khóa khẩn cấp**: ô "Khóa Mật Khẩu" trong Cài đặt nhanh, hoặc giữ biểu tượng app → "Khóa ngay"
- Mở bằng vân tay / khuôn mặt (Android Keystore), Recovery Key khi quên mật khẩu chính
- Tự khóa, tự xóa clipboard, chặn chụp màn hình, màn hình kiểm tra bảo mật
- Backup mã hóa (`.lvbackup`) mở được bằng mật khẩu chính hoặc Recovery Key, khôi phục được trên máy khác

## Build

Nhấn **Ctrl+Shift+B** trong VS Code, rồi chép file APK trong `build\apk\` sang điện thoại và mở để cài.

## ⚠️ Đọc trước khi lưu dữ liệu thật

- **Khóa ký app:** APK được ký bằng khóa debug trên laptop này, nên các bản sau cài đè được. Nếu đổi khóa ký (đổi
  máy build, cài lại Windows mất `~\.android\debug.keystore`) thì phải gỡ app, và **gỡ app là mất vault**. Muốn
  chuyển: xuất backup → gỡ → cài bản mới → khôi phục backup.
- **Recovery Key:** chép tay ra giấy, cất kỹ. Quên mật khẩu chính mà mất Recovery Key thì không ai lấy lại được dữ
  liệu, kể cả app.
- **Backup định kỳ:** Cài đặt → Xuất backup mã hóa, cất file ở máy tính / USB. Backup mở bằng mật khẩu chính (hoặc
  Recovery Key) **tại thời điểm xuất**.

## Giới hạn đã biết

- Dữ liệu đã giải mã nằm trong `String` nên không xóa sạch khỏi RAM được; chỉ khóa giải mã (VEK) được ghi đè bằng 0.
- Xóa clipboard chỉ ở mức cố gắng: hệ thống kill app thì bộ đếm mất; bàn phím có thể giữ lịch sử clipboard.
- Không chống được mã độc có quyền root hoặc Accessibility.
- Thanh đo độ mạnh mật khẩu còn thô.

Chi tiết cho người phát triển (kiến trúc khóa, định dạng file): xem `CLAUDE.md`. Danh sách từ BIP39: MIT, xem
`licenses/`.
