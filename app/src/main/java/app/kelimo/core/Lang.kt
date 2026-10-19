package app.kelimo.core

import java.util.Locale

/**
 * Languages the app knows about. [curated] means a hand-written course ships in assets;
 * every other target gets AI-generated lessons (clearly labelled as such).
 */
enum class Lang(
    val code: String,
    val endonym: String,
    val flag: String,
    val curated: Boolean = false,
    /** Short, very frequent words used by [LangDetect] to sanity-check model output. */
    val stopwords: Set<String>,
) {
    DE("de", "Deutsch", "🇩🇪", curated = true, stopwords = setOf("der", "die", "das", "und", "ist", "ich", "nicht", "ein", "eine", "du", "sie", "wir", "es", "mit", "zu", "auf", "für", "sind", "haben", "hast", "bin", "bist", "was", "wie", "gern", "gerne", "ja", "nein", "auch", "aber", "sehr", "kann", "möchte", "noch", "schon", "hier", "dein", "mein", "ihr", "heute", "den", "dem", "im", "am")),
    EN("en", "English", "🇬🇧", curated = true, stopwords = setOf("the", "and", "is", "are", "you", "i", "a", "to", "of", "it", "not", "do", "does", "what", "how", "have", "has", "with", "for", "your", "my", "we", "can", "would", "like", "this", "that", "yes", "no", "very", "too", "here", "today", "am", "be")),
    TR("tr", "Türkçe", "🇹🇷", stopwords = setOf("ve", "bir", "bu", "ne", "ben", "sen", "biz", "siz", "çok", "değil", "var", "yok", "mi", "mı", "mu", "mü", "da", "de", "için", "ile", "ama", "evet", "hayır", "nasıl", "neden", "şu", "o", "gibi", "daha", "sonra", "şimdi", "bugün", "senin", "benim")),
    ES("es", "Español", "🇪🇸", stopwords = setOf("el", "la", "los", "las", "y", "es", "de", "que", "en", "un", "una", "no", "yo", "tú", "por", "para", "con", "muy", "sí", "pero", "como", "qué", "está", "estoy", "hoy", "mi", "tu", "lo", "se")),
    FR("fr", "Français", "🇫🇷", stopwords = setOf("le", "la", "les", "et", "est", "de", "un", "une", "je", "tu", "il", "elle", "nous", "vous", "pas", "ne", "que", "qui", "en", "pour", "avec", "très", "oui", "non", "mais", "ça", "c'est", "suis", "aujourd'hui", "mon", "ton")),
    IT("it", "Italiano", "🇮🇹", stopwords = setOf("il", "la", "lo", "gli", "le", "e", "è", "di", "che", "un", "una", "io", "tu", "non", "per", "con", "molto", "sì", "ma", "come", "sono", "oggi", "mio", "tuo", "anche", "ciao")),
    NL("nl", "Nederlands", "🇳🇱", stopwords = setOf("de", "het", "een", "en", "is", "ik", "je", "jij", "niet", "wat", "hoe", "met", "voor", "op", "zijn", "ben", "ja", "nee", "ook", "maar", "heel", "vandaag", "mijn", "jouw")),
    PT("pt", "Português", "🇵🇹", stopwords = setOf("o", "a", "os", "as", "e", "é", "de", "que", "um", "uma", "eu", "você", "não", "para", "com", "muito", "sim", "mas", "como", "estou", "hoje", "meu", "seu", "em")),
    PL("pl", "Polski", "🇵🇱", stopwords = setOf("i", "w", "nie", "się", "na", "to", "jest", "że", "z", "co", "jak", "ja", "ty", "tak", "ale", "bardzo", "dzisiaj", "mój", "twój", "czy", "do")),
    RU("ru", "Русский", "🇷🇺", stopwords = setOf("и", "в", "не", "на", "я", "ты", "что", "это", "как", "он", "она", "мы", "вы", "да", "нет", "очень", "но", "сегодня", "мой", "твой", "у", "с")),
    UK("uk", "Українська", "🇺🇦", stopwords = setOf("і", "в", "не", "на", "я", "ти", "що", "це", "як", "він", "вона", "ми", "ви", "так", "ні", "дуже", "але", "сьогодні", "мій", "твій", "у", "з")),
    AR("ar", "العربية", "🇸🇦", stopwords = setOf("في", "من", "على", "أنا", "أنت", "هذا", "هذه", "ما", "لا", "نعم", "و", "مع", "كيف", "هو", "هي", "اليوم")),
    JA("ja", "日本語", "🇯🇵", stopwords = emptySet());

    val locale: Locale get() = Locale.forLanguageTag(code)

    companion object {
        fun of(code: String?): Lang = entries.firstOrNull { it.code == code } ?: DE
        /** Languages with a translated user interface. */
        val uiLanguages = listOf(TR, DE, EN)
    }
}

/** Picks a localized string from a `{"tr": "...", "en": "..."}` map with a sane fallback chain. */
fun Map<String, String>.loc(lang: String): String =
    this[lang] ?: this["en"] ?: this["tr"] ?: values.firstOrNull().orEmpty()

/**
 * Cheap heuristic language check for model output. It never decides *what* is correct,
 * only flags replies written in the wrong language so they can be regenerated.
 */
object LangDetect {
    private val wordRx = Regex("[\\p{L}']+")

    /** Share of tokens that are stopwords of [lang], 0.0..1.0. */
    fun score(text: String, lang: Lang): Double {
        val tokens = wordRx.findAll(text.lowercase()).map { it.value }.toList()
        if (tokens.isEmpty()) return 0.0
        return tokens.count { it in lang.stopwords }.toDouble() / tokens.size
    }

    /** True when the text looks more like [expected] than like [other]. Short or unknown texts pass. */
    fun looksLike(text: String, expected: Lang, other: Lang): Boolean {
        if (expected.stopwords.isEmpty() || text.split(' ').size < 4) return true
        val e = score(text, expected)
        val o = if (other.stopwords.isEmpty()) 0.0 else score(text, other)
        return e >= o
    }
}
