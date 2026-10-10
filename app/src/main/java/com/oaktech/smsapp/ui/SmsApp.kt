package com.oaktech.smsapp.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.oaktech.smsapp.contacts.ContactNameResolver
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.permission.PermissionSetup
import com.oaktech.smsapp.permission.SmsPermissions
import com.oaktech.smsapp.sms.send.SendGraph
import com.oaktech.smsapp.ui.compose.ComposeIntentParser
import com.oaktech.smsapp.ui.compose.ComposeRoute
import com.oaktech.smsapp.ui.compose.ComposeViewModel
import com.oaktech.smsapp.ui.conversations.ConversationsRoute
import com.oaktech.smsapp.ui.conversations.ConversationsViewModel
import com.oaktech.smsapp.ui.drafts.DraftsRoute
import com.oaktech.smsapp.ui.drafts.DraftsViewModel
import com.oaktech.smsapp.ui.permission.PermissionGate
import com.oaktech.smsapp.ui.permission.PermissionRevokedBanner
import com.oaktech.smsapp.ui.permission.openAppSettings
import com.oaktech.smsapp.ui.search.SearchRoute
import com.oaktech.smsapp.ui.search.SearchViewModel
import com.oaktech.smsapp.ui.settings.SettingsRoute
import com.oaktech.smsapp.ui.spam.SpamRoute
import com.oaktech.smsapp.ui.spam.SpamViewModel
import com.oaktech.smsapp.ui.thread.ThreadRoute
import com.oaktech.smsapp.ui.thread.ThreadViewModel

private object Routes {
    const val CONVERSATIONS = "conversations"
    const val SPAM = "spam"
    const val DRAFTS = "drafts"
    const val THREAD = "thread/{conversationId}?recipient={recipient}"
    const val COMPOSE = "compose"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    fun thread(conversationId: Long, recipient: String) =
        "thread/$conversationId?recipient=${android.net.Uri.encode(recipient)}"
}

@Composable
fun SmsApp(
    initialConversationId: Long? = null,
    composeRequest: ComposeRequest? = null,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val roleState = rememberSmsRoleState()
    val roleRequestScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-read on every resume: the system can revoke SMS access when the role
    // changes, and a stale "granted" would leave the UI offering to send.
    var grantedPermissions by remember { mutableStateOf(SmsPermissions.allGranted(context)) }

    // Whether the grant has ever succeeded decides what a missing permission
    // means: never granted is a first launch and gates, granted-then-revoked is
    // a revocation and must not, because the local store is still readable and
    // hiding it would present data loss to the user.
    var setupCompleted by remember { mutableStateOf(PermissionSetup.everGranted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                grantedPermissions = SmsPermissions.allGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Trust the result map, not a re-read: a denial may be "don't ask again",
        // in which case the flag is genuinely absent and a re-read agrees. The map
        // is still authoritative for permissions the user just granted.
        // Only essential permissions gate the app. A POST_NOTIFICATIONS denial
        // on API 33+ is acceptable: the app works without alerts, and the user
        // can enable them later from system settings.
        grantedPermissions = SmsPermissions.allGranted(context, SmsPermissions.essential)
        if (grantedPermissions) {
            PermissionSetup.markGranted(context)
            setupCompleted = true
        }
    }

    val requestPermissions: () -> Unit = {
        permissionLauncher.launch(SmsPermissions.forCurrentPlatform().toTypedArray())
    }

    // The role request dialog resolves the requester from the calling
    // package, which only exists when the intent is started for result; a
    // fire-and-forget startActivity() makes the dialog dismiss itself
    // before drawing anything.
    val requestSmsRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { roleRequestScope.launch { roleState.refreshAfterRequest() } }

    val requestSmsRole: () -> Unit = {
        roleState.requestIntent()?.let { intent ->
            runCatching { requestSmsRoleLauncher.launch(intent) }
        }
    }

    if (!grantedPermissions && !setupCompleted) {
        PermissionGate(
            missing = SmsPermissions.missing(context),
            onRequestPermissions = requestPermissions,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // The role banner inside each screen covers a role-loss revocation,
        // since re-taking the role grants the SMS group back. This one only
        // fires while the role is still held — permissions the user switched
        // off by hand cannot be won back by any in-app flow.
        if (!grantedPermissions && roleState.isHeld) {
            PermissionRevokedBanner(onOpenSettings = { openAppSettings(context) })
        }

        SmsNavHost(
            navController = navController,
            isSmsRoleHeld = { roleState.isHeld },
            requestSmsRole = requestSmsRole,
            initialConversationId = initialConversationId,
            composeRequest = composeRequest,
            roleHeldNow = roleState.isHeld,
        )
    }
}

/** A SENDTO request from another app, parsed into the composer. */
data class ComposeRequest(val recipient: String, val body: String, val draftId: Long? = null)

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SmsNavHost(
    navController: NavHostController,
    isSmsRoleHeld: () -> Boolean,
    requestSmsRole: () -> Unit,
    initialConversationId: Long?,
    composeRequest: ComposeRequest?,
    roleHeldNow: Boolean,
) {
    val context = LocalContext.current
    val navigationScope = rememberCoroutineScope()
    val repository = DataGraph.get(context).messageRepository
    val contactNames = remember { ContactNameResolver(context) }
    // Pull in the provider's history once the role is held. Runs on every entry
    // rather than once per process: the role can be granted after launch, and
    // messages also arrive while another app holds the role.
    LaunchedEffect(roleHeldNow) {
        if (roleHeldNow) {
            contactNames.preload()
            DataGraph.get(context).providerImport.importIfPossible()
        }
    }

    // A notification tap names a conversation id but not an address, so the
    // recipient is resolved from the stored row. Suspended because the query
    // hits Room; navigating with the wrong address would send to the wrong
    // person, so it waits for the real value.
    var pendingDeepLinkId by remember { mutableStateOf(initialConversationId) }
    var initialRecipient by remember { mutableStateOf<String?>(null) }

    // When onNewIntent delivers a new conversation id while the activity is
    // already created, initialConversationId changes but the remembered
    // pendingDeepLinkId does not. This effect propagates the new value.
    LaunchedEffect(initialConversationId) {
        if (initialConversationId != null) {
            pendingDeepLinkId = initialConversationId
        }
    }

    LaunchedEffect(pendingDeepLinkId) {
        initialRecipient = pendingDeepLinkId?.let { repository.recipientFor(it) }
    }

    // A SENDTO request is one-shot, like the notification deep link above.
    // Keying navigation straight on the request re-opens the composer every time
    // the conversations destination is re-entered, and a send pops back to
    // conversations: the composer would return holding the same recipient and
    // body, and each further tap would send a duplicate. Adopting a request only
    // while it differs from the last one served keeps a repeat delivery of the
    // same intent idempotent, which is also what the old key-based effect did.
    var servedComposeRequest by remember { mutableStateOf<ComposeRequest?>(null) }
    var pendingComposeRequest by remember { mutableStateOf<ComposeRequest?>(null) }
    LaunchedEffect(composeRequest) {
        if (composeRequest != null && composeRequest != servedComposeRequest) {
            pendingComposeRequest = composeRequest
        }
    }

    NavHost(navController = navController, startDestination = Routes.CONVERSATIONS) {
        composable(Routes.CONVERSATIONS) {
            val viewModel: ConversationsViewModel = viewModel(
                factory = ConversationsViewModel.factory(repository, isSmsRoleHeld, contactNames),
            )
            ConversationsRoute(
                viewModel = viewModel,
                roleHeldNow = roleHeldNow,
                onOpenConversation = { row ->
                    navController.navigate(Routes.thread(row.id, row.address))
                },
                onRequestSmsRole = requestSmsRole,
                onResume = viewModel::resolveContactNames,
                onOpenSpam = { navController.navigate(Routes.SPAM) },
                onOpenDrafts = { navController.navigate(Routes.DRAFTS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                // Reached from the app's own floating action button, so unlike the
                // SENDTO path below there is no request to seed and nothing to
                // mark as served: a repeat tap means another blank draft.
                onCompose = {
                    servedComposeRequest = null
                    navController.navigate(Routes.COMPOSE)
                },
            )

            LaunchedEffect(Unit) { viewModel.resolveContactNames() }

            // Deep link from a notification tap. Navigation is keyed on both the
            // id and the resolved address so a slow Room query cannot navigate
            // twice, and so the thread never opens with a blank recipient.
            LaunchedEffect(pendingDeepLinkId, initialRecipient) {
                val conversationId = pendingDeepLinkId
                val recipient = initialRecipient
                if (conversationId != null && recipient != null) {
                    pendingDeepLinkId = null
                    initialRecipient = null
                    navController.navigate(Routes.thread(conversationId, recipient))
                }
            }

            // SENDTO from another app takes precedence over a notification deep
            // link: the caller asked for a composer, and honouring the tap
            // instead would silently drop the recipient they supplied. The
            // request is cleared as it is served, so returning here after a send
            // does not re-open the composer.
            LaunchedEffect(pendingComposeRequest) {
                val request = pendingComposeRequest
                if (request != null) {
                    servedComposeRequest = request
                    pendingComposeRequest = null
                    pendingDeepLinkId = null
                    navController.navigate(Routes.COMPOSE)
                }
            }
        }

        composable(Routes.COMPOSE) {
            // Seed from the request that was served rather than the raw
            // parameter, so the composer is populated even after a send has
            // already cleared the pending copy.
            val request = servedComposeRequest
            var composeContactName by remember(request?.recipient) { mutableStateOf<String?>(null) }
            LaunchedEffect(request?.recipient) {
                composeContactName = request?.recipient?.let { contactNames.displayName(it) }
            }
            val viewModel: ComposeViewModel = viewModel(
                factory = ComposeViewModel.factory(
                    initialRecipient = request?.recipient.orEmpty(),
                    initialDraft = request?.body.orEmpty(),
                    isSmsRoleHeld = isSmsRoleHeld,
                    send = { address, body ->
                        SendGraph.coordinator(context).send(address, body)
                    },
                ),
            )

            val contactLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickContact(),
            ) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                queryContactPhoneNumber(context.contentResolver, uri)?.let(viewModel::addRecipient)
            }
            ComposeRoute(
                viewModel = viewModel,
                contactName = composeContactName,
                onBack = {
                    val composeState = viewModel.uiState.value
                    navigationScope.launch {
                        // Save before removing the compose destination: a
                        // ViewModel-scoped write would be cancelled as soon as
                        // this screen is popped.
                        repository.saveDraftForRecipient(
                            composeState.recipient,
                            composeState.draft,
                            request?.draftId,
                        )
                        servedComposeRequest = null
                        if (!navController.popBackStack(Routes.CONVERSATIONS, false)) {
                            navController.context.findActivity()?.finish()
                        }
                    }
                },
                onRequestSmsRole = requestSmsRole,
                onPickContact = { contactLauncher.launch(null) },
                isSmsRoleHeld = isSmsRoleHeld(),
            )
        }

        // The spam folder. Reads the same conversations as the inbox through a
        // different query, so marking as spam and restoring are one flag change
        // and neither list can hold a row the other still claims.
        composable(Routes.SPAM) {
            val viewModel: SpamViewModel = viewModel(
                factory = SpamViewModel.factory(repository, contactNames),
            )
            SpamRoute(
                viewModel = viewModel,
                onOpenConversation = { row ->
                    navController.navigate(Routes.thread(row.id, row.address))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.DRAFTS) {
            val viewModel: DraftsViewModel = viewModel(
                factory = DraftsViewModel.factory(repository, contactNames),
            )
            DraftsRoute(
                viewModel = viewModel,
                onOpenDraft = { row ->
                    servedComposeRequest = ComposeRequest(
                        recipient = row.address,
                        body = row.snippet,
                        draftId = row.id,
                    )
                    navController.navigate(Routes.COMPOSE)
                },
                onSendDraft = { row ->
                    servedComposeRequest = ComposeRequest(
                        recipient = row.address,
                        body = row.snippet,
                        draftId = row.id,
                    )
                    navController.navigate(Routes.COMPOSE)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SEARCH) {
            val viewModel: SearchViewModel = viewModel(
                factory = SearchViewModel.factory(repository, contactNames::displayName),
            )
            SearchRoute(
                viewModel = viewModel,
                onOpenConversation = { result ->
                    navController.navigate(Routes.thread(result.conversationId, result.address))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.THREAD,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.LongType },
                navArgument("recipient") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val conversationId = entry.arguments?.getLong("conversationId") ?: 0L
            val recipient = entry.arguments?.getString("recipient").orEmpty()
            var threadContactName by remember(recipient) { mutableStateOf<String?>(null) }
            LaunchedEffect(recipient) {
                threadContactName = contactNames.displayName(recipient)
            }

            val viewModel: ThreadViewModel = viewModel(
                factory = ThreadViewModel.factory(
                    repository = repository,
                    conversationId = conversationId,
                    recipient = recipient,
                    isSmsRoleHeld = isSmsRoleHeld,
                    send = { address, body ->
                        SendGraph.coordinator(context).send(address, body)
                    },
                ),
            )

            ThreadRoute(
                viewModel = viewModel,
                recipient = recipient,
                contactName = threadContactName,
                roleHeldNow = roleHeldNow,
                onBack = { navController.popBackStack() },
                onRequestSmsRole = requestSmsRole,
                // Forward opens the composer with the body pre-filled and no
                // recipient, exactly as if the user had tapped "share to new
                // message" — the sender chooses who it goes to.
                onForward = { body ->
                    servedComposeRequest = ComposeRequest(recipient = "", body = body)
                    navController.navigate(Routes.COMPOSE)
                },
                // A number tapped inside a message opens the composer with that
                // number pre-filled and a blank body, so the user writes a fresh
                // message to it rather than continuing this thread.
                onMessageNumber = { number ->
                    servedComposeRequest = ComposeRequest(recipient = number, body = "")
                    navController.navigate(Routes.COMPOSE)
                },
            )
        }
    }
}

/** Reads the first phone number for a contact selected from the system picker. */
private fun queryContactPhoneNumber(
    resolver: android.content.ContentResolver,
    contactUri: Uri,
): String? {
    val contactId = runCatching { ContentUris.parseId(contactUri) }.getOrNull() ?: return null
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
    return runCatching {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numberIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(numberIndex)) {
                cursor.getString(numberIndex)
            } else {
                null
            }
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
