package com.masum.cipher.core.notifications

import org.junit.Assert.*
import org.junit.Test

class RevolutNotificationParserTest {
    @Test fun actualRevolutNotification() {
        val result = RevolutNotificationParser.parse(
            "T554 Carcaixent",
            "🛒 Paid €3.14 at T554 Carcaixent\n⚠️ EUR Balance: €21.86"
        )
        assertNotNull(result)
        assertEquals("T554 Carcaixent", result!!.merchant)
        assertEquals(3.14, result.amount, 0.0001)
        assertEquals("EUR", result.currency)
        assertFalse(result.isIncome)
    }

    @Test fun commaDecimalAndUnknownMerchant() {
        val result = RevolutNotificationParser.parse(
            "Small local shop",
            "Paid €1.234,56 at Small local shop\nEUR Balance: €50,00"
        )
        assertNotNull(result)
        assertEquals("Small local shop", result!!.merchant)
        assertEquals(1234.56, result.amount, 0.0001)
    }

    @Test fun merchantInTitleOnly() {
        val result = RevolutNotificationParser.parse("Consum", "Paid €8.20")
        assertEquals("Consum", result?.merchant)
        assertEquals(8.20, result!!.amount, 0.0001)
    }

    @Test fun balanceIsNotPayment() {
        assertNull(RevolutNotificationParser.parse("Balance", "EUR Balance: €21.86"))
    }

    @Test fun noMerchantDoesNotInventMiscellaneous() {
        assertNull(RevolutNotificationParser.parse("Revolut", "Paid €3.14"))
    }
}
