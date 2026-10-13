package com.budgethunter.categorization

/**
 * The cheap first layer: recognises well-known merchants and obvious words in Spanish and English
 * and answers instantly, so the AI is only asked about what is left.
 *
 * Tuned for precision over recall. A keyword is whole-word (`tax` does not match `taxi`), and only
 * words that point to one category are listed; ambiguous ones (`metro`, `club`, `rappi`, `gas`)
 * are left out on purpose, because a wrong rule is worse than no rule: it would also keep the AI
 * from being asked. When several keywords match, the longest wins (`uber eats` beats `uber`), and
 * on a tie the category listed first does. Never answers `OTHER`; "no rule matched" is `null`.
 *
 * Keywords are written naturally and normalised with [DescriptionNormalizer] at construction.
 */
class RuleBasedClassifier : CategoryClassifier {

    private data class Rule(val keyword: String, val category: String)

    private val rules: List<Rule> = KEYWORDS
        .flatMap { (category, words) ->
            words.map { Rule(DescriptionNormalizer.normalize(it), category) }
        }
        .filter { it.keyword.isNotEmpty() }
        .distinct()
        // Stable sort: equal lengths keep the declaration order of KEYWORDS.
        .sortedByDescending { it.keyword.length }

    override fun classify(descriptions: List<String>): List<String?> = descriptions.map(::classifyOne)

    private fun classifyOne(description: String): String? {
        val text = DescriptionNormalizer.normalize(description)
        if (text.isEmpty()) return null
        val padded = " $text "
        return rules.firstOrNull { padded.contains(" ${it.keyword} ") }?.category
    }

    private companion object {
        val KEYWORDS: Map<String, List<String>> = linkedMapOf(
            "FOOD" to listOf(
                "restaurante", "restaurant", "cafe", "cafeteria", "coffee", "starbucks", "juan valdez",
                "mcdonalds", "burger king", "kfc", "subway", "dominos", "pizza", "pizzeria", "sushi",
                "hamburguesa", "uber eats", "ubereats", "didi food", "frisby", "el corral", "panaderia",
                "bakery", "heladeria", "bar", "pub", "almuerzo", "desayuno", "lunch", "dinner",
                "breakfast", "brunch"
            ),
            "GROCERIES" to listOf(
                "supermercado", "supermarket", "grocery", "groceries", "mercado", "exito", "jumbo",
                "carulla", "olimpica", "walmart", "costco", "aldi", "lidl", "tesco", "carrefour", "makro",
                "pricesmart", "mercadona", "whole foods", "trader joes", "kroger", "safeway", "fruver",
                "verduras", "frutas", "carniceria", "abarrotes"
            ),
            "SELF_CARE" to listOf(
                "peluqueria", "barberia", "barber", "barbershop", "salon", "spa", "manicure", "pedicure",
                "nail salon", "gimnasio", "gym", "crossfit", "yoga", "cosmeticos", "maquillaje",
                "sephora", "belleza", "haircut", "corte de pelo"
            ),
            "TRANSPORTATION" to listOf(
                "uber", "cabify", "taxi", "indriver", "didi", "gasolina", "gasolinera", "combustible",
                "fuel", "gas station", "terpel", "primax", "texaco", "shell", "esso", "chevron", "bp",
                "peaje", "toll", "parqueadero", "parking", "estacionamiento", "transmilenio", "sitp",
                "bus", "autobus", "buseta", "tren", "train", "soat", "taller mecanico", "mecanico",
                "car wash", "lavado de carros", "llantas", "oil change", "rent a car"
            ),
            "HOUSEHOLD_ITEMS" to listOf(
                "ikea", "homecenter", "home depot", "leroy merlin", "alkosto", "muebles", "furniture",
                "electrodomesticos", "appliance", "appliances", "ferreteria", "hardware store",
                "detergente", "colchon", "mattress", "articulos de limpieza"
            ),
            "SERVICES" to listOf(
                "arriendo", "alquiler", "rent", "acueducto", "alcantarillado", "vanti", "enel", "epm",
                "codensa", "electricity", "electric bill", "gas natural", "internet", "wifi", "claro",
                "movistar", "tigo", "etb", "utilities", "utility", "phone bill", "plan de datos",
                "recarga", "lavanderia", "laundry", "plomero", "plumber", "electricista", "abogado",
                "lawyer", "contador", "accountant", "notaria", "notary", "hosting", "icloud",
                "google one", "dropbox", "adobe"
            ),
            "EDUCATION" to listOf(
                "universidad", "university", "colegio", "school", "escuela", "matricula", "tuition",
                "curso", "course", "udemy", "coursera", "platzi", "duolingo", "libreria", "bookstore",
                "libros", "books", "utiles escolares", "school supplies", "semestre", "clases", "tutor",
                "tutoria", "academia"
            ),
            "HEALTH" to listOf(
                "farmacia", "pharmacy", "drogueria", "cruz verde", "farmatodo", "locatel", "medico",
                "doctor", "hospital", "clinica", "clinic", "dentista", "dentist", "odontologia",
                "laboratorio", "laboratory", "optica", "examen medico", "eps", "medicina prepagada",
                "colsanitas", "psicologo", "psychologist", "terapeuta", "fisioterapia", "urgencias",
                "vacuna", "medicamentos", "medicine"
            ),
            "LEISURE" to listOf(
                "cine", "cinema", "movie", "movies", "cinemark", "cine colombia", "netflix", "spotify",
                "disney plus", "hbo", "hbo max", "prime video", "youtube premium", "steam",
                "playstation", "xbox", "nintendo", "concierto", "concert", "teatro", "theater", "museo",
                "museum", "parque", "hotel", "hostal", "hostel", "airbnb", "booking", "vuelo", "flight",
                "avianca", "latam", "airline", "tiquete", "tiquetes", "boleta", "boletas", "ticketmaster",
                "tuboleta", "discoteca", "fiesta", "bowling", "videojuego", "viaje", "travel",
                "vacaciones", "vacation"
            ),
            "TAXES" to listOf(
                "impuesto", "impuestos", "tax", "taxes", "dian", "predial", "declaracion de renta",
                "income tax", "multa", "multas", "fotomulta", "comparendo", "runt", "tributaria", "iva",
                "retefuente", "irs", "hmrc"
            )
        )
    }
}
