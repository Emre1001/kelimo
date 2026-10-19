package app.kelimo.ui

import androidx.compose.runtime.staticCompositionLocalOf

typealias T = (String) -> String

val LocalT = staticCompositionLocalOf<T> { { it } }

/** UI strings. Missing keys fall back to English, then to the key itself. */
object I18n {
    fun table(lang: String): T {
        val primary = when (lang) { "tr" -> Strings.tr; "de" -> Strings.de; else -> Strings.en }
        return { key -> primary[key] ?: Strings.en[key] ?: key }
    }
}
