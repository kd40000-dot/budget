package com.masum.cipher.core.notifications

import android.util.Log
import com.masum.cipher.BuildConfig
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.masum.cipher.core.data.local.entity.TransactionEntity
import com.masum.cipher.core.data.local.pref.UserPreferences
import com.masum.cipher.core.data.repository.TransactionRepository
import com.masum.cipher.core.security.SecurityManager
import com.masum.cipher.core.sms.TransactionParser
import com.masum.cipher.core.sms.resolveTransactionTimestamp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TransactionNotificationService : NotificationListenerService() {

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var transactionParser: TransactionParser

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var packageInstallReceiver: PackageInstallReceiver

    @Inject
    lateinit var securityManager: SecurityManager

    private val serviceJob = SupervisorJob()
    private val logTag = "BudgetNotif"
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile
    private var cachedTrackedApps: Set<String> = emptySet()

    @Volatile
    private var cachedCurrencyCode: String = "INR"

    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        registerReceiver(packageInstallReceiver, filter)
        serviceScope.launch {
            userPreferences.settingsFlow.collect { settings ->
                cachedTrackedApps = settings.trackedApps
                cachedCurrencyCode = settings.currencyCode
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val packageName = sbn.packageName ?: return
        if (!cachedTrackedApps.contains(packageName)) {
            if (BuildConfig.DEBUG && packageName == REVOLUT_PACKAGE) Log.d(logTag, "Revolut received but not tracked")
            return
        }
        if (BuildConfig.DEBUG) Log.d(logTag, "Tracked notification received; app=" + packageName)

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val titleBig = extras.getCharSequence(android.app.Notification.EXTRA_TITLE_BIG)?.toString().orEmpty()
        val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val textLines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.joinToString(" ").orEmpty()
        val summaryText = extras.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()

        val fullMessage = listOf(title, titleBig, text, bigText, subText, textLines, summaryText, infoText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .trim()
        if (fullMessage.isBlank()) {
            if (BuildConfig.DEBUG) Log.d(logTag, "Notification has no readable text")
            return
        }

        val currencyCode = cachedCurrencyCode
        val postedAtMillis = sbn.postTime
        serviceScope.launch {
            val revolutPayment = if (packageName == REVOLUT_PACKAGE) {
                RevolutNotificationParser.parse(title, text, bigText, textLines, titleBig, subText, summaryText, infoText)
            } else null
            // Retain upstream fallback so unknown Revolut notification formats are not discarded.
            val parsedTx = if (revolutPayment == null) transactionParser.parse(fullMessage, currencyCode) else null
            if (BuildConfig.DEBUG) Log.d(logTag, "Parsing: dedicated=" + (revolutPayment != null) + ", generic=" + (parsedTx != null))
            if (revolutPayment != null || parsedTx != null) {
                val appLabel = try {
                    val pm = packageManager
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    null
                }

                val storedMessage = if (!appLabel.isNullOrBlank() && !fullMessage.startsWith("[$appLabel]")) {
                    "[$appLabel] $fullMessage"
                } else {
                    fullMessage
                }

                val transactionEntity = TransactionEntity(
                    merchant = revolutPayment?.merchant ?: requireNotNull(parsedTx).merchant,
                    amount = revolutPayment?.amount ?: requireNotNull(parsedTx).amount,
                    currency = revolutPayment?.currency ?: requireNotNull(parsedTx).currency,
                    category = "",
                    isIncome = revolutPayment?.isIncome ?: requireNotNull(parsedTx).isIncome,
                    rawSms = storedMessage,
                    timestamp = resolveTransactionTimestamp(postedAtMillis, System.currentTimeMillis())
                )
                if (securityManager.isDatabaseKeyUnavailable()) {
                    if (BuildConfig.DEBUG) Log.w(logTag, "Transaction not saved: database key unavailable")
                    return@launch
                }
                try {
                    val saved = transactionRepository.insertTransaction(transactionEntity)
                    if (BuildConfig.DEBUG) Log.d(logTag, "Transaction insertion: " + (if (saved == null) "not inserted" else "inserted"))
                } catch (e: Exception) {
                    Log.e(logTag, "Transaction insertion failed", e)
                }
            }
        }
    }

    companion object { private const val REVOLUT_PACKAGE = "com.revolut.revolut" }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageInstallReceiver)
        serviceJob.cancel()
    }
}
