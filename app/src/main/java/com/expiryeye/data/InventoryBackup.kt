package com.expiryeye.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class InventoryBackup(
    val formatVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val foodItems: List<FoodItem>,
    val shoppingItems: List<ShoppingEntry>
)

object InventoryBackupCodec {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    fun encode(backup: InventoryBackup): String = json.encodeToString(backup)
    fun decode(input: String): InventoryBackup {
        require(input.length <= 10_000_000) { "Backup file is too large." }
        val value = json.decodeFromString<InventoryBackup>(input)
        require(value.formatVersion == 1) { "This backup version is not supported." }
        require(value.foodItems.size <= 100_000 && value.shoppingItems.size <= 100_000) { "The backup contains too many records." }
        require(value.foodItems.all { item ->
            item.id >= 0 && item.name.isNotBlank() && item.category.isNotBlank() && item.quantity.isFinite() && item.quantity > 0 &&
                item.expiresAt > 0 && item.createdAt > 0 && (item.price == null || item.price.isFinite() && item.price >= 0) &&
                (item.consumedAt == null || item.consumedAt > 0) &&
                (item.purchasedAt == null || item.purchasedAt > 0) && (item.openedAt == null || item.openedAt > 0) &&
                item.updatedAt >= 0 && (item.discardedAt == null || item.discardedAt > 0) &&
                (item.discardReason == null || item.discardReason in setOf("Expired", "Spoiled", "Unwanted", "Other"))
        }) { "The backup has invalid food records." }
        require(value.shoppingItems.all { it.id >= 0 && it.name.isNotBlank() && it.quantity.isNotBlank() }) { "The backup has invalid shopping records." }
        return value
    }

    fun toCsv(items: List<FoodItem>): String = buildString {
        appendLine("name,brand,category,quantity,unit,expires_on,purchased_at,opened_at,location,price,barcode,notes,created_at,updated_at,consumed_at,discarded_at,discard_reason")
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        items.forEach { item ->
            val date = Instant.ofEpochMilli(item.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter)
            fun dateValue(value: Long?): String = value?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter) }.orEmpty()
            appendLine(listOf(item.name, item.brand, item.category, item.quantity.toString(), item.unit, date, dateValue(item.purchasedAt), dateValue(item.openedAt), item.location, item.price?.toString().orEmpty(), item.barcode, item.notes, item.createdAt.toString(), item.updatedAt.toString(), item.consumedAt?.toString().orEmpty(), item.discardedAt?.toString().orEmpty(), item.discardReason.orEmpty()).joinToString(",") { csv(it) })
        }
    }

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
}
