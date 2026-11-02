# Budget — Cipher Revolut improvements

Project repository for adapting [Cipher](https://github.com/insaneodyssey26/Cipher) to reliably import Revolut notifications.

**Status:** The upstream Android project has not yet been imported into this repository. The initial commit contains a self-contained Revolut notification parser and regression tests; it is **not yet a buildable APK**.

## Confirmed notification sample (2 November 2026)

- Notification title: `T554 Carcaixent`
- Body: `🛒 Paid €3.14 at T554 Carcaixent\n⚠️ EUR Balance: €21.86`
- Expected: expense 3.14 EUR, merchant `T554 Carcaixent`
- Balance 21.86 must not be recorded as a transaction.
- Merchant must not become `Miscellaneous`.

## Integration

1. Import the upstream Cipher Android code, preserving upstream attribution/license and Git history where feasible.
2. In `TransactionNotificationService.onNotificationPosted`, before concatenating notification fields, use `RevolutNotificationParser.parse(title, text, bigText)` for package `com.revolut.revolut`.
3. When that returns a transaction, construct `TransactionEntity` with its merchant, amount, currency, and expense flag, retain original notification in `rawSms`, and insert through the existing repository. Fall back to Cipher's generic parser for notifications not recognized by this parser.
4. Run the included tests and build the APK with the upstream project's Gradle configuration.

Privacy: do not commit actual personal notification histories or account data.
