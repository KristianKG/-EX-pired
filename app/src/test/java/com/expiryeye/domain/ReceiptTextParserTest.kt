package com.expiryeye.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTextParserTest {
    @Test fun extractsProductNamePriceAndSimpleQuantity() {
        val lines = ReceiptTextParser.parse("2x Apples 3.50\nMilk 1,29\nTOTAL 4.79\n")
        assertEquals(2, lines.size)
        assertEquals(ReceiptCandidate("Apples", 3.5, 2.0), lines[0])
        assertEquals(ReceiptCandidate("Milk", 1.29, 1.0), lines[1])
    }

    @Test fun duplicateLinesAreDeduplicatedAndNamesWithoutPriceRemainCandidates() {
        val lines = ReceiptTextParser.parse("Oat milk\nOat milk\nThank you")
        assertEquals(1, lines.size)
        assertTrue(lines.single().price == null)
    }

    @Test fun malformedPaymentAndEmptyLinesAreNotProducts() {
        assertTrue(ReceiptTextParser.parse("\nTOTAL 9.99\nCASH 10.00\n123456789\n").isEmpty())
    }
}
