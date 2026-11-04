package com.budgethunter.receipts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class ReceiptFileValidatorTest {

    private val validator = ReceiptFileValidator(maxFileBytes = 64)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D)
    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()

    @Test
    fun `accepts each supported type with its signature and normalises the type`() {
        assertEquals("image/jpeg", validator.validate(b64(jpeg), " IMAGE/JPEG ").second)
        assertEquals("image/png", validator.validate(b64(png), "image/png").second)
        assertEquals("image/webp", validator.validate(b64(webp), "image/webp").second)
        assertEquals("application/pdf", validator.validate(b64("%PDF-1.4".toByteArray()), "application/pdf").second)
    }

    @Test
    fun `a PDF header may come after some leading bytes`() {
        validator.validate(b64("junk\n%PDF-1.4".toByteArray()), "application/pdf")
    }

    @Test
    fun `content that does not match the type is UNSUPPORTED_TYPE`() {
        val e = assertThrows<ReceiptException> { validator.validate(b64(png), "image/jpeg") }
        assertEquals(415, e.status)
        assertEquals("UNSUPPORTED_TYPE", e.code)
    }

    @Test
    fun `an unknown type is UNSUPPORTED_TYPE`() {
        assertEquals(415, assertThrows<ReceiptException> { validator.validate(b64(jpeg), "image/gif") }.status)
    }

    @Test
    fun `the size limit is exact, from the length and padding`() {
        validator.validate(b64(jpeg + ByteArray(60)), "image/jpeg") // 64 bytes, "==" padding
        val e = assertThrows<ReceiptException> { validator.validate(b64(jpeg + ByteArray(61)), "image/jpeg") }
        assertEquals(413, e.status)
        assertEquals("TOO_LARGE", e.code)
    }

    @Test
    fun `invalid, unpadded or empty Base64 is a bad request`() {
        listOf("", "not base64!", "/9j/4A", "/9j/4A=A", "/9j/====").forEach { data ->
            assertThrows<IllegalArgumentException>(data) { validator.validate(data, "image/jpeg") }
        }
    }
}
