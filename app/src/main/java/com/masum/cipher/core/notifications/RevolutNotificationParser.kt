package com.masum.cipher.core.notifications

import java.util.Locale

/**
 * Parses Revolut card payment notifications without interpreting the available
 * balance as another transaction. Kept independent from Android for unit tests.
 */
internal object RevolutNotificationParser {
    data class Payment(
        val merchant: String,
        val amount: Double,
        val currency: String,
        val isIncome: Boolean = false
    )

    private val cardPayment = Regex(
        """(?i)\bpaid\s*(€|EUR|£|GBP|\$|USD)\s*([0-9][0-9.,\s]*)\s+at\s+([^\r\n]+)"""
    )
    private val merchantOnlyPayment = Regex(
        """(?i)\bpaid\s*(€|EUR|£|GBP|\$|USD)\s*([0-9][0-9.,\s]*)(?=\s*$)"""
    )
    private val trailingBalance = Regex(
        """(?i)\s*(?:⚠️|⚠)?\s*(?:EUR|GBP|USD)?\s*balance\s*:.*$"""
    )
    private val invalidMerchant = Regex("""(?i)^(?:revolut|payment|card payment|miscellaneous)$""")

    fun parse(title: String, text: String, bigText: String = "", vararg additionalFields: String): Payment? {
        val candidates = (listOf(bigText, text) + additionalFields.toList()).filter { it.isNotBlank() }.distinct()
        for (body in candidates) {
            val match = cardPayment.find(body)
            if (match != null) {
                val amount = parseAmount(match.groupValues[2]) ?: continue
                val rawMerchant = match.groupValues[3]
                    .replace(trailingBalance, "")
                    .trim()
                    .trimEnd('.', ' ', '⚠')
                val merchant = rawMerchant.takeIf { it.isNotBlank() && !invalidMerchant.matches(it) }
                    ?: usableTitle(title)
                    ?: continue
                return Payment(merchant, amount, currency(match.groupValues[1]))
            }

            // Some Revolut variants put the merchant solely in the title.
            val strippedBody = body.lineSequence().firstOrNull().orEmpty().trim()
            val amountOnly = merchantOnlyPayment.find(strippedBody)
            if (amountOnly != null) {
                val merchant = usableTitle(title) ?: continue
                val amount = parseAmount(amountOnly.groupValues[2]) ?: continue
                return Payment(merchant, amount, currency(amountOnly.groupValues[1]))
            }
        }
        return null
    }

    private fun usableTitle(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && !invalidMerchant.matches(it) }

    private fun currency(symbol: String): String = when (symbol.uppercase(Locale.ROOT)) {
        "€", "EUR" -> "EUR"
        "£", "GBP" -> "GBP"
        else -> "USD"
    }

    private fun parseAmount(raw: String): Double? {
        val compact = raw.replace(" ", "")
        val lastComma = compact.lastIndexOf(',')
        val lastDot = compact.lastIndexOf('.')
        val decimalSeparator = when {
            lastComma > lastDot -> ','
            lastDot >= 0 -> '.'
            else -> null
        }
        val normalized = if (decimalSeparator == null) {
            compact
        } else {
            val integerPart = compact.substring(0, maxOf(lastComma, lastDot))
                .filter { it.isDigit() }
            val fractionalPart = compact.substring(maxOf(lastComma, lastDot) + 1)
            if (fractionalPart.length !in 1..2 || fractionalPart.any { !it.isDigit() }) return null
            "$integerPart.$fractionalPart"
        }
        return normalized.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
    }
}
