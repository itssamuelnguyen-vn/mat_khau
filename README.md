# Mật Khẩu

Trình quản lý mật khẩu chạy hoàn toàn offline trên Android. Không server, không tài khoản, không quyền Internet.
Mọi dữ liệu được mã hóa ngay trên điện thoại bằng mật khẩu chính của bạn.

- Tài khoản đăng nhập (username, mật khẩu, URL, mã 2FA/TOTP, ghi chú, tag, trường tùy chỉnh), ghi chú bảo mật,
  **thẻ ngân hàng** (che số thẻ, CVV, PIN; báo số thẻ gõ sai, thẻ hết hạn) và **giấy tờ tùy thân** (CCCD, hộ chiếu…)
- **File đính kèm** mã hóa (ảnh giấy tờ, file khóa…; tối đa 10 MB mỗi file, 40 MB tất cả), có trong backup
- Mã 2FA tự cập nhật, có đếm ngược; thêm bằng **quét mã QR** hoặc dán secret / `otpauth://`; **nhập nhiều mã một
  lúc từ Google Authenticator** (mã QR "Chuyển tài khoản")
- Tìm kiếm, lọc theo phân loại, **yêu thích** (ghim lên đầu), sinh mật khẩu / cụm từ (passphrase)
- **Lịch sử mật khẩu** (dùng lại bản cũ), **sức khỏe mật khẩu**: yếu, dùng chung, lâu chưa đổi, thiếu 2FA
- **Khóa khẩn cấp**: ô "Khóa Mật Khẩu" trong Cài đặt nhanh, hoặc giữ biểu tượng app → "Khóa ngay"
- **Tự động điền** vào app và trình duyệt (cả **thẻ** ở trang thanh toán, luôn hỏi vân tay), **chống phishing**:
  chỉ đề xuất đúng tên miền / đúng app (kiểm tra chứng chỉ ký), tên miền lạ thì chặn; lưu đăng nhập mới hoặc cập
  nhật mật khẩu ngay khi đăng nhập
- **Nhập từ CSV** của Bitwarden, Chrome / Google, Firefox, KeePass…
- Mở bằng vân tay / khuôn mặt (Android Keystore), Recovery Key khi quên mật khẩu chính
- Tự khóa, tự xóa clipboard, chặn chụp màn hình, màn hình kiểm tra bảo mật
- **Chống mã độc dùng quyền Trợ năng** (Android 14+): app lạ có quyền Trợ năng không đọc được màn hình, không bấm thay
  được; cảnh báo app Trợ năng / bàn phím lạ; tùy chọn bắt quét vân tay mỗi lần hiện, copy hoặc tự điền mật khẩu
- Backup mã hóa (`.lvbackup`) mở được bằng mật khẩu chính hoặc Recovery Key, khôi phục được trên máy khác;
  **nhắc backup** khi lâu chưa backup, **tự backup** vào một thư mục (vd. thư mục Google Drive / Syncthing đồng bộ)
- Nhập sai mật khẩu chính nhiều lần thì phải **chờ lâu dần** (30 giây → 30 phút); không bao giờ tự xóa vault

## Build

Nhấn **Ctrl+Shift+B** trong VS Code, rồi chép file APK trong `build\apk\` sang điện thoại và mở để cài.

## ⚠️ Đọc trước khi lưu dữ liệu thật

- **Khóa ký app:** APK được ký bằng khóa debug trên laptop này, nên các bản sau cài đè được. Nếu đổi khóa ký (đổi
  máy build, cài lại Windows mất `~\.android\debug.keystore`) thì phải gỡ app, và **gỡ app là mất vault**. Muốn
  chuyển: xuất backup → gỡ → cài bản mới → khôi phục backup.
- **Recovery Key:** chép tay ra giấy, cất kỹ. Quên mật khẩu chính mà mất Recovery Key thì không ai lấy lại được dữ
  liệu, kể cả app.
- **Backup định kỳ:** Cài đặt → Xuất backup mã hóa (hoặc bật Tự backup vào thư mục), cất file ở máy tính / USB.
  Backup mở bằng mật khẩu chính (hoặc Recovery Key) **tại thời điểm xuất**. Bản 0.4.x trở về trước không đọc được
  backup của 0.5.0.

## Giới hạn đã biết

- Dữ liệu đã giải mã nằm trong `String` nên không xóa sạch khỏi RAM được; chỉ khóa giải mã (VEK) được ghi đè bằng 0.
- Xóa clipboard chỉ ở mức cố gắng: hệ thống kill app thì bộ đếm mất; bàn phím có thể giữ lịch sử clipboard.
- Không chống được mã độc có quyền root. Mã độc dùng quyền Trợ năng: xem lớp bảo vệ ở trên (Android 14+).
- Thanh đo độ mạnh mật khẩu còn thô.

Chi tiết cho người phát triển (kiến trúc khóa, định dạng file): xem `CLAUDE.md`. Danh sách từ BIP39: MIT; font
JetBrains Mono (cho Recovery Key): SIL OFL 1.1; xem `licenses/`.
