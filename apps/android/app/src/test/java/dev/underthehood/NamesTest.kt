package dev.underthehood
import org.junit.Assert.*
import org.junit.Test
class NamesTest {
    @Test fun unicodeNamesAreAllowed() { HoodModel.validateName("大学 📁 résumé.txt") }
    @Test fun traversalAndControlCharactersAreRejected() {
        for(name in listOf("", "..", ".", "a/b", "a\\b", "a\u0000b")) {
            try { HoodModel.validateName(name); fail("accepted unsafe name") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun quotaFormattingDoesNotOverflow() { assertEquals("1.0 TB",formatBytes(1099511627776)); assertEquals("0 B",formatBytes(0)) }
}
