package es.personal.avisosairef.data.parser

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    fun visible(text: String): String = text
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    fun comparable(text: String): String {
        val normalized = Normalizer.normalize(visible(text), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
        return normalized
            .replace('º', 'o')
            .replace('ª', 'a')
            .replace(Regex("[“”\"'`´]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
