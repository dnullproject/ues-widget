package systems.dnull.ueswidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStyleTest {
    @Test fun hex() {
        assertEquals("#282A36", normalizeHex("282a36"))
        assertEquals("#BD93F9", normalizeHex(" #bd93f9 "))
        assertNull(normalizeHex("#12345"))
        assertNull(normalizeHex("#GGGGGG"))
        assertEquals(0xFF282A36.toInt(), parseHexOrNull("#282A36"))
    }

    @Test fun alpha() {
        assertEquals(0x00282A36, withAlpha(0xFF282A36.toInt(), 0))
        assertEquals(0xFF282A36.toInt(), withAlpha(0xFF282A36.toInt(), 100))
        assertEquals(0x7F282A36, withAlpha(0xFF282A36.toInt(), 50))
    }
}
