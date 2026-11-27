# Mật Khẩu 0.4.0: checklist thử trên điện thoại

Đánh dấu `[x]` khi đạt. Mục nào sai: ghi lại bước làm, điều thấy được, và chụp màn hình bằng **máy khác** (app chặn
chụp màn hình).

- **P0** = phải đạt trước khi lưu mật khẩu thật.
- **P1** = nên đạt.
- **P2** = trường hợp biên.

**Chuẩn bị:**
- Một tờ giấy và bút để chép Recovery Key.
- Một app 2FA khác để so mã (Google Authenticator, Microsoft Authenticator…).
- Máy tính để hiện mã QR và giữ file backup.
- Dữ liệu thử là **dữ liệu giả**, chưa dùng mật khẩu thật.

Secret TOTP dùng để thử: `JBSWY3DPEHPK3PXP`.

---

## A. Cài đặt và chạy offline

- [ ] **A1 (P0): cài được**
  - Bước: mở file APK, cho phép cài từ nguồn không rõ, bấm Cài đặt.
  - Mong đợi: cài xong, biểu tượng ổ khóa tên **Mật Khẩu** xuất hiện.
- [ ] **A2 (P0): không có quyền mạng**
  - Bước: Cài đặt hệ thống → Ứng dụng → Mật Khẩu → Quyền.
  - Mong đợi:
    - chỉ có Camera (chưa cấp) và có thể dòng sinh trắc học;
    - không có mục mạng hay Internet;
    - mục Dữ liệu di động / Wi-Fi của app luôn 0 B.
- [ ] **A3 (P0): chạy được ở chế độ máy bay**
  - Bước: bật chế độ máy bay, rồi làm các mục B và C.
  - Mong đợi: mọi chức năng chạy bình thường, không có thông báo cần mạng.

## B. Tạo vault và Recovery Key

- [ ] **B1 (P0): màn hình chào**
  - Bước: mở app lần đầu.
  - Mong đợi: thấy "Tạo vault mới" và "Khôi phục từ file backup".
- [ ] **B2 (P0): ràng buộc mật khẩu chính**
  - Bước: Tạo vault mới, nhập mật khẩu 11 ký tự; sau đó nhập 12 ký tự nhưng ô nhập lại khác đi.
  - Mong đợi:
    - nút "Tạo vault" mờ trong cả 2 trường hợp;
    - trường hợp 2 có dòng "Hai mật khẩu không khớp";
    - thanh độ mạnh đổi màu theo mật khẩu.
- [ ] **B3 (P0): tạo vault**
  - Bước: nhập mật khẩu có dấu, ví dụ `Mật khẩu của tôi 2026`, nhập lại giống hệt, tick "Tôi hiểu…", bấm Tạo vault.
  - Mong đợi:
    - vòng xoay "Đang tạo vault (Argon2id × 2, vài giây)…" chạy vài giây;
    - sau đó hiện màn **Recovery Key** gồm 8 nhóm 4 ký tự, và không có nút copy.
- [ ] **B4 (P0): không chụp được Recovery Key**
  - Bước: thử chụp màn hình ở màn Recovery Key.
  - Mong đợi: hệ thống báo không chụp được, hoặc ảnh chụp ra màu đen.
- [ ] **B5 (P0): Back không thoát được màn Recovery Key**
  - Bước: bấm nút Back của hệ thống.
  - Mong đợi: vẫn ở màn Recovery Key.
- [ ] **B6 (P1): màn hình giữ sáng**
  - Bước: để yên lâu hơn thời gian tắt màn hình của máy (ví dụ 30 giây) và hơn 1 phút tự khóa.
  - Mong đợi: màn hình không tự tắt, vault không tự khóa.
- [ ] **B7 (P0): xác nhận đã chép key**
  - Bước: chép key ra giấy, tick "Tôi đã chép Recovery Key ra giấy", bấm Tiếp tục.
  - Mong đợi:
    - nút Tiếp tục chỉ bật khi đã tick;
    - sau đó vào danh sách "Vault trống. Bấm + để thêm mục đầu tiên."
- [ ] **B8 (P1): key chưa xác nhận thì được tạo lại**
  - Làm trên vault thử. Có thể làm sau, bằng Cài đặt → "Tạo Recovery Key mới".
  - Bước: khi màn Recovery Key đang hiện, bấm nút nguồn tắt màn hình, mở lại máy, rồi mở khóa bằng mật khẩu.
  - Mong đợi:
    - hiện lại màn Recovery Key với dòng "Lần trước vault bị khóa khi bạn chưa xác nhận…";
    - key **khác** key trước, và key cũ không còn dùng được.

## C. Thêm, xem, sửa, xóa mục

- [ ] **C1 (P0): thêm tài khoản đăng nhập**
  - Bước: bấm + → Tài khoản đăng nhập, rồi nhập:
    - Tên `GitHub`;
    - username `me@example.com`;
    - mật khẩu bất kỳ;
    - URL `https://github.com`;
    - tag `work, dev`.
  - Bấm ✓ để lưu.
  - Mong đợi:
    - snackbar "Đã lưu";
    - mở ra màn chi tiết của GitHub với tag #work #dev.
- [ ] **C2 (P0): tên là bắt buộc**
  - Bước: thêm mục mới mà bỏ trống Tên.
  - Mong đợi: nút ✓ mờ.
- [ ] **C3 (P0): ẩn/hiện mật khẩu**
  - Bước: ở màn chi tiết, bấm biểu tượng con mắt cạnh Mật khẩu.
  - Mong đợi: mặc định hiện dấu •, bấm thì hiện chữ thật, bấm lần nữa thì ẩn lại.
- [ ] **C4 (P0): sửa mục**
  - Bước: bấm ✏️, đổi username, bấm ✓.
  - Mong đợi: quay lại màn chi tiết, username mới hiện ra, dòng "Sửa:" có thời gian mới.
- [ ] **C5 (P0): xóa mục**
  - Bước: bấm 🗑 → Xóa.
  - Mong đợi: snackbar "Đã xóa", quay về danh sách, mục biến mất.
- [ ] **C6 (P1): ghi chú bảo mật**
  - Bước: bấm + → Ghi chú bảo mật, nhập Tên `Wi-Fi nhà` và nội dung nhiều dòng, rồi lưu.
  - Mong đợi:
    - ở màn chi tiết, nội dung bị che, bấm con mắt mới hiện;
    - lọc "Ghi chú" trong danh sách thì thấy mục này.
- [ ] **C7 (P1): bàn phím không gợi ý nội dung ghi chú**
  - Bước: gõ nội dung ghi chú.
  - Mong đợi: bàn phím không gợi ý hay tự sửa chữ (tùy bàn phím).
- [ ] **C8 (P1): trường tùy chỉnh**
  - Bước: Sửa → Thêm trường, nhập Tên `PIN`, giá trị `1234`, bật Che giấu, rồi lưu.
  - Mong đợi: ở màn chi tiết, trường PIN bị che, có nút hiện và nút copy.
- [ ] **C9 (P1): nhập dở không bị mất**
  - Bước: đang nhập dở một mục, xoay ngang điện thoại, rồi đổi chế độ sáng/tối.
  - Mong đợi: chữ đang nhập vẫn còn, không bị mất.

## D. Tìm kiếm và lọc

- [ ] **D1 (P0): tìm theo các trường được phép**
  - Bước: tạo 3–4 mục, rồi lần lượt gõ vào ô tìm kiếm một phần tên, username, URL và `#work`.
  - Mong đợi: lọc ra đúng mục.
- [ ] **D2 (P0): không tìm theo dữ liệu bí mật**
  - Bước: gõ một phần **mật khẩu** hoặc **nội dung ghi chú**.
  - Mong đợi: **không** tìm thấy, vì app cố ý không tìm theo mật khẩu, TOTP và ghi chú.
- [ ] **D3 (P1): lọc theo phân loại**
  - Bước: bấm các chip Công việc / Tài chính…
  - Mong đợi: chỉ hiện mục đúng phân loại; "Tất cả (n)" đếm đúng tổng số mục.

## E. Clipboard

- [ ] **E1 (P0): copy và tự xóa clipboard**
  - Bước: ở màn chi tiết, bấm copy mật khẩu, mở app ghi chú khác và dán ngay; đợi hơn 30 giây rồi dán lại.
  - Mong đợi:
    - snackbar "Đã copy mật khẩu. Clipboard sẽ được xóa sau 30 giây.";
    - lần dán đầu ra đúng mật khẩu, lần sau không còn gì để dán.
- [ ] **E2 (P0): dán được ở app khác khi dùng "Ngay khi rời app"**
  - Bước: Cài đặt → Tự khóa sau → "Ngay khi rời app". Copy mật khẩu, chuyển sang trình duyệt rồi dán trong vòng 30 giây.
  - Mong đợi: dán được. Quay lại app thì vault đã khóa.
- [ ] **E3 (P1): không xóa thứ người dùng copy sau đó**
  - Bước: copy mật khẩu trong app, sau đó copy một đoạn chữ khác ở app khác, quay lại Mật Khẩu và bấm Khóa ngay.
  - Mong đợi: đoạn chữ vừa copy ở app khác vẫn còn trong clipboard.
- [ ] **E4 (P1): đổi thời gian xóa clipboard**
  - Bước: Cài đặt → Tự xóa clipboard sau → 10 giây, rồi copy.
  - Mong đợi: snackbar ghi 10 giây, và sau khoảng 10 giây clipboard trống.

## F. Mã 2FA (TOTP) và quét QR

- [ ] **F1 (P0): mã khớp với app 2FA khác**
  - Bước: sửa một mục, dán `JBSWY3DPEHPK3PXP` vào ô Secret TOTP rồi lưu. Thêm cùng secret đó vào Google Authenticator.
  - Mong đợi:
    - ở màn chi tiết, thẻ "Mã xác thực 2 bước" hiện 6 số và tự đổi mỗi 30 giây;
    - thanh đếm ngược chạy;
    - mã **giống hệt** Google Authenticator.
- [ ] **F2 (P0): copy mã 2FA**
  - Bước: bấm copy mã 2FA.
  - Mong đợi: snackbar "Đã copy mã 2FA…", dán ra đúng 6 số.
- [ ] **F3 (P0): secret sai không làm sập app**
  - Bước: gõ vào ô TOTP lần lượt `abc!`, `otpauth://totp/a%?secret=JBSWY3DPEHPK3PXP`, `otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=0`.
  - Mong đợi:
    - app **không sập**;
    - dòng đỏ "Secret không hợp lệ…" hiện ra và nút ✓ mờ.
- [ ] **F4 (P0): xin quyền camera khi bấm quét**
  - Bước: bấm biểu tượng QR cạnh ô TOTP lần đầu.
  - Mong đợi: Android hỏi quyền Camera. App chỉ hỏi vào lúc này, không hỏi lúc mở app.
- [ ] **F5 (P1): từ chối quyền camera**
  - Bước: chọn "Không cho phép".
  - Mong đợi: dòng gợi ý "Chưa có quyền Camera. Bạn vẫn có thể dán secret…", app không sập.
- [ ] **F6 (P0): quét mã QR 2FA**
  - Bước:
    1. Trên máy tính, tạo mã QR từ chuỗi `otpauth://totp/Test:me@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Test`, hoặc dùng mã QR thật ở trang bật 2FA của một tài khoản thử.
    2. Thêm mục mới, để trống Tên và Username, bấm QR và cho quyền camera.
    3. Đưa mã QR vào khung.
  - Mong đợi:
    - màn camera đóng lại sau khi đọc được mã;
    - ô Tên tự điền `Test`, Username tự điền `me@example.com`, dòng gợi ý "Đã đọc mã QR 2FA";
    - lưu xong, mã 2FA khớp với F1.
- [ ] **F7 (P1): QR không phải mã 2FA**
  - Bước: quét một mã QR thường, ví dụ link web.
  - Mong đợi: dòng vàng "Mã QR này không phải mã 2FA…", camera tiếp tục quét, app không sập.
- [ ] **F8 (P1): đóng màn camera**
  - Bước: khi màn camera đang mở, bấm Back hoặc nút ✕.
  - Mong đợi: quay về form, chữ đang nhập vẫn còn, đèn báo camera tắt.

## G. Sinh mật khẩu

- [ ] **G1 (P1): sinh mật khẩu**
  - Bước: bấm biểu tượng sinh mật khẩu trên thanh trên cùng, kéo độ dài, bật/tắt các loại ký tự.
  - Mong đợi: mật khẩu đổi ngay, độ dài đúng, chỉ chứa các loại ký tự đang bật, "Độ ngẫu nhiên ~N bit" thay đổi theo.
- [ ] **G2 (P1): tắt hết loại ký tự**
  - Bước: tắt cả 4 loại ký tự.
  - Mong đợi: hiện "Chọn ít nhất một loại ký tự", nút copy mờ.
- [ ] **G3 (P1): sinh cụm từ**
  - Bước: chọn "Cụm từ (passphrase)", kéo số từ, đổi dấu nối.
  - Mong đợi: ra các từ tiếng Anh nối bằng dấu đã chọn, có 1 chữ số, số từ đúng như đã kéo.
- [ ] **G4 (P1): dùng mật khẩu sinh ra trong form**
  - Bước: trong form Sửa, bấm ✨ cạnh ô Mật khẩu → "Dùng mật khẩu này".
  - Mong đợi: ô Mật khẩu được điền mật khẩu vừa sinh.

## H. Khóa và mở khóa

- [ ] **H1 (P0): Khóa ngay**
  - Bước: bấm 🔒 "Khóa ngay" trên thanh trên cùng.
  - Mong đợi:
    - về màn "Vault đang khóa";
    - **không** tự bật hộp thoại vân tay ngay lúc này.
- [ ] **H2 (P0): sai mật khẩu**
  - Bước: nhập sai mật khẩu chính.
  - Mong đợi: sau vài giây hiện dòng đỏ "Sai mật khẩu chính", ô mật khẩu được xóa trống.
- [ ] **H3 (P0): đúng mật khẩu**
  - Bước: nhập đúng mật khẩu (có dấu, như ở B3).
  - Mong đợi: mở được, mọi mục còn nguyên.
- [ ] **H4 (P1): đổi bàn phím vẫn mở được**
  - Chỉ làm nếu máy có 2 bàn phím tiếng Việt khác nhau (ví dụ Gboard và Laban Key).
  - Bước: gõ mật khẩu có dấu bằng bàn phím còn lại.
  - Mong đợi: vẫn mở được.
- [ ] **H5 (P0): tắt màn hình là khóa**
  - Bước: khi vault đang mở, bấm nút nguồn tắt màn hình, rồi mở lại.
  - Mong đợi: vault đã khóa.
- [ ] **H6 (P0): tự khóa khi không thao tác**
  - Bước: Tự khóa sau = 1 phút, mở vault rồi để yên trong app hơn 1 phút (màn hình vẫn sáng).
  - Mong đợi: tự về màn khóa.
- [ ] **H7 (P0): đang gõ thì không bị khóa**
  - Bước: Tự khóa sau = 1 phút, mở form ghi chú và gõ liên tục hơn 1 phút, chỉ gõ phím, không chạm vào chỗ khác.
  - Mong đợi: **không** bị khóa giữa chừng.
- [ ] **H8 (P0): khóa sau khi rời app**
  - Bước: Tự khóa sau = 1 phút, về màn hình chính của máy, đợi hơn 1 phút, rồi mở lại app.
  - Mong đợi: vault đã khóa. Nếu quay lại trong dưới 1 phút thì vẫn mở.
- [ ] **H9 (P0): "Ngay khi rời app"**
  - Bước: chọn "Ngay khi rời app", bấm Home, rồi mở lại app.
  - Mong đợi: vault đã khóa ngay.
- [ ] **H10 (P1): tắt màn hình lúc đang mở khóa**
  - Bước: nhập mật khẩu, bấm Mở khóa rồi bấm nút nguồn ngay khi vòng xoay đang chạy; mở lại máy.
  - Mong đợi: vault vẫn khóa (hoặc snackbar "Vault đã bị khóa"), không mở sẵn.
- [ ] **H11 (P1): chọn file backup không bị khóa giữa chừng**
  - Bước: Tự khóa sau = "Ngay khi rời app", Cài đặt → Xuất backup mã hóa.
  - Mong đợi: lúc mở trình chọn file, vault **không** bị khóa giữa chừng (được hoãn tối đa 5 phút).

## I. Vân tay / khuôn mặt

- [ ] **I1 (P0): bật vân tay**
  - Bước: Cài đặt → bật "Mở khóa bằng vân tay / khuôn mặt", rồi quét vân tay.
  - Mong đợi: snackbar "Đã bật mở khóa bằng vân tay".
- [ ] **I2 (P0): mở bằng vân tay**
  - Bước: tắt màn hình, mở lại máy, mở app.
  - Mong đợi: hộp thoại vân tay tự hiện, quét đúng thì vào vault.
- [ ] **I3 (P0): hủy vân tay, dùng mật khẩu**
  - Bước: bấm "Dùng mật khẩu" trong hộp thoại vân tay.
  - Mong đợi: hộp thoại đóng, ô mật khẩu vẫn dùng được.
- [ ] **I4 (P1): vân tay sai nhiều lần**
  - Bước: quét ngón không đăng ký vài lần.
  - Mong đợi: hệ thống báo không khớp hoặc khóa tạm vân tay; mật khẩu chính vẫn mở được.
- [ ] **I5 (P1): thêm vân tay mới trên máy**
  - Không bắt buộc.
  - Bước: Cài đặt hệ thống → thêm 1 vân tay mới, rồi quay lại app và mở bằng vân tay.
  - Mong đợi:
    - báo "Vân tay trên máy đã thay đổi. Hãy mở bằng mật khẩu chính…";
    - switch vân tay trong app tự tắt.
- [ ] **I6 (P1): tắt vân tay**
  - Bước: Cài đặt → tắt switch vân tay.
  - Mong đợi: snackbar "Đã tắt…", lần khóa sau không còn hiện hộp thoại vân tay.

## J. Recovery Key và đổi mật khẩu

- [ ] **J1 (P0): mở bằng Recovery Key**
  - Bước: ở màn khóa, chọn "Quên mật khẩu? Dùng Recovery Key", nhập key trên giấy. Có thể gõ chữ thường, không gạch nối, gõ `0` thay `O`.
  - Mong đợi:
    - mở được;
    - vào thẳng màn "Đặt mật khẩu chính mới", không có nút quay lại, nút Back cũng không thoát được.
- [ ] **J2 (P0): bắt buộc đặt mật khẩu mới không bỏ qua được**
  - Bước: khi đang ở màn J1, tắt màn hình, rồi mở lại bằng **vân tay** (hoặc mật khẩu cũ).
  - Mong đợi: vẫn bị đưa vào màn "Đặt mật khẩu chính mới".
- [ ] **J3 (P0): đặt mật khẩu mới sau khi dùng Recovery Key**
  - Bước: nhập mật khẩu mới 2 lần → Lưu, khóa lại, thử mật khẩu cũ rồi đến mật khẩu mới.
  - Mong đợi: "Đã đổi mật khẩu chính"; mật khẩu cũ báo sai, mật khẩu mới mở được.
- [ ] **J4 (P0): đổi mật khẩu từ Cài đặt**
  - Bước: Cài đặt → Đổi mật khẩu chính, nhập sai mật khẩu hiện tại; sau đó nhập đúng.
  - Mong đợi: lần sai báo "Mật khẩu hiện tại không đúng"; lần đúng đổi được, giống J3.
- [ ] **J5 (P1): tạo Recovery Key mới**
  - Bước: Cài đặt → Tạo Recovery Key mới → Tạo mới, chép key mới, rồi khóa và thử key **cũ**.
  - Mong đợi: key cũ báo "Recovery Key không đúng"; key mới mở được.

## K. Backup và khôi phục

- [ ] **K1 (P0): xuất backup**
  - Bước: Cài đặt → Xuất backup mã hóa, lưu vào Downloads hoặc Drive.
  - Mong đợi:
    - snackbar "Đã lưu backup (n mục)…";
    - có file `matkhau-yyyyMMdd-HHmm.lvbackup` lớn hơn 0 byte.
- [ ] **K2 (P0): backup không lộ dữ liệu**
  - Bước: chép file sang máy tính, mở bằng Notepad.
  - Mong đợi: chỉ thấy ký tự rác, **không** đọc được tên mục, username hay mật khẩu nào.
- [ ] **K3 (P0): khôi phục từ backup**
  - Bước: xóa 1 mục, rồi Cài đặt → Khôi phục từ backup → chọn file vừa xuất.
  - Mong đợi: hiện "✓ Định dạng file hợp lệ" kèm thông số Argon2id và "Recovery Key: có".
- [ ] **K4 (P0): sai mật khẩu backup**
  - Bước: nhập sai mật khẩu → "Giải mã & kiểm tra".
  - Mong đợi: "Sai mật khẩu của bản backup".
- [ ] **K5 (P0): xác nhận khôi phục**
  - Bước: nhập đúng mật khẩu → Giải mã → Khôi phục.
  - Mong đợi:
    - hiện "✓ Giải mã thành công…" kèm số mục và thời gian xuất;
    - sau Khôi phục, vault khóa lại và báo "Đã khôi phục n mục…";
    - mở khóa thì mục vừa xóa đã quay lại;
    - **vân tay bị tắt** và phải bật lại.
- [ ] **K6 (P0): mở backup bằng Recovery Key**
  - Bước: làm lại K3, chọn chip "Recovery Key", nhập Recovery Key lúc xuất file.
  - Mong đợi: giải mã được.
- [ ] **K7 (P0): file không phải backup**
  - Bước: chọn một file ảnh hoặc PDF bất kỳ để khôi phục.
  - Mong đợi: "File không hợp lệ: …", app không sập.
- [ ] **K8 (P1): file backup bị sửa**
  - Bước: trên máy tính, dùng một trình sửa file nhị phân (hex editor) đổi 1 byte ở giữa file `.lvbackup`, hoặc cắt bớt cuối file, rồi khôi phục.
  - Mong đợi: bị từ chối với "File không hợp lệ", "Sai mật khẩu" hoặc "File backup đã bị sửa đổi hoặc hỏng".
- [ ] **K9 (P1): không khôi phục được khi vault đang khóa**
  - Bước: khi vault đã có dữ liệu và đang khóa, tìm cách khôi phục.
  - Mong đợi: màn khóa **không** có nút khôi phục, chỉ khôi phục được sau khi mở khóa.
- [ ] **K10 (P1): khôi phục sang máy khác**
  - Không bắt buộc.
  - Bước: cài app trên máy thứ hai (hoặc gỡ rồi cài lại trên máy này, chỉ làm khi đã có backup), chọn "Khôi phục từ file backup" ở màn chào.
  - Mong đợi: khôi phục được bằng mật khẩu hoặc Recovery Key của backup, mọi mục còn nguyên.
- [ ] **K11 (P1): mật khẩu cũ vẫn mở backup cũ**
  - Bước: sau khi đổi mật khẩu (J4), khôi phục một backup xuất **trước** khi đổi.
  - Mong đợi: backup đó mở bằng mật khẩu **cũ**, không mở bằng mật khẩu mới.

## L. Màn hình bảo mật và chống chụp

- [ ] **L1 (P0): Bảo mật & quyền riêng tư**
  - Bước: Cài đặt → Bảo mật & quyền riêng tư.
  - Mong đợi các dòng có dấu ✓ xanh:
    - "Không có quyền Internet";
    - "Không sao lưu lên cloud";
    - "Vault được mã hóa" (64 MiB, 3 vòng);
    - "Mật khẩu chính không được lưu, DB không có plaintext" (meta(k, v), items(id, fmt, nonce, ct));
    - "Recovery Key";
    - "Chặn chụp màn hình";
    - "Bản release";
    - "Không analytics…".
- [ ] **L2 (P1): dòng Camera và vân tay**
  - Bước: xem dòng "Quyền Camera" và dòng vân tay.
  - Mong đợi:
    - Camera: chưa cấp thì ✓ "Chưa cấp…", đã cấp thì ⓘ "Đã cấp — chỉ dùng khi bạn bấm quét…";
    - vân tay đang bật: "khóa nằm trong phần cứng bảo mật".
- [ ] **L3 (P1): dòng khóa màn hình điện thoại**
  - Bước: xem dòng này.
  - Mong đợi: máy có PIN hoặc vân tay thì ✓; nếu không thì ⚠️ "rất nên bật".
- [ ] **L4 (P0): không chụp được màn hình nào**
  - Bước: thử chụp màn hình ở danh sách, màn chi tiết và hộp thoại sinh mật khẩu.
  - Mong đợi: không chụp được hoặc ảnh đen. Quay màn hình cũng ra đen.
- [ ] **L5 (P0): ảnh xem trước trong đa nhiệm bị che**
  - Bước: mở danh sách app gần đây (đa nhiệm).
  - Mong đợi: ô xem trước của Mật Khẩu trống hoặc đen, không thấy nội dung.

## N. Tính năng 0.2.0: yêu thích, lịch sử, sức khỏe, khóa khẩn cấp

- [ ] **N1 (P1): đánh dấu yêu thích**
  - Bước: mở một mục, bấm ☆ trên thanh trên cùng.
  - Mong đợi:
    - biểu tượng thành ★ và hiện snackbar "Đã thêm vào Yêu thích";
    - ở danh sách, mục này nằm đầu và có ★ bên phải;
    - có thêm chip "★ Yêu thích" để lọc.
- [ ] **N2 (P1): bỏ yêu thích**
  - Bước: bấm ★ lần nữa.
  - Mong đợi: mục về lại đúng vị trí theo tên; chip "★ Yêu thích" biến mất khi không còn mục nào được ghim.
- [ ] **N3 (P0): đổi mật khẩu thì mật khẩu cũ vào lịch sử**
  - Bước: sửa mục GitHub, đổi mật khẩu rồi lưu. Làm 2 lần.
  - Mong đợi:
    - màn chi tiết có "▸ Lịch sử mật khẩu (2)";
    - bấm vào thấy 2 mật khẩu cũ, mới nhất ở trên, kèm "Dùng tới <ngày>";
    - mỗi mật khẩu cũ bị che, có nút hiện và nút copy.
- [ ] **N4 (P1): sửa chỗ khác không ghi lịch sử**
  - Bước: sửa username, giữ nguyên mật khẩu, rồi lưu.
  - Mong đợi: số mục trong lịch sử không đổi.
- [ ] **N5 (P1): dùng lại mật khẩu cũ**
  - Bước: trong lịch sử bấm "Dùng lại mật khẩu này" → Dùng lại.
  - Mong đợi:
    - mật khẩu hiện tại thành bản cũ đó;
    - mật khẩu vừa thay vào đầu lịch sử;
    - bản vừa dùng lại không còn nằm trong lịch sử.
- [ ] **N6 (P0): lịch sử và yêu thích có trong backup**
  - Bước: xuất backup, khôi phục lại (K3–K5).
  - Mong đợi: lịch sử mật khẩu và ★ vẫn còn sau khi khôi phục.
- [ ] **N7 (P1): độ mạnh khi tạo hoặc đổi mật khẩu chính**
  - Bước: gõ lần lượt `P@ssw0rd2024`, `01011990abc`, `qwerty123456`.
  - Mong đợi: thanh độ mạnh báo Rất yếu / Yếu, kèm lý do như "Là mật khẩu phổ biến…", "Giống ngày tháng…", "Có chuỗi phím liền nhau".
- [ ] **N8 (P1): cảnh báo ở màn chi tiết**
  - Bước: tạo 2 mục cùng mật khẩu `Hanoi2024`, mở một mục.
  - Mong đợi: dưới dòng Mật khẩu có "⚠ Mật khẩu rất yếu: …" và "⚠ Dùng chung với 1 mục khác".
- [ ] **N9 (P1): màn Sức khỏe mật khẩu**
  - Bước: Cài đặt → Sức khỏe mật khẩu.
  - Mong đợi:
    - điểm 0–100, màu đỏ, vàng hoặc xanh;
    - các phần "Mật khẩu yếu", "Mật khẩu dùng chung", "Lâu chưa đổi", "Chưa có 2FA", "Thiếu URL" có số mục đúng;
    - bấm một mục thì mở màn chi tiết của mục đó, bấm Back thì quay lại màn Sức khỏe.
- [ ] **N10 (P1): điểm tăng khi sửa xong**
  - Bước: đổi 2 mục ở N8 sang mật khẩu mạnh, khác nhau (dùng nút sinh mật khẩu), quay lại màn Sức khỏe.
  - Mong đợi: các mục đó biến khỏi "yếu" và "dùng chung", điểm tăng lên.
- [ ] **N11 (P0): ô khóa trong Cài đặt nhanh**
  - Bước: kéo thanh thông báo xuống → ✏ Sửa → kéo ô "Khóa Mật Khẩu" vào. Mở vault rồi kéo thanh thông báo xuống.
  - Mong đợi:
    - ô sáng khi vault đang mở;
    - bấm vào thì ô tối đi; mở app lại thì vault đã khóa.
- [ ] **N12 (P1): ô khóa bấm được khi máy đang khóa**
  - Bước: khi vault đang mở, khóa màn hình điện thoại, kéo thanh thông báo xuống từ màn hình khóa và bấm ô.
  - Mong đợi: vault khóa mà không cần mở khóa điện thoại.
- [ ] **N13 (P0): shortcut "Khóa ngay"**
  - Bước: khi vault đang mở, về màn hình chính, giữ biểu tượng Mật Khẩu → "Khóa ngay".
  - Mong đợi:
    - hiện thông báo nhỏ "Đã khóa Mật Khẩu", không mở giao diện app;
    - mở app thì thấy màn khóa.
- [ ] **N14 (P1): khóa khẩn cấp xóa clipboard**
  - Bước: copy một mật khẩu, rồi dùng ô khóa hoặc shortcut ngay.
  - Mong đợi: clipboard bị xóa ngay, không phải chờ 30 giây.

## O. Nhập từ CSV (0.2.1)

- [ ] **O1 (P0): xuất CSV từ app cũ**
  - Bước: xuất file CSV từ app đang dùng, ví dụ:
    - Bitwarden: Công cụ → Xuất kho → .csv;
    - Google: passwords.google.com → Cài đặt → Xuất;
    - KeePassXC: Cơ sở dữ liệu → Xuất → CSV.
  - Bước: chép file sang điện thoại.
  - Mong đợi: có file `.csv`.
- [ ] **O2 (P0): xem trước khi nhập**
  - Bước: Cài đặt → Chuyển từ app khác → Nhập từ file CSV → chọn file.
  - Mong đợi:
    - màn "Nhập từ CSV" hiện **đúng nguồn** (vd. "Bitwarden") và số mục sẽ nhập;
    - danh sách xem trước chỉ có tên, username, URL, **không** hiện mật khẩu.
- [ ] **O3 (P0): nhập và kiểm tra dữ liệu**
  - Bước: bấm "Nhập N mục", mở vài mục để so với app cũ.
  - Mong đợi:
    - username, mật khẩu, URL, ghi chú, mã 2FA khớp với app cũ;
    - thư mục cũ thành tag;
    - trường tùy chỉnh còn đủ.
- [ ] **O4 (P0): xóa file CSV sau khi nhập**
  - Bước: sau khi nhập, bấm "Xóa file CSV".
  - Mong đợi: báo "Đã xóa file CSV", hoặc hướng dẫn xóa bằng tay. Mở trình quản lý file thì không còn file (kiểm tra cả Thùng rác).
- [ ] **O5 (P1): nhập lại cùng file**
  - Bước: nhập lại đúng file đó lần nữa.
  - Mong đợi: báo "N mục đã có trong vault: bỏ qua", không tạo mục trùng.
- [ ] **O6 (P1): file lưu lại bằng Excel**
  - Bước: mở CSV bằng Excel và lưu lại kiểu "CSV (Comma delimited)", rồi nhập file đó.
  - Mong đợi: nếu file có chữ có dấu thì báo "File không phải UTF-8…", không nhập sai chữ.
- [ ] **O7 (P1): khóa giữa chừng**
  - Bước: đang ở màn xem trước thì tắt màn hình, rồi mở lại.
  - Mong đợi: vault đã khóa, màn xem trước biến mất, phải chọn lại file.

## P. Tự động điền (0.3.0)

- [ ] **P1 (P0): bật dịch vụ tự động điền**
  - Bước: Cài đặt → Tự động điền → "Dịch vụ tự động điền", chọn **Mật Khẩu**, rồi quay lại app.
  - Mong đợi: dòng đổi thành "Đang dùng Mật Khẩu…"; màn Bảo mật cũng báo "Tự động điền: Đang bật".
- [ ] **P2 (P0): bật cho Chrome**
  - Chỉ làm nếu dùng Chrome.
  - Bước: Chrome → Cài đặt → Mật khẩu và tự động điền → chọn "Tự động điền bằng dịch vụ khác", khởi động lại Chrome.
  - Mong đợi: lưu được lựa chọn.
- [ ] **P3 (P0): đề xuất trên trang web khi vault đang mở**
  - Bước: mở Mật Khẩu (vault mở). Trong Chrome hoặc Firefox, vào trang đăng nhập github.com (đã có mục GitHub với URL `https://github.com`) và chạm ô username.
  - Mong đợi:
    - danh sách đề xuất có dòng 🔒 "GitHub · me@example.com" và dòng "Chọn mục khác…";
    - chọn GitHub thì cả username lẫn mật khẩu được điền.
- [ ] **P4 (P0): đề xuất khi vault đang khóa**
  - Bước: khóa vault, quay lại trang đăng nhập và chạm ô.
  - Mong đợi:
    - chỉ có 1 dòng "Mở khóa Mật Khẩu – để điền vào github.com";
    - chọn nó thì hiện màn mở khóa (vân tay hoặc mật khẩu), mở xong thấy GitHub trong "Khớp với trang này";
    - chọn GitHub thì form được điền.
- [ ] **P5 (P0): chống phishing trên web**
  - Bước: vào một trang đăng nhập khác tên miền (vd. gitlab.com) → "Chọn mục khác…" → chọn mục GitHub.
  - Mong đợi:
    - hộp thoại "⚠ Đã chặn tự động điền" nêu rõ trang đang mở và tên miền đã lưu;
    - **không có nút điền**.
- [ ] **P6 (P1): gán tên miền cho mục chưa có URL**
  - Bước: tạo mục không có URL, vào trang đăng nhập → "Chọn mục khác…" → chọn mục đó.
  - Mong đợi:
    - hộp thoại "Gán trang này…" hiện đúng tên miền;
    - bấm "Gán & điền" thì form được điền, và lần sau mục đó tự xuất hiện trong đề xuất.
- [ ] **P7 (P0): app lần đầu cần liên kết**
  - Bước: mở một app có màn đăng nhập (vd. app ngân hàng hoặc app GitHub), chạm ô đăng nhập → "Chọn mục khác…" → chọn mục tương ứng.
  - Mong đợi:
    - hộp thoại hiện tên app, tên gói, chứng chỉ;
    - bấm "Liên kết & điền" thì form được điền.
- [ ] **P8 (P0): app đã liên kết thì tự đề xuất**
  - Bước: mở lại app đó, chạm ô đăng nhập.
  - Mong đợi:
    - mục vừa liên kết hiện thẳng trong đề xuất;
    - trong Mật Khẩu, màn chi tiết mục có phần "Ứng dụng tự động điền" với tên app và nút "Bỏ liên kết".
- [ ] **P9 (P1): bỏ liên kết**
  - Bước: bấm "Bỏ liên kết", quay lại app kia.
  - Mong đợi: mục không còn được đề xuất tự động.
- [ ] **P10 (P0): lưu đăng nhập mới**
  - Bước: đăng nhập vào một trang hoặc app chưa có trong vault, gõ tay username và mật khẩu rồi bấm đăng nhập.
  - Mong đợi:
    - Android hỏi lưu vào Mật Khẩu; chọn Lưu thì hiện màn "Lưu đăng nhập" với tên mục gợi sẵn;
    - bấm "Lưu mục mới" thì hiện thông báo "Đã lưu vào Mật Khẩu";
    - trong app có mục mới với URL đúng (web) hoặc đã liên kết app.
- [ ] **P11 (P1): cập nhật mật khẩu**
  - Bước: đổi mật khẩu trên một website đã lưu, đăng nhập lại bằng mật khẩu mới qua form, rồi chọn lưu.
  - Mong đợi:
    - màn lưu đề nghị "Cập nhật mật khẩu cho …";
    - sau khi cập nhật, mật khẩu cũ nằm trong Lịch sử mật khẩu.
- [ ] **P12 (P1): không lưu**
  - Bước: ở màn "Lưu đăng nhập", bấm "Không lưu" hoặc Back.
  - Mong đợi: không có mục mới; màn hình đóng.
- [ ] **P13 (P0): không chụp được màn tự động điền**
  - Bước: thử chụp màn hình ở màn mở khóa / chọn mục của tự động điền.
  - Mong đợi: không chụp được hoặc ảnh đen.
- [ ] **P14 (P1): tự khóa sau khi điền**
  - Bước: Tự khóa sau = "Ngay khi rời app", điền một lần, rồi mở app Mật Khẩu.
  - Mong đợi: vault đã khóa (vì vừa rời app sau khi điền).

## Q. Chống mã độc dùng quyền Trợ năng (0.4.0)

- [ ] **Q1 (P0): màn Bảo mật báo đúng**
  - Bước: Cài đặt → Bảo mật & quyền riêng tư.
  - Mong đợi:
    - "App có quyền Trợ năng: Không có app lạ…" ✓ (nếu bạn không bật app Trợ năng nào);
    - "Chặn app Trợ năng đọc màn hình: Bật (Android 14+)" ✓;
    - "Bàn phím đang dùng" hiện đúng bàn phím của bạn.
- [ ] **Q2 (P0): TalkBack vẫn dùng được**
  - Chỉ làm nếu muốn thử.
  - Bước: bật TalkBack (Cài đặt → Hỗ trợ tiếp cận) rồi mở app.
  - Mong đợi: TalkBack vẫn đọc được màn hình, vì nó là công cụ Trợ năng thật. Thử xong nhớ tắt TalkBack.
- [ ] **Q3 (P1): cảnh báo app Trợ năng lạ**
  - Chỉ làm nếu máy có sẵn một app Trợ năng không phải công cụ thật (vd. app tự động bấm, app khóa màn hình, app đọc thông báo).
  - Bước: bật Trợ năng cho app đó, khóa vault rồi mở app.
  - Mong đợi:
    - màn mở khóa hiện "⚠ App đang có quyền Trợ năng: <tên> (<tên gói>)…";
    - màn Bảo mật hiện ⚠ kèm tên app, và dòng "Xác thực khi hiện / copy" ghi "Đang TỰ BẬT…";
    - dù chưa bật tùy chọn ở Q4, bấm hiện mật khẩu vẫn bị hỏi vân tay;
    - thử xong tắt lại Trợ năng cho app đó.
- [ ] **Q4 (P0): bật xác thực mỗi lần**
  - Bước: Cài đặt → Chống mã độc → bật "Xác thực mỗi lần hiện / copy / tự điền mật khẩu".
  - Mong đợi: công tắc bật.
- [ ] **Q5 (P0): hiện mật khẩu**
  - Bước: mở một mục, bấm 👁 cạnh mật khẩu.
  - Mong đợi:
    - hệ thống hỏi vân tay (hoặc mã PIN của máy);
    - quét xong thì mật khẩu hiện ra;
    - bấm Hủy thì mật khẩu vẫn bị che.
- [ ] **Q6 (P1): miễn hỏi lại 30 giây**
  - Bước: trong vòng 30 giây sau Q5, bấm copy mật khẩu rồi copy mã 2FA.
  - Mong đợi: không hỏi lại; quá 30 giây thì hỏi lại.
- [ ] **Q7 (P1): khóa thì hết miễn**
  - Bước: khóa vault, mở lại, bấm hiện mật khẩu ngay.
  - Mong đợi: hỏi vân tay lại, thời gian miễn không còn.
- [ ] **Q8 (P0): tự động điền cũng phải xác thực**
  - Bước: khi tùy chọn đang bật, tự động điền vào một trang web.
  - Mong đợi: sau khi chọn mục thì hỏi vân tay, quét xong mới điền.
- [ ] **Q9 (P1): tắt tùy chọn cũng phải xác thực**
  - Bước: tắt công tắc ở Q4.
  - Mong đợi: hỏi vân tay trước khi cho tắt.
- [ ] **Q10 (P1): màn Sửa và bỏ che cũng phải xác thực**
  - Bước: khi tùy chọn đang bật và đã quá 30 giây từ lần xác thực trước, bấm ✏️ Sửa trên một mục; trong màn Sửa, tắt "Che giấu" của một trường tùy chỉnh.
  - Mong đợi: cả hai đều hỏi vân tay trước.
- [ ] **Q11 (P2): hiện chữ ở màn mở khóa không bị hỏi**
  - Bước: ở màn mở khóa, gõ mật khẩu rồi bấm 👁.
  - Mong đợi: hiện chữ ngay, không hỏi vân tay (đó là chữ bạn vừa gõ).

## M. Cài bản mới đè lên (làm khi có bản 0.1.2+)

- [ ] **M1 (P0): cài đè giữ nguyên dữ liệu**
  - Bước: xuất backup, cài APK mới đè lên bản cũ, không gỡ.
  - Mong đợi:
    - cài được mà không phải gỡ;
    - mật khẩu chính cũ mở được, mọi mục và Recovery Key vẫn như cũ.
