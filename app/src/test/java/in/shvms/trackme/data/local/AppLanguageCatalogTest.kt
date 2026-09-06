package `in`.shvms.trackme.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageCatalogTest {
    @Test fun `catalog contains the seven shipping languages in picker order`() {
        assertEquals(listOf("en", "es", "fr", "de", "hi", "ja", "zh"), AppLanguageCatalog.supportedCodes)
    }

    @Test fun `regional and script tags reduce to a supported base language`() {
        assertEquals("es", AppLanguageCatalog.normalize("es-MX"))
        assertEquals("zh", AppLanguageCatalog.normalize("zh-Hans-SG"))
        assertEquals("en", AppLanguageCatalog.normalize("en-US"))
    }

    @Test fun `unsupported or malformed tags do not silently enter the catalog`() {
        assertNull(AppLanguageCatalog.normalize("pt-BR"))
        assertNull(AppLanguageCatalog.normalize(""))
        assertNull(AppLanguageCatalog.normalize(null))
    }
}
