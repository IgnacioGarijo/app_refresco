package es.personal.avisosairef.data.parser

import es.personal.avisosairef.Constants
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

class AirefPublicationsParser {
    fun parse(
        html: String,
        pageUrl: String = Constants.DefaultPageUrl,
        previousKnownCount: Int = 0,
        targetTitle: String? = null
    ): ParserResult {
        val baseUrl = baseOrigin(pageUrl)
        val document = Jsoup.parse(html, baseUrl)
        val links = if (targetTitle.isNullOrBlank()) {
            extractMainContent(document)
        } else {
            val targetComparable = TextNormalizer.comparable(targetTitle)
            val targetElement = findTargetElement(document, targetComparable) ?: return ParserResult.Failure(
                ParserFailureReason.TargetSectionMissing,
                "No se encontro el apartado opcional configurado."
            )
            extractPrimary(targetElement, targetComparable) ?: extractFallback(targetElement)
        }

        val publications = links
            .mapNotNull { linkToPublication(it, baseUrl) }
            .distinctBy { it.key }

        if (publications.isEmpty() && previousKnownCount > 0) {
            return ParserResult.Failure(ParserFailureReason.EmptyUnexpected, "No se encontraron enlaces de forma inesperada.")
        }
        if (previousKnownCount >= 4 && publications.size < previousKnownCount / 2) {
            return ParserResult.Failure(
                ParserFailureReason.ImplausibleResult,
                "Caida brusca de enlaces: ${publications.size} frente a $previousKnownCount conocidos."
            )
        }
        return ParserResult.Success(publications)
    }

    private fun findTargetElement(root: Element, targetComparable: String): Element? =
        root.select("p,h1,h2,h3,h4,h5,h6,div,span,strong")
            .firstOrNull { TextNormalizer.comparable(it.ownText()) == targetComparable }
            ?: root.getAllElements().firstOrNull { TextNormalizer.comparable(it.text()) == targetComparable }

    private fun extractMainContent(document: Element): List<Element> {
        val content = document.selectFirst("main article")
            ?: document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.selectFirst("body")
            ?: document
        return content.select("a[href]")
            .filter { isReasonablePublicationLink(it) }
    }

    private fun isReasonablePublicationLink(link: Element): Boolean {
        val href = link.attr("href").lowercase(Locale.ROOT)
        val title = TextNormalizer.comparable(link.text())
        return href.endsWith(".pdf") ||
            href.contains("/wp-content/uploads/") ||
            href.contains("boe.es/") ||
            title.contains("resolucion") ||
            title.contains("listado") ||
            title.contains("nota") ||
            title.contains("calendario") ||
            title.contains("cronograma") ||
            title.contains("plantilla") ||
            title.contains("modelo")
    }

    private fun extractPrimary(targetElement: Element, targetComparable: String): List<Element>? {
        val container = targetElement.parents().firstOrNull { it.hasClass("elementor-widget-wrap") }
            ?: targetElement.parents().firstOrNull { it.tagName() == "section" }
            ?: return null

        val links = mutableListOf<Element>()
        var afterTarget = false
        for (child in container.children()) {
            if (!afterTarget) {
                afterTarget = child == targetElement || child.hasSameDescendant(targetElement)
                if (afterTarget) links += child.select("a[href]")
                continue
            }
            if (containsNextProfessionalHeading(child, targetComparable)) break
            links += child.select("a[href]")
        }
        return links
    }

    private fun extractFallback(targetElement: Element): List<Element> {
        val container = targetElement.parents().firstOrNull { parent ->
            parent.text().contains(targetElement.text(), ignoreCase = true) && parent.select("a[href]").isNotEmpty()
        }
        return container?.select("a[href]") ?: emptyList()
    }

    private fun linkToPublication(link: Element, baseUrl: String): Publicacion? {
        val title = TextNormalizer.visible(link.text().ifBlank { link.attr("title") })
        if (title.isBlank()) return null
        val url = UrlNormalizer.normalize(link.attr("href"), baseUrl) ?: return null
        val normalizedTitle = TextNormalizer.comparable(title)
        val type = inferType(url, normalizedTitle)
        return Publicacion(
            title = title,
            normalizedTitle = normalizedTitle,
            url = url,
            key = url.ifBlank { "title:$normalizedTitle" },
            type = type,
            detectedDate = detectDate(title)
        )
    }

    private fun containsNextProfessionalHeading(element: Element, targetComparable: String): Boolean {
        val ownTexts = element.select("p,h1,h2,h3,h4,h5,h6,strong,div").map { TextNormalizer.comparable(it.ownText()) }
        return ownTexts.any { text ->
            text.isNotBlank() &&
                text != targetComparable &&
                !text.startsWith("cuestionarios") &&
                !text.startsWith("modelo ") &&
                likelyJobHeading(text)
        }
    }

    private fun likelyJobHeading(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("experto/a") ||
            lower.contains("tecnico/a") ||
            lower.contains("analista") ||
            lower.contains("comunicacion") ||
            lower.contains("titulado superior") ||
            lower.contains("administrativo")
    }

    private fun Element.hasSameDescendant(descendant: Element): Boolean =
        this == descendant || getAllElements().any { it == descendant }

    private fun inferType(url: String, title: String): String? {
        val lowerUrl = url.lowercase(Locale.ROOT)
        return when {
            lowerUrl.endsWith(".pdf") -> "PDF"
            title.contains("boe") || lowerUrl.contains("boe.es") -> "BOE"
            title.contains("calendario") -> "Calendario"
            title.contains("nota") -> "Nota"
            title.contains("listado") -> "Listado"
            title.contains("resolucion") -> "Resolucion"
            else -> null
        }
    }

    private fun detectDate(title: String): String? {
        val normalized = TextNormalizer.visible(title)
        Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b").find(normalized)?.let { return it.value }
        Regex("\\b\\d{1,2}\\s+de\\s+[A-Za-zA-Za-z]+\\s+de\\s+\\d{4}\\b").find(normalized)?.let { return it.value }
        return null
    }

    private fun baseOrigin(pageUrl: String): String =
        runCatching {
            val uri = java.net.URI(pageUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(Constants.DefaultPageUrl)
}
