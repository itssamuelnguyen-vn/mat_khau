package com.samuelcruz.matkhau.core.card

/** Hãng thẻ, đoán theo đầu số (BIN). Chỉ để hiển thị, không ảnh hưởng tới việc lưu. */
enum class CardBrand(val label: String) {
    VISA("Visa"),
    MASTERCARD("Mastercard"),
    AMEX("American Express"),
    JCB("JCB"),
    UNIONPAY("UnionPay"),
    NAPAS("Napas"),
    DISCOVER("Discover"),
    DINERS("Diners Club"),
    UNKNOWN("Thẻ"),
}

/** Tháng / năm hết hạn (năm đủ 4 chữ số). */
data class CardExpiry(val month: Int, val year: Int) {
    val mmyy: String get() = "%02d/%02d".format(month, year % 100)

    /** Hết hạn sau ngày cuối của tháng in trên thẻ. */
    fun isExpired(nowYear: Int, nowMonth: Int): Boolean = year < nowYear || (year == nowYear && month < nowMonth)
}

object PaymentCard {
    fun digits(number: String): String = number.filter { it.isDigit() }

    fun brand(number: String): CardBrand {
        val d = digits(number)
        fun prefix(n: Int) = d.take(n).toIntOrNull() ?: -1
        return when {
            d.startsWith("9704") -> CardBrand.NAPAS
            d.startsWith("4") -> CardBrand.VISA
            prefix(2) in 51..55 || prefix(4) in 2221..2720 -> CardBrand.MASTERCARD
            prefix(2) == 34 || prefix(2) == 37 -> CardBrand.AMEX
            prefix(4) in 3528..3589 -> CardBrand.JCB
            d.startsWith("62") -> CardBrand.UNIONPAY
            d.startsWith("6011") || d.startsWith("65") || prefix(3) in 644..649 -> CardBrand.DISCOVER
            prefix(2) == 36 || prefix(2) == 38 || prefix(3) in 300..305 -> CardBrand.DINERS
            else -> CardBrand.UNKNOWN
        }
    }

    /** Kiểm tra Luhn (bắt lỗi gõ nhầm một chữ số). Thẻ nội địa Napas không phải lúc nào cũng theo Luhn: luôn true. */
    fun looksValid(number: String): Boolean {
        val d = digits(number)
        if (d.length !in 12..19) return false
        if (brand(d) == CardBrand.NAPAS) return true
        var sum = 0
        d.reversed().forEachIndexed { i, c ->
            var v = c - '0'
            if (i % 2 == 1) {
                v *= 2
                if (v > 9) v -= 9
            }
            sum += v
        }
        return sum % 10 == 0
    }

    /** "4111 1111 1111 1111"; Amex "3782 822463 10005". */
    fun format(number: String): String {
        val d = digits(number)
        if (brand(d) == CardBrand.AMEX && d.length == 15) return listOf(d.take(4), d.substring(4, 10), d.substring(10)).joinToString(" ")
        return d.chunked(4).joinToString(" ")
    }

    /** "•••• 1111" — đủ để nhận ra thẻ mà không lộ số. */
    fun masked(number: String): String {
        val d = digits(number)
        return if (d.length < 4) "••••" else "•••• " + d.takeLast(4)
    }

    /** Nhận "MM/YY", "MM/YYYY", "MM-YY", "MMYY", "M/YY". null nếu không đọc được. */
    fun parseExpiry(text: String): CardExpiry? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split('/', '-', ' ', '.').filter { it.isNotEmpty() }
        val (m, y) = when {
            parts.size == 2 -> parts[0] to parts[1]
            parts.size == 1 && parts[0].length == 4 -> parts[0].take(2) to parts[0].drop(2)
            parts.size == 1 && parts[0].length == 6 -> parts[0].take(2) to parts[0].drop(2)
            else -> return null
        }
        val month = m.toIntOrNull() ?: return null
        val yearRaw = y.toIntOrNull() ?: return null
        if (month !in 1..12) return null
        val year = when (y.length) {
            2 -> 2000 + yearRaw
            4 -> yearRaw
            else -> return null
        }
        if (year !in 2000..2099) return null
        return CardExpiry(month, year)
    }
}
