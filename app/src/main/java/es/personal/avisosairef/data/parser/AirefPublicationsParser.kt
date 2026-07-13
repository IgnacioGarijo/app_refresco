package es.personal.avisosairef.data.parser

import es.personal.avisosairef.Constants
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.util.Locale

class AirefPublicationsParser {
    fun parse(
        html: String,
        pageUrl: String = Constants.DefaultPageUrl,
        previousKnownCount: Int = 0,
        targetTitle: String? = null,
        cssSelector: String = "",
        includeKeywords: String = ""
    ): ParserResult {
        val baseUrl = baseOrigin(pageUrl)
        val document = Jsoup.parse(html, baseUrl)
        val content = if (cssSelector.isNotBlank()) {
            val selected = document.select(cssSelector)
            if (selected.isEmpty()) {
                return ParserResult.Failure(ParserFailureReason.SelectionMissing, "No se encontro el selector CSS configurado.")
            }
            selected
        } else if (targetTitle.isNullOrBlank()) {
            listOf(extractMainContent(document))
        } else {
            val targetComparable = TextNormalizer.comparable(targetTitle)
            val targetElement = findTargetElement(document, targetComparable) ?: return ParserResult.Failure(
                ParserFailureReason.SelectionMissing,
                "No se encontro la zona configurada."
            )
            listOf(extractPrimaryContainer(targetElement, targetComparable) ?: extractFallbackContainer(targetElement))
        }

        val allLinks = content.flatMap { it.select("a[href]") }
        val publications = allLinks
            .mapNotNull { linkToPublication(it, baseUrl) }
            .filter { matchesKeywords(it, includeKeywords) }
            .distinctBy { it.key }

        val snapshot = buildSnapshot(document, content, baseUrl)

        if (previousKnownCount > 0 && publications.isEmpty()) {
            return ParserResult.Failure(
                ParserFailureReason.EmptyUnexpected,
                "No se encontraron enlaces aunque la referencia anterior tenia documentos."
            )
        }
        if (previousKnownCount >= 4 && publications.size < previousKnownCount / 2 && snapshot.visibleTextLength < 40) {
            return ParserResult.Failure(
                ParserFailureReason.ImplausibleResult,
                "Caida brusca de contenido: pocos enlaces y texto visible muy escaso."
            )
        }
        return ParserResult.Success(publications, snapshot)
    }

    private fun findTargetElement(root: Element, targetComparable: String): Element? =
        root.select("p,h1,h2,h3,h4,h5,h6,div,span,strong")
            .firstOrNull { TextNormalizer.comparable(it.ownText()) == targetComparable }
            ?: root.getAllElements().firstOrNull { TextNormalizer.comparable(it.text()) == targetComparable }

    private fun extractMainContent(document: Element): Element =
        document.selectFirst("main article")
            ?: document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.selectFirst("body")
            ?: document

    private fun matchesKeywords(publication: Publicacion, keywords: String): Boolean {
        val terms = keywords.split(",", "\n", ";")
            .map { TextNormalizer.comparable(it) }
            .filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val haystack = TextNormalizer.comparable("${publication.title} ${publication.url}")
        return terms.any { haystack.contains(it) }
    }

    private fun extractPrimaryContainer(targetElement: Element, targetComparable: String): Element? {
        val container = targetElement.parents().firstOrNull { it.hasClass("elementor-widget-wrap") }
            ?: targetElement.parents().firstOrNull { it.tagName() == "section" }
            ?: return null

        val wrapper = Element("section")
        var afterTarget = false
        for (child in container.children()) {
            if (!afterTarget) {
                afterTarget = child == targetElement || child.hasSameDescendant(targetElement)
                if (afterTarget) wrapper.appendChild(child.clone())
                continue
            }
            if (containsNextProfessionalHeading(child, targetComparable)) break
            wrapper.appendChild(child.clone())
        }
        return wrapper
    }

    private fun extractFallbackContainer(targetElement: Element): Element {
        val container = targetElement.parents().firstOrNull { parent ->
            parent.text().contains(targetElement.text(), ignoreCase = true) && parent.select("a[href]").isNotEmpty()
        }
        return container ?: targetElement
    }

    private fun buildSnapshot(document: Document, content: List<Element>, baseUrl: String): PageSnapshot {
        val cleaned = content.map { cleanForSnapshot(it) }
        val visibleText = TextNormalizer.visible(cleaned.joinToString(" ") { it.text() })
        val domSignature = cleaned.joinToString("\n") { elementSignature(it) }
        val links = content.flatMap { it.select("a[href]") }
            .mapNotNull { link ->
                val url = UrlNormalizer.normalize(link.attr("href"), baseUrl) ?: return@mapNotNull null
                "${TextNormalizer.visible(link.text())}|$url"
            }
            .distinct()
            .sorted()
        val metadata = extractMetadata(document, baseUrl)
        val images = extractImages(document, content, baseUrl)
        val dynamicHints = detectDynamicHints(document, visibleText, metadata)

        return PageSnapshot(
            textHash = sha256(visibleText),
            domHash = sha256(domSignature),
            linksHash = sha256(links.joinToString("\n")),
            metadataHash = sha256(metadata.joinToString("\n")),
            imagesHash = sha256(images.joinToString("\n")),
            combinedHash = sha256(listOf(visibleText, domSignature, links.joinToString("\n"), metadata.joinToString("\n"), images.joinToString("\n")).joinToString("\n---\n")),
            visibleTextLength = visibleText.length,
            linkCount = links.size,
            imageCount = images.size,
            metadataCount = metadata.size,
            dynamicHints = dynamicHints
        )
    }

    private fun cleanForSnapshot(element: Element): Element {
        val clone = element.clone()
        clone.select("script,style,noscript,svg,iframe").remove()
        clone.getAllElements().forEach {
            it.clearAttributes()
        }
        return clone
    }

    private fun elementSignature(element: Element): String =
        element.getAllElements()
            .filter { it.tagName() !in setOf("#root") }
            .joinToString(" ") { it.tagName() }

    private fun extractMetadata(document: Document, baseUrl: String): List<String> {
        val meta = document.select("title,meta[name],meta[property],link[rel=icon],link[rel='shortcut icon'],link[rel='apple-touch-icon']")
            .mapNotNull { element ->
                when (element.tagName()) {
                    "title" -> "title=${TextNormalizer.visible(element.text())}"
                    "meta" -> {
                        val key = element.attr("name").ifBlank { element.attr("property") }.lowercase(Locale.ROOT)
                        val value = element.attr("content")
                        if (key in relevantMetaKeys && value.isNotBlank()) "$key=${normalizeMetaValue(key, value, baseUrl)}" else null
                    }
                    "link" -> {
                        val rel = element.attr("rel").lowercase(Locale.ROOT)
                        val href = UrlNormalizer.normalize(element.attr("href"), baseUrl)
                        if (href != null) "$rel=$href" else null
                    }
                    else -> null
                }
            }
        return meta.distinct().sorted()
    }

    private fun normalizeMetaValue(key: String, value: String, baseUrl: String): String =
        if (key.contains("image") || key.contains("icon")) {
            UrlNormalizer.normalize(value, baseUrl) ?: value.trim()
        } else {
            TextNormalizer.visible(value)
        }

    private fun extractImages(document: Document, content: List<Element>, baseUrl: String): List<String> {
        val fromContent = content.flatMap { it.select("img[src],img[srcset],source[srcset]") }
            .mapNotNull { imageElementSignature(it, baseUrl) }
        val fromMeta = document.select("meta[property=og:image],meta[name=twitter:image],meta[name=twitter:image:src]")
            .mapNotNull { UrlNormalizer.normalize(it.attr("content"), baseUrl) }
            .map { "meta-image|$it" }
        return (fromContent + fromMeta).distinct().sorted()
    }

    private fun imageElementSignature(element: Element, baseUrl: String): String? {
        val raw = element.attr("src").ifBlank { element.attr("srcset").substringBefore(" ") }
        val url = UrlNormalizer.normalize(raw, baseUrl) ?: return null
        val alt = TextNormalizer.visible(element.attr("alt"))
        return "$alt|$url"
    }

    private fun detectDynamicHints(document: Document, visibleText: String, metadata: List<String>): List<String> {
        val hints = mutableListOf<String>()
        val scripts = document.select("script").size
        val rootLike = document.select("#root,#react-root,#__next,[data-reactroot]").isNotEmpty()
        if (scripts >= 8 && visibleText.length < 500) hints += "La pagina parece depender de JavaScript para parte del contenido."
        if (rootLike) hints += "Se detecto contenedor de aplicacion dinamica."
        if (metadata.any { it.startsWith("og:description=") || it.startsWith("twitter:description=") }) {
            hints += "Se vigilan metadatos sociales de descripcion."
        }
        if (metadata.any { it.contains("image=") }) {
            hints += "Se vigilan URLs de imagen/avatar en metadatos."
        }
        return hints.distinct()
    }

    private fun linkToPublication(link: Element, baseUrl: String): Publicacion? {
        val title = TextNormalizer.visible(link.text().ifBlank { link.attr("title") }.ifBlank { link.attr("aria-label") })
        val url = UrlNormalizer.normalize(link.attr("href"), baseUrl) ?: return null
        val displayTitle = title.ifBlank { url.substringAfterLast('/').ifBlank { url } }
        val normalizedTitle = TextNormalizer.comparable(displayTitle)
        val type = inferType(url, normalizedTitle)
        return Publicacion(
            title = displayTitle,
            normalizedTitle = normalizedTitle,
            url = url,
            key = url.ifBlank { "title:$normalizedTitle" },
            type = type,
            detectedDate = detectDate(displayTitle)
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
            lowerUrl.endsWith(".r") -> "R"
            lowerUrl.endsWith(".html") || lowerUrl.endsWith(".htm") -> "HTML"
            title.contains("boe") || lowerUrl.contains("boe.es") -> "BOE"
            title.contains("calendario") -> "Calendario"
            title.contains("nota") -> "Nota"
            title.contains("listado") -> "Listado"
            title.contains("resolucion") -> "Resolucion"
            else -> "Enlace"
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

    private fun sha256(value: String): String {
        if (value.isBlank()) return ""
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val relevantMetaKeys = setOf(
            "description",
            "og:title",
            "og:description",
            "og:image",
            "twitter:title",
            "twitter:description",
            "twitter:image",
            "twitter:image:src",
            "profile:username"
        )
    }
}
