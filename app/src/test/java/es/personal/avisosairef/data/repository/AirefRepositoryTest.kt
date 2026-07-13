package es.personal.avisosairef.data.repository

import es.personal.avisosairef.data.network.AirefFetcher
import es.personal.avisosairef.data.network.FetchError
import es.personal.avisosairef.data.network.FetchResult
import es.personal.avisosairef.data.storage.AppState
import es.personal.avisosairef.data.storage.StateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AirefRepositoryTest {
    @Test
    fun firstSyncCreatesReferenceWithoutNewPublications() = runTest {
        val repository = repositoryWith(FakeFetcher(FetchResult.Success(html("A", "/a.pdf"), null, null)))
        val outcome = repository.checkNow()
        assertTrue(outcome.firstReferenceCreated)
        assertTrue(outcome.newPublications.isEmpty())
        assertEquals(2, repositoryState(repository).selectedMonitor.knownPublications.size)
    }

    @Test
    fun targetNewLinkProducesNovelty() = runTest {
        val fetcher = QueueFetcher(
            FetchResult.Success(html("A", "/a.pdf"), null, null),
            FetchResult.Success(html("A", "/a.pdf", "Nuevo", "/nuevo.pdf"), null, null)
        )
        val repository = repositoryWith(fetcher)
        repository.checkNow()
        val outcome = repository.checkNow()
        assertEquals(1, outcome.newPublications.size)
        assertEquals("Nuevo", outcome.newPublications.first().title)
    }

    @Test
    fun otherSectionNewLinkProducesNoveltyInWholePageMode() = runTest {
        val fetcher = QueueFetcher(
            FetchResult.Success(html("A", "/a.pdf"), null, null),
            FetchResult.Success(html("A", "/a.pdf", otherExtra = "<section><a href='/otro-nuevo.pdf'>Otro nuevo</a></section>"), null, null)
        )
        val repository = repositoryWith(fetcher)
        repository.checkNow()
        val outcome = repository.checkNow()
        assertEquals(listOf("Otro nuevo"), outcome.newPublications.map { it.title })
    }

    @Test
    fun networkErrorDoesNotClearPreviousState() = runTest {
        val fetcher = QueueFetcher(
            FetchResult.Success(html("A", "/a.pdf"), null, null),
            FetchResult.Failure(FetchError.Network("timeout"))
        )
        val repository = repositoryWith(fetcher)
        repository.checkNow()
        repository.checkNow()
        assertEquals(2, repositoryState(repository).selectedMonitor.knownPublications.size)
    }

    @Test
    fun unexpectedEmptyListIsNotAccepted() = runTest {
        val fetcher = QueueFetcher(
            FetchResult.Success(html("A", "/a.pdf"), null, null),
            FetchResult.Success(htmlNoLinks(), null, null)
        )
        val repository = repositoryWith(fetcher)
        repository.checkNow()
        val outcome = repository.checkNow()
        assertEquals(CheckStatus.Error, outcome.status)
        assertEquals(2, repositoryState(repository).selectedMonitor.knownPublications.size)
    }

    @Test
    fun canAddSecondMonitorAndCheckIt() = runTest {
        val repository = repositoryWith(FakeFetcher(FetchResult.Success("<html><body><main><a href='/x.pdf'>X</a></main></body></html>", null, null)))
        val id = repository.upsertMonitor(
            id = null,
            name = "Otra pagina",
            folder = "Pruebas",
            url = "https://example.com",
            intervalMinutes = 60,
            enabled = true,
            cssSelector = "",
            includeKeywords = ""
        )

        val outcome = repository.checkNow(id)

        assertTrue(outcome.firstReferenceCreated)
        assertEquals(2, repositoryState(repository).monitors.size)
        assertEquals("Otra pagina", repositoryState(repository).selectedMonitor.name)
        assertEquals(1, repositoryState(repository).selectedMonitor.knownPublications.size)
    }

    @Test
    fun masterSwitchRestoresPreviouslyEnabledPages() = runTest {
        val repository = repositoryWith(FakeFetcher(FetchResult.Success(html("A", "/a.pdf"), null, null)))
        val secondId = repository.upsertMonitor(
            id = null,
            name = "Pausada",
            folder = "Pruebas",
            url = "https://example.com/paused",
            intervalMinutes = 30,
            enabled = false,
            cssSelector = "",
            includeKeywords = ""
        )
        repository.setMonitoringEnabled(false)
        assertTrue(repositoryState(repository).monitors.none { it.enabled })

        repository.setMonitoringEnabled(true)
        val restored = repositoryState(repository)
        assertTrue(restored.monitors.first { it.id != secondId }.enabled)
        assertTrue(!restored.monitors.first { it.id == secondId }.enabled)
    }

    private fun repositoryWith(fetcher: AirefFetcher): AirefRepository {
        return AirefRepository(MemoryStore(), fetcher, clock = { 1_000L })
    }

    private suspend fun repositoryState(repository: AirefRepository) = repository.state.first()

    private fun html(title: String, url: String, title2: String? = null, url2: String? = null, otherExtra: String = ""): String {
        val second = if (title2 != null && url2 != null) "<section><a href='$url2'>$title2</a></section>" else ""
        return """
            <html><body><section><div><div><div class="elementor-widget-wrap">
            <div><p>Experto/a en evaluación de políticas públicas</p></div>
            <section><a href="$url">$title</a></section>
            $second
            <div><p>Experto/a en comunicación</p></div>
            <section><a href="/otro.pdf">Otro</a></section>
            $otherExtra
            </div></div></div></section></body></html>
        """.trimIndent()
    }

    private fun htmlNoLinks(): String = """
        <html><body><section><div class="elementor-widget-wrap">
        <div><p>Experto/a en evaluación de políticas públicas</p></div>
        <div><p>Experto/a en comunicación</p></div>
        </div></section></body></html>
    """.trimIndent()

    private class FakeFetcher(private val result: FetchResult) : AirefFetcher {
        override suspend fun fetch(url: String, eTag: String?, lastModified: String?) = result
    }

    private class QueueFetcher(vararg results: FetchResult) : AirefFetcher {
        private val queue = ArrayDeque(results.toList())
        override suspend fun fetch(url: String, eTag: String?, lastModified: String?) = queue.removeFirst()
    }

    private class MemoryStore : StateStore {
        private val flow = MutableStateFlow(AppState())
        override val state = flow
        override suspend fun current(): AppState = flow.value
        override suspend fun update(transform: (AppState) -> AppState): AppState {
            flow.value = transform(flow.value)
            return flow.value
        }
        override suspend fun resetReference(monitorId: String) {
            update {
                it.copy(
                    monitors = it.monitors.map { monitor ->
                        if (monitor.id != monitorId) {
                            monitor
                        } else {
                            monitor.copy(
                                knownPublications = emptyList(),
                                unseenPublications = emptyList(),
                                recentPublications = emptyList(),
                                eTag = null,
                                lastModified = null,
                                lastChangeAtMillis = null,
                                lastError = null,
                                lastResult = "Referencia pendiente"
                            )
                        }
                    }
                )
            }
        }
    }
}
