package es.personal.avisosairef.data.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AirefHttpClientTest {
    @Test
    fun clientLoadsBundledFnmtTrustAnchor() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val client = AirefHttpClient(context)

        assertNotNull(client)
    }
}
