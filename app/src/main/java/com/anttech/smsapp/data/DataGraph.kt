package com.anttech.smsapp.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.anttech.smsapp.contacts.ContactNameResolver
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.data.telephony.PlatformTelephonyMessageWriter
import com.anttech.smsapp.data.telephony.ProviderImportCoordinator
import com.anttech.smsapp.data.telephony.TelephonySmsDataSource
import com.anttech.smsapp.sms.role.SmsRoleManager
import com.anttech.smsapp.sync.GoogleAccessTokenProvider
import com.anttech.smsapp.sync.HttpDriveClient
import com.anttech.smsapp.sync.SyncRepository
import com.anttech.smsapp.sync.SyncSettings

/**
 * Entry point for data access, hiding construction of the stores.
 */
class DataGraph private constructor(
    context: Context,
    private val injectedRepository: MessageRepository? = null,
) {

    private val database = SmsDatabase.get(context)

    val messageRepository: MessageRepository = injectedRepository ?: MessageRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        draftDao = database.draftDao(),
        telephonyWriter = PlatformTelephonyMessageWriter(context),
    )

    /**
     * Conversation-level queries and commands, split out of [messageRepository].
     * Derived from the message store rather than built separately so an injected
     * test store and the app graph hand out the same instance: the thread screen
     * and the inbox it came from must share one read count.
     */
    val conversationRepository: ConversationRepository get() = messageRepository.conversations

    /**
     * Backfills provider history into the local store, over the same database as
     * [messageRepository]. Only the import path reads it, and a test that injects
     * its own [MessageRepository] does not drive that path.
     */
    val providerImportRepository: ProviderImportRepository = ProviderImportRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        database = database,
    )

    val telephonyDataSource: TelephonySmsDataSource = TelephonySmsDataSource(context)

    val roleManager: SmsRoleManager = SmsRoleManager(context)

    /**
     * Shared so every screen, notification and receiver resolves numbers through
     * one cache instead of each building its own and re-querying the provider.
     */
    val contactNames: ContactNameResolver = ContactNameResolver(context)

    /**
     * Backfills messages the provider already holds.
     *
     * Shared rather than constructed per call so the mutex inside serialises
     * concurrent imports instead of letting two full provider queries race.
     */
    val providerImport: ProviderImportCoordinator by lazy {
        ProviderImportCoordinator(
            repository = providerImportRepository,
            dataSource = telephonyDataSource,
            hasSmsRole = { roleManager.isHeld() },
        )
    }

    /**
     * Backs the store up to the user's hidden Drive app data folder and merges
     * in what their other phones put there, over the same database as
     * [messageRepository].
     *
     * Shared because the settings screen's button and the periodic worker must
     * go through one instance: [SyncRepository] serialises its attempts, and two
     * instances would not. Construction is cheap - the Play services client is
     * only reached when a sync actually runs - so this does not bring Google
     * Play services into the process just by opening the app.
     */
    val syncRepository: SyncRepository = SyncRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        database = database,
        settings = SyncSettings(context),
        drive = HttpDriveClient(GoogleAccessTokenProvider(context)),
    )

    companion object {
        @Volatile
        private var instance: DataGraph? = null

        fun get(context: Context): DataGraph =
            instance ?: synchronized(this) {
                instance ?: DataGraph(context.applicationContext).also { instance = it }
            }

        /**
         * Installs a store for the current test, or clears it with null.
         *
         * The graph is a process-wide singleton, so a test cannot build its own
         * and expect the code under test to use it: the notification action
         * receiver resolves its repository through [get] and would otherwise
         * write to the real on-device database.
         */
        @VisibleForTesting
        fun overrideForTest(context: Context, repository: MessageRepository?) {
            instance = repository?.let { DataGraph(context.applicationContext, it) }
        }
    }
}