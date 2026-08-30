package com.translive.app.engine.camera

/**
 * Dietary and allergen categories for travel menus and food packaging.
 */
enum class FoodAllergen(val labelRu: String, val labelEn: String, val icon: String) {
    NUTS("Орехи", "Nuts", "🥜"),
    SEAFOOD("Морепродукты", "Seafood", "🦐"),
    DAIRY("Молоко / Лактоза", "Dairy", "🥛"),
    GLUTEN("Глютен", "Gluten", "🌾"),
    PORK("Свинина", "Pork", "🥩"),
    SPICY("Острое", "Spicy", "🌶️"),
    VEGETARIAN("Вегетарианское", "Vegetarian", "🌱")
}

/**
 * High-speed multilingual food ingredient and allergen classifier.
 */
object AllergenClassifier {

    private val NUT_KEYWORDS = setOf(
        "nut", "nuts", "peanut", "peanuts", "almond", "cashew", "walnut", "hazelnut",
        "pistachio", "macadamia", "pecan", "орех", "орехи", "арахис", "миндаль",
        "фундук", "фисташк", "кешью", "грецкий орех", "dau phong", "hat dieu",
        "hat de", "noyer", "noisette", "cacahuete", "nuez", "cacahuate", "almendra",
        "erdnuss", "mandel", "nuss", "noce", "arachide", "arachidi"
    )

    private val SEAFOOD_KEYWORDS = setOf(
        "seafood", "fish", "salmon", "tuna", "shrimp", "prawn", "crab", "lobster",
        "clam", "mussel", "oyster", "squid", "octopus", "рыба", "лосось", "тунец",
        "креветк", "краб", "омар", "миди", "устриц", "кальмар", "осьминог",
        "tom", "cua", "ca", "muc", "ngheu", "so", "oc", "hai san",
        "poisson", "crevette", "crabe", "moule", "pescado", "marisco", "camaron",
        "fisch", "garnele", "pesce", "gambero", "frutti di mare"
    )

    private val DAIRY_KEYWORDS = setOf(
        "milk", "cheese", "butter", "cream", "yogurt", "lactose", "молоко",
        "сыр", "масло", "сливки", "йогурт", "лактоз", "творог",
        "sua", "pho mai", "bo", "kem", "lait", "fromage", "beurre",
        "leche", "queso", "mantequilla", "milch", "kase", "latte", "formaggio"
    )

    private val GLUTEN_KEYWORDS = setOf(
        "gluten", "wheat", "flour", "barley", "rye", "bread", "pasta", "глютен",
        "пшениц", "мука", "ячмень", "рожь", "хлеб", "паста", "lua mi", "bot mi",
        "banh mi", "ble", "farine", "pain", "trigo", "harina", "pan",
        "weizen", "mehl", "brot", "frumento", "pane"
    )

    private val PORK_KEYWORDS = setOf(
        "pork", "bacon", "ham", "lard", "prosciutto", "свинин", "бекон", "ветчин",
        "сало", "thit heo", "thit lon", "thit ba chi", "porc", "jambon", "lardon",
        "cerdo", "tocino", "jamon", "schwein", "speck", "schinken", "maiale", "pancetta"
    )

    private val SPICY_KEYWORDS = setOf(
        "spicy", "chili", "chilli", "hot", "pepper", "jalapeno", "habanero",
        "острое", "острый", "перец", "чили", "халапеньо", "cay", "ot", "ot hiem",
        "epice", "piment", "picante", "chile", "scharf", "chili", "piccante", "peperoncino"
    )

    private val VEGETARIAN_KEYWORDS = setOf(
        "vegetarian", "vegan", "tofu", "veggie", "vegetable", "вегетариан", "веган",
        "тофу", "овощн", "chay", "dau phu", "dau hu", "vegetarien", "vegetalien",
        "vegetariano", "vegetarisch"
    )

    fun detectAllergens(text: String): Set<FoodAllergen> {
        if (text.isBlank()) return emptySet()
        val lower = text.lowercase()
        val detected = mutableSetOf<FoodAllergen>()

        if (containsAny(lower, NUT_KEYWORDS)) detected.add(FoodAllergen.NUTS)
        if (containsAny(lower, SEAFOOD_KEYWORDS)) detected.add(FoodAllergen.SEAFOOD)
        if (containsAny(lower, DAIRY_KEYWORDS)) detected.add(FoodAllergen.DAIRY)
        if (containsAny(lower, GLUTEN_KEYWORDS)) detected.add(FoodAllergen.GLUTEN)
        if (containsAny(lower, PORK_KEYWORDS)) detected.add(FoodAllergen.PORK)
        if (containsAny(lower, SPICY_KEYWORDS)) detected.add(FoodAllergen.SPICY)
        if (containsAny(lower, VEGETARIAN_KEYWORDS)) detected.add(FoodAllergen.VEGETARIAN)

        return detected
    }

    private fun containsAny(text: String, keywords: Set<String>): Boolean {
        for (kw in keywords) {
            if (text.contains(kw)) return true
        }
        return false
    }
}
