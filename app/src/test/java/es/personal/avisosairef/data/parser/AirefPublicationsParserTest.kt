package es.personal.avisosairef.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirefPublicationsParserTest {
    private val parser = AirefPublicationsParser()

    @Test
    fun normalPageExtractsOnlyTargetSection() {
        val result = parser.parse(page(targetLinks = listOf("A" to "/docs/a.pdf", "B" to "https://www.boe.es/b.pdf"))) as ParserResult.Success
        assertEquals(listOf("A", "B"), result.publications.map { it.title })
    }

    @Test
    fun newLinkInTargetIsVisible() {
        val old = (parser.parse(page(targetLinks = listOf("A" to "/docs/a.pdf"))) as ParserResult.Success).publications
        val new = (parser.parse(page(targetLinks = listOf("A" to "/docs/a.pdf", "Nuevo" to "/docs/nuevo.pdf"))) as ParserResult.Success).publications
        assertEquals(listOf("https://www.airef.es/docs/nuevo.pdf"), new.filterNot { it.key in old.map { p -> p.key } }.map { it.url })
    }

    @Test
    fun newLinkInOtherSectionIsIgnored() {
        val result = parser.parse(page(otherTitle = "Analista macroeconómico", otherLinks = listOf("Otro nuevo" to "/docs/otro.pdf"))) as ParserResult.Success
        assertEquals(listOf("Base"), result.publications.map { it.title })
    }

    @Test
    fun duplicatedLinksAreRemoved() {
        val result = parser.parse(page(targetLinks = listOf("A" to "/docs/a.pdf", "A duplicado" to "/docs/a.pdf?utm_source=x"))) as ParserResult.Success
        assertEquals(1, result.publications.size)
    }

    @Test
    fun spacesAndCaseDoNotBreakHeading() {
        val html = page(title = "  EXPERTO/A   EN EVALUACIÓN DE POLÍTICAS   PÚBLICAS  ")
        val result = parser.parse(html) as ParserResult.Success
        assertEquals(1, result.publications.size)
    }

    @Test
    fun relativeUrlBecomesAbsolute() {
        val result = parser.parse(page(targetLinks = listOf("Relativo" to "/wp-content/x.pdf"))) as ParserResult.Success
        assertEquals("https://www.airef.es/wp-content/x.pdf", result.publications.first().url)
    }

    @Test
    fun orderChangesDoNotChangeKeys() {
        val first = (parser.parse(page(targetLinks = listOf("A" to "/a.pdf", "B" to "/b.pdf"))) as ParserResult.Success).publications.map { it.key }.toSet()
        val second = (parser.parse(page(targetLinks = listOf("B" to "/b.pdf", "A" to "/a.pdf"))) as ParserResult.Success).publications.map { it.key }.toSet()
        assertEquals(first, second)
    }

    @Test
    fun missingTargetFails() {
        val result = parser.parse("<html><body><p>Otro puesto</p><a href='/a.pdf'>A</a></body></html>")
        assertTrue(result is ParserResult.Failure && result.reason == ParserFailureReason.TargetSectionMissing)
    }

    @Test
    fun unexpectedEmptyFailsWhenPreviousStateHadDocuments() {
        val result = parser.parse(page(targetLinks = emptyList()), previousKnownCount = 3)
        assertTrue(result is ParserResult.Failure && result.reason == ParserFailureReason.EmptyUnexpected)
    }

    @Test
    fun moderateStructureChangeUsesFallback() {
        val html = """
            <html><body>
            <article>
              <div><span>Experto/a en evaluación de políticas públicas</span>
                <div class="downloads"><a href="/fallback.pdf">Documento fallback</a></div>
              </div>
            </article>
            </body></html>
        """.trimIndent()
        val result = parser.parse(html) as ParserResult.Success
        assertEquals("Documento fallback", result.publications.first().title)
    }

    private fun page(
        title: String = "Experto/a en evaluación de políticas públicas",
        targetLinks: List<Pair<String, String>> = listOf("Base" to "/docs/base.pdf"),
        otherTitle: String = "Experto/a en comunicación",
        otherLinks: List<Pair<String, String>> = listOf("Otro" to "/docs/otro-base.pdf")
    ): String = """
        <html><body>
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
        </body></html>
    """.trimIndent()
}
