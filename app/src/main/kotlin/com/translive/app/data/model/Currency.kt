package com.translive.app.data.model

enum class Currency(
    val code: String,
    val symbol: String,
    val localizedNames: List<String>,
    val defaultDecimals: Int,
    val flag: String
) {
    USD("USD", "$", listOf("usd", "dollar", "dollars", "долл", "доллар", "долларов", "bucks"), 2, "🇺🇸"),
    EUR("EUR", "€", listOf("eur", "euro", "euros", "евро"), 2, "🇪🇺"),
    RUB("RUB", "₽", listOf("rub", "руб", "руб.", "рублей", "рубля", "р.", "р"), 0, "🇷🇺"),
    CNY("CNY", "¥", listOf("cny", "rmb", "yuan", "юань", "юаней", "元", "块"), 2, "🇨🇳"),
    VND("VND", "₫", listOf("vnd", "đ", "dong", "đồng", "d"), 0, "🇻🇳"),
    GBP("GBP", "£", listOf("gbp", "pound", "pounds", "фунт", "фунтов"), 2, "🇬🇧"),
    JPY("JPY", "¥", listOf("jpy", "yen", "иен", "иены", "円"), 0, "🇯🇵"),
    KRW("KRW", "₩", listOf("krw", "won", "вон", "вона", "원"), 0, "🇰🇷"),
    TRY("TRY", "₺", listOf("try", "tl", "lira", "лира", "лир"), 2, "🇹🇷"),
    KZT("KZT", "₸", listOf("kzt", "тенге", "тг"), 0, "🇰🇿"),
    AED("AED", "AED", listOf("aed", "dhs", "dirham", "дирхам", "дирхамов", "د.إ"), 2, "🇦🇪"),
    THB("THB", "฿", listOf("thb", "baht", "бат", "батов"), 2, "🇹🇭"),
    IDR("IDR", "Rp", listOf("idr", "rp", "rupiah", "рупий"), 0, "🇮🇩"),
    INR("INR", "₹", listOf("inr", "rs", "rupee", "rupees", "рупия"), 2, "🇮🇳"),
    BRL("BRL", "R$", listOf("brl", "reais", "real"), 2, "🇧🇷"),
    CAD("CAD", "CA$", listOf("cad", "c$"), 2, "🇨🇦"),
    AUD("AUD", "AU$", listOf("aud", "a$"), 2, "🇦🇺"),
    CHF("CHF", "CHF", listOf("chf", "fr", "franc", "франк"), 2, "🇨🇭"),
    SGD("SGD", "SG$", listOf("sgd", "s$"), 2, "🇸🇬"),
    MYR("MYR", "RM", listOf("myr", "rm", "ringgit"), 2, "🇲🇾"),
    PHP("PHP", "₱", listOf("php", "peso", "pesos"), 2, "🇵🇭"),
    ILS("ILS", "₪", listOf("ils", "shekel", "шекель", "шекелей"), 2, "🇮🇱"),
    SAR("SAR", "SAR", listOf("sar", "riyal", "риал", "ر.س"), 2, "🇸🇦"),
    PLN("PLN", "zł", listOf("pln", "zl", "zloty", "злотый"), 2, "🇵🇱"),
    CZK("CZK", "Kč", listOf("czk", "kc", "koruna", "крона"), 2, "🇨🇿"),
    HUF("HUF", "Ft", listOf("huf", "ft", "forint", "форинт"), 0, "🇭🇺");

    companion object {
        fun fromCodeOrSymbol(token: String): Currency? {
            val upper = token.trim().uppercase()
            val lower = token.trim().lowercase()
            return entries.find {
                it.code == upper ||
                it.symbol == token.trim() ||
                it.localizedNames.contains(lower)
            }
        }
    }
}
