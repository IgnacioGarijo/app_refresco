package es.personal.avisosairef.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirefPublicationsParserTest {
    private val parser = AirefPublicationsParser()
    private val target = "Experto/a en evaluacion de politicas publicas"

    @Test
    fun optionalSelectionExtractsOnlyConfiguredArea() {
        val result = parser.parse(
            page(targetLinks = listOf("A" to "/docs/a.pdf", "B" to "https://www.boe.es/b.pdf")),
            targetTitle = target
        ) as ParserResult.Success
        assertEquals(listOf("A", "B"), result.publications.map { it.title })
    }

    @Test
    fun defaultModeExtractsWholePage() {
        val result = parser.parse(
            page(targetLinks = listOf("A" to "/docs/a.pdf"), otherLinks = listOf("Otro" to "/docs/otro.pdf"))
        ) as ParserResult.Success
        assertEquals(setOf("A", "Otro"), result.publications.map { it.title }.toSet())
    }

    @Test
    fun newLinkInTargetIsVisible() {
        val old = (parser.parse(page(targetLinks = listOf("A" to "/docs/a.pdf"))) as ParserResult.Success).publications
        val new = (parser.parse(page(targetLinks = listOf("A" to "/docs/a.pdf", "Nuevo" to "/docs/nuevo.pdf"))) as ParserResult.Success).publications
        assertEquals(listOf("https://example.com/docs/nuevo.pdf"), new.filterNot { it.key in old.map { p -> p.key } }.map { it.url })
    }

    @Test
    fun newLinkInOtherSectionIsIgnoredWhenFilterIsEnabled() {
        val result = parser.parse(
            page(otherTitle = "Analista macroeconomico", otherLinks = listOf("Otro nuevo" to "/docs/otro.pdf")),
            targetTitle = target
        ) as ParserResult.Success
        assertEquals(listOf("Base"), result.publications.map { it.title })
    }

    @Test
    fun duplicatedLinksAreRemoved() {
        val result = parser.parse(page(targetLinks = listOf("A" to "/docs/a.pdf", "A duplicado" to "/docs/a.pdf?utm_source=x"))) as ParserResult.Success
        assertEquals(2, result.publications.size)
    }

    @Test
    fun spacesAndCaseDoNotBreakHeadingInFilteredMode() {
        val html = page(title = "  EXPERTO/A   EN EVALUACION DE POLITICAS   PUBLICAS  ")
        val result = parser.parse(html, targetTitle = target) as ParserResult.Success
        assertEquals(1, result.publications.size)
    }

    @Test
    fun relativeUrlBecomesAbsolute() {
        val result = parser.parse(page(targetLinks = listOf("Relativo" to "/wp-content/x.pdf"))) as ParserResult.Success
        assertEquals("https://example.com/wp-content/x.pdf", result.publications.first { it.title == "Relativo" }.url)
    }

    @Test
    fun orderChangesDoNotChangeKeys() {
        val first = (parser.parse(page(targetLinks = listOf("A" to "/a.pdf", "B" to "/b.pdf"))) as ParserResult.Success).publications.map { it.key }.toSet()
        val second = (parser.parse(page(targetLinks = listOf("B" to "/b.pdf", "A" to "/a.pdf"))) as ParserResult.Success).publications.map { it.key }.toSet()
        assertEquals(first, second)
    }

    @Test
    fun missingTargetFailsOnlyWhenFilterIsEnabled() {
        val result = parser.parse("<html><body><main><a href='/a.pdf'>A</a></main></body></html>", targetTitle = target)
        assertTrue(result is ParserResult.Failure && result.reason == ParserFailureReason.SelectionMissing)
    }

    @Test
    fun missingTargetDoesNotFailInWholePageMode() {
        val result = parser.parse("<html><body><main><a href='/a.pdf'>A</a></main></body></html>") as ParserResult.Success
        assertEquals(listOf("A"), result.publications.map { it.title })
    }

    @Test
    fun unexpectedEmptyFailsWhenPreviousStateHadDocuments() {
        val result = parser.parse("<html><body><main></main></body></html>", previousKnownCount = 3)
        assertTrue(result is ParserResult.Failure && result.reason == ParserFailureReason.EmptyUnexpected)
    }

    @Test
    fun moderateStructureChangeUsesFallbackInFilteredMode() {
        val html = """
            <html><body>
            <article>
              <div><span>Experto/a en evaluacion de politicas publicas</span>
                <div class="downloads"><a href="/fallback.pdf">Documento fallback</a></div>
              </div>
            </article>
            </body></html>
        """.trimIndent()
        val result = parser.parse(html, targetTitle = target) as ParserResult.Success
        assertEquals("Documento fallback", result.publications.first().title)
    }

    @Test
    fun cssSelectorLimitsExtraction() {
        val html = """
            <html><body><main>
              <section class="wanted"><a href="/a.pdf">A</a></section>
              <section class="ignored"><a href="/b.pdf">B</a></section>
            </main></body></html>
        """.trimIndent()
        val result = parser.parse(html, cssSelector = ".wanted") as ParserResult.Success
        assertEquals(listOf("A"), result.publications.map { it.title })
    }

    @Test
    fun keywordFilterKeepsMatchingLinksOnly() {
        val html = """
            <html><body><main>
              <a href="/calendario.pdf">Calendario provisional</a>
              <a href="/modelo.pdf">Modelo de solicitud</a>
            </main></body></html>
        """.trimIndent()
        val result = parser.parse(html, includeKeywords = "calendario") as ParserResult.Success
        assertEquals(listOf("Calendario provisional"), result.publications.map { it.title })
    }

    private fun page(
        title: String = "Experto/a en evaluacion de politicas publicas",
        targetLinks: List<Pair<String, String>> = listOf("Base" to "/docs/base.pdf"),
        otherTitle: String = "Experto/a en comunicacion",
        otherLinks: List<Pair<String, String>> = listOf("Otro" to "/docs/otro-base.pdf")
    ): String = """
        <html><body>
          <main><article>
            <section class="elementor-section elementor-top-section">
              <div class="elementor-container">
                <div class="elementor-column">
                  <div class="elementor-widget-wrap">
                    <div class="elementor-widget-text-editor"><div><p>$title</p></div></div>
                    ${targetLinks.joinToString("\n") { "<section><p><a href='${it.second}'>${it.first}</a></p></section>" }}
                    <div class="elementor-widget-divider"></div>
                    <div class="elementor-widget-text-editor"><div><p>$otherTitle</p></div></div>
                    ${otherLinks.joinToString("\n") { "<section><p><a href='${it.second}'>${it.first}</a></p></section>" }}
                  </div>
                </div>
              </div>
            </section>
          </article></main>
        </body></html>
    """.trimIndent()
}
