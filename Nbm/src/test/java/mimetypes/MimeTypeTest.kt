package mimetypes

import utils.KotlinTestCase

class MimeTypeTest : KotlinTestCase("MIME type test", "mimetypes") {

    fun testKotlinFileMimeType() {
        val file = dir.getFileObject("sample.kt")
        assertNotNull(file)
        assertEquals("text/x-kotlin", file!!.mimeType)
    }
}
