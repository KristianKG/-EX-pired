package com.expiryeye.domain

data class ReceiptCandidate(val name: String, val price: Double? = null, val quantity: Double = 1.0)

/** Heuristic only: every OCR candidate is shown for user review before it can be saved. */
object ReceiptTextParser {
    private val pricePattern = Regex("(?<!\\d)(\\d{1,5}[.,]\\d{2})(?!\\d)")
    private val quantityPrefix = Regex("^(\\d+(?:[.,]\\d+)?)\\s*[xX*]\\s+")
    private val ignoredLine = Regex("(?i)\\b(total|subtotal|tax|vat|change|cash|card|receipt|balance|tender|discount|thank you)\\b")

    fun parse(text: String): List<ReceiptCandidate> {
        val seen = mutableSetOf<String>()
        return text.lineSequence().mapNotNull { raw ->
            val line = raw.trim().replace(Regex("\\s+"), " ")
            if (line.length !in 3..100 || ignoredLine.containsMatchIn(line)) return@mapNotNull null
            val quantityMatch = quantityPrefix.find(line)
            val quantity = quantityMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            val withoutQuantity = if (quantityMatch != null) line.removeRange(quantityMatch.range) else line
            val amount = pricePattern.findAll(withoutQuantity).lastOrNull()
            val price = amount?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            val name = (if (amount == null) withoutQuantity else withoutQuantity.removeRange(amount.range))
                .trim().trimEnd { !it.isLetterOrDigit() }
            val letterCount = name.count(Char::isLetter)
            if (name.length !in 2..80 || letterCount < 2 || name.any(Char::isDigit) && letterCount < 4) return@mapNotNull null
            if (!seen.add(name.lowercase())) return@mapNotNull null
            ReceiptCandidate(name, price, quantity)
        }.take(50).toList()
    }
}
