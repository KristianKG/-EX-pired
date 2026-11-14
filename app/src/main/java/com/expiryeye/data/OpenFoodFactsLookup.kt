package com.expiryeye.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ProductLookup(
    val barcode: String,
    val name: String? = null,
    val brand: String? = null,
    val category: String = "Other",
    val message: String? = null
)

/** Sends only the scanned barcode to Open Food Facts; no inventory information is transmitted. */
object OpenFoodFactsLookup {
    suspend fun find(barcode: String): ProductLookup = withContext(Dispatchers.IO) {
        require(barcode.matches(Regex("[0-9]{8,14}"))) { "This barcode format is not supported for product lookup." }
        val connection = (URL("https://world.openfoodfacts.org/api/v3/product/$barcode?fields=product_name,brands,categories&lc=en").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ExpiryEye/1.0 (offline-first food inventory app)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext ProductLookup(barcode, message = "Product lookup is unavailable. You can enter the name yourself.")
            val body = connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder(); val buffer = CharArray(4096)
                while (true) { val count = reader.read(buffer); if (count < 0) break; require(out.length + count <= 1_000_000) { "Product response is too large." }; out.append(buffer, 0, count) }
                out.toString()
            }
            val root = JSONObject(body)
            if (root.optString("status") !in setOf("success", "success_with_warnings", "success_with_errors")) return@withContext ProductLookup(barcode, message = "We couldn't find this barcode. Enter the product name to continue.")
            val product = root.optJSONObject("product") ?: JSONObject()
            val name = product.optString("product_name", "").takeIf { it.isNotBlank() }
            val brand = product.optString("brands", "").split(',').firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            val categories = product.optString("categories", "").lowercase()
            val category = when {
                "fruit" in categories -> "Fruits"
                "vegetable" in categories -> "Vegetables"
                "dairy" in categories || "milk" in categories || "cheese" in categories -> "Dairy"
                "meat" in categories -> "Meat"
                "fish" in categories || "seafood" in categories -> "Fish & seafood"
                "beverage" in categories || "drink" in categories -> "Beverages"
                "bakery" in categories || "bread" in categories -> "Bakery"
                "frozen" in categories -> "Frozen food"
                "canned" in categories -> "Canned food"
                else -> "Other"
            }
            ProductLookup(barcode, name, brand, category, if (name == null) "No product name was provided. You can enter it manually." else null)
        } finally { connection.disconnect() }
    }
}
