package ai.anywear.mvp

import ai.anywear.mvp.data.BackendConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendConfigTest {
    @Test
    fun `base url has one trailing slash`() {
        assertEquals("https://example.com/", BackendConfig.normalize("https://example.com"))
        assertEquals("https://example.com/", BackendConfig.normalize("https://example.com/"))
    }
}
