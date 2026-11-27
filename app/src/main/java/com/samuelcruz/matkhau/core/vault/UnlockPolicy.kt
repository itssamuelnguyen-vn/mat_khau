package com.samuelcruz.matkhau.core.vault

/** Quyết định có mở vault hay không sau khi đã thử giải mã mọi mục. */
object UnlockPolicy {
    /**
     * @param keyVerified true khi khóa đã được chứng minh đúng (slot mật khẩu / Recovery Key mở được bằng GCM,
     *   hoặc blob vân tay gắn vaultId mở được). Khi đó mục lỗi chỉ là mục hỏng: vẫn mở và cảnh báo — không
     *   bao giờ báo "sai mật khẩu" với mật khẩu đúng (nếu không, một mục hỏng sẽ khóa chủ nhân ngoài vault).
     * @return false = coi như sai khóa (mọi mục đều lỗi với một khóa chưa được kiểm chứng).
     */
    fun accept(total: Int, failed: Int, keyVerified: Boolean): Boolean =
        keyVerified || total == 0 || failed < total
}
