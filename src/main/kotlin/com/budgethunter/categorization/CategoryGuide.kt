package com.budgethunter.categorization

import com.budgethunter.model.EntryCategory

/**
 * What each category means, in one line. This is the wording the app's receipt prompt uses
 * (`categoryHints` in `CreateBudgetEntryFromImageUseCase`), on purpose: a receipt scanned on the
 * phone and the same purchase categorised here should land in the same category. Change one side
 * and the other has to follow.
 */
object CategoryGuide {

    val meanings: Map<String, String> = linkedMapOf(
        "FOOD" to "restaurants, cafes, delivery, bars",
        "GROCERIES" to "supermarket and food/drink shopping to cook at home",
        "SELF_CARE" to "hair, beauty, cosmetics, gym, personal care",
        "TRANSPORTATION" to "fuel, public transport, taxi/ride-hailing, parking, tolls, car maintenance",
        "HOUSEHOLD_ITEMS" to "furniture, appliances, cleaning supplies, hardware, home goods",
        "SERVICES" to "utilities, internet, phone, subscriptions, rent, professional services",
        "EDUCATION" to "tuition, courses, books, school supplies",
        "HEALTH" to "pharmacy, doctors, insurance, medical tests",
        "LEISURE" to "entertainment, travel, events, hobbies, streaming",
        "TAXES" to "taxes, fines, government fees",
        EntryCategory.OTHER to "anything that does not fit the categories above"
    )

    init {
        check(meanings.keys.toList() == EntryCategory.ALL) {
            "CategoryGuide and EntryCategory.ALL list different categories"
        }
    }
}
