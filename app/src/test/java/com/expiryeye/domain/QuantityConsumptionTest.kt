package com.expiryeye.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityConsumptionTest {
    @Test fun partialUseKeepsTheRemainingQuantity() {
        val result = consumeQuantity(1.0, 0.2)!!
        assertEquals(0.8, result.remaining, 1e-9)
        assertFalse(result.itemFinished)
    }

    @Test fun consumingTheAvailableAmountFinishesTheItem() {
        val result = consumeQuantity(0.75, 0.75)!!
        assertEquals(0.0, result.remaining, 0.0)
        assertTrue(result.itemFinished)
    }

    @Test fun invalidAmountsAreRejected() {
        assertNull(consumeQuantity(1.0, 0.0))
        assertNull(consumeQuantity(1.0, 1.1))
        assertNull(consumeQuantity(Double.NaN, 0.5))
    }
}
