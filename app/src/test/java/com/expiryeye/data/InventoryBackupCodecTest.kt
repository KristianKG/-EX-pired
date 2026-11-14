package com.expiryeye.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryBackupCodecTest {
    @Test fun jsonBackupPreservesInventoryAndShoppingData() {
        val original = InventoryBackup(
            foodItems = listOf(FoodItem(id = 4, name = "Oat milk", category = "Dairy", quantity = 1.5, unit = "L", expiresAt = 1_900_000_000_000, location = "Fridge", price = 2.5, brand = "Oaty", barcode = "5901234123457")),
            shoppingItems = listOf(ShoppingEntry(id = 9, name = "Apples", checked = true))
        )
        val restored = InventoryBackupCodec.decode(InventoryBackupCodec.encode(original))
        assertEquals(original.foodItems, restored.foodItems)
        assertEquals(original.shoppingItems, restored.shoppingItems)
    }

    @Test fun csvQuotesCommasQuotesAndLineBreaks() {
        val csv = InventoryBackupCodec.toCsv(listOf(FoodItem(name = "Pear, \"ripe\"\nlocal", expiresAt = 1_900_000_000_000)))
        assertTrue(csv.contains("\"Pear, \"\"ripe\"\"\nlocal\""))
    }

    @Test fun invalidBackupIsRejectedBeforeRestore() {
        val invalid = InventoryBackup(foodItems = listOf(FoodItem(name = " ", expiresAt = 1_900_000_000_000)), shoppingItems = emptyList())
        assertThrows(IllegalArgumentException::class.java) { InventoryBackupCodec.decode(InventoryBackupCodec.encode(invalid)) }
    }
}
