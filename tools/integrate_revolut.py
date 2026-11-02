#!/usr/bin/env python3
"""Wire the Revolut-specific parser into Cipher's notification listener."""
from pathlib import Path

path = Path("app/src/main/java/com/masum/cipher/core/notifications/TransactionNotificationService.kt")
src = path.read_text()
old = """            val parsedTx = transactionParser.parse(fullMessage, currencyCode)
            if (parsedTx != null) {"""
new = """            val revolutPayment = if (packageName == "com.revolut.revolut") {
                RevolutNotificationParser.parse(title, text, bigText)
            } else null
            val parsedTx = if (revolutPayment != null) null
                else if (packageName == "com.revolut.revolut") null
                else transactionParser.parse(fullMessage, currencyCode)
            if (revolutPayment != null || parsedTx != null) {"""
if "RevolutNotificationParser.parse(title, text, bigText)" in src:
    print("Already patched")
    raise SystemExit(0)
if old not in src:
    raise SystemExit("Upstream notification listener changed: refusing unsafe patch")
src = src.replace(old, new)
src = src.replace(
    "merchant = parsedTx.merchant,",
    "merchant = revolutPayment?.merchant ?: requireNotNull(parsedTx).merchant,",
)
src = src.replace(
    "amount = parsedTx.amount,",
    "amount = revolutPayment?.amount ?: requireNotNull(parsedTx).amount,",
)
src = src.replace(
    "currency = parsedTx.currency,",
    "currency = revolutPayment?.currency ?: requireNotNull(parsedTx).currency,",
)
src = src.replace(
    "isIncome = parsedTx.isIncome,",
    "isIncome = revolutPayment?.isIncome ?: requireNotNull(parsedTx).isIncome,",
)
path.write_text(src)
print("Revolut notification listener patch applied")
