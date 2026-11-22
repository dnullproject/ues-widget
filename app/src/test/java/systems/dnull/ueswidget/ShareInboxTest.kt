package systems.dnull.ueswidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareInboxTest {
    @Test
    fun combineTextAndSubject() {
        assertEquals("Title\nhttps://example.com", combineShareText("Title", "https://example.com"))
        assertEquals("Title\nhttps://example.com", combineShareText("Title", "Title\nhttps://example.com"))
        assertEquals("Only text", combineShareText(null, "  Only text  "))
        assertEquals("Only subject", combineShareText("Only subject", "   "))
        assertEquals("", combineShareText(null, null))
    }

    @Test
    fun resolveTextVsImagePath() {
        val textPayload = resolveSharePayload(
            action = "android.intent.action.SEND",
            mimeType = "text/plain",
            subject = "Article",
            text = "https://example.com",
            streamUris = emptyList(),
        )
        assertEquals(SharePayload.Text("Article\nhttps://example.com"), textPayload)

        val singleImage = resolveSharePayload(
            action = "android.intent.action.SEND",
            mimeType = "image/jpeg",
            subject = null,
            text = "receipt",
            streamUris = listOf("content://media/1"),
        )
        assertEquals(SharePayload.Images(listOf("content://media/1"), "receipt"), singleImage)

        val multiImage = resolveSharePayload(
            action = "android.intent.action.SEND_MULTIPLE",
            mimeType = "image/*",
            subject = null,
            text = null,
            streamUris = listOf("content://media/1", "content://media/2"),
        )
        assertEquals(SharePayload.Images(listOf("content://media/1", "content://media/2"), ""), multiImage)

        assertNull(
            resolveSharePayload(
                action = "android.intent.action.VIEW",
                mimeType = "text/plain",
                subject = null,
                text = "ignored",
                streamUris = emptyList(),
            ),
        )
        assertNull(
            resolveSharePayload(
                action = "android.intent.action.SEND",
                mimeType = "text/plain",
                subject = "  ",
                text = "",
                streamUris = emptyList(),
            ),
        )
    }

    @Test
    fun jsonBody() {
        val obj = JSONObject(buildInboxJson("hello world"))
        assertEquals("hello world", obj.getString("text"))
        assertEquals("share", obj.getString("source"))
    }

    @Test
    fun multipartBody() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val req = buildInboxMultipart(
            text = "photo note",
            source = "share",
            imageBytes = bytes,
            filename = "image.jpg",
            mimeType = "image/jpeg",
            boundary = "test-boundary",
        )
        assertEquals("multipart/form-data; boundary=test-boundary", req.contentType)
        val bodyStr = req.body.toString(Charsets.ISO_8859_1)
        assertTrue(bodyStr.contains("--test-boundary\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\nphoto note\r\n"))
        assertTrue(bodyStr.contains("--test-boundary\r\nContent-Disposition: form-data; name=\"source\"\r\n\r\nshare\r\n"))
        assertTrue(bodyStr.contains("Content-Disposition: form-data; name=\"file\"; filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        assertTrue(bodyStr.endsWith("\r\n--test-boundary--\r\n"))
    }

    @Test
    fun scaledDimensionsBounds() {
        assertEquals(1024 to 768, scaledDimensions(1024, 768, 2048))
        assertEquals(2048 to 1024, scaledDimensions(4096, 2048, 2048))
        assertEquals(1024 to 2048, scaledDimensions(1500, 3000, 2048))
        assertEquals(0 to 0, scaledDimensions(0, 0, 2048))
    }
}
