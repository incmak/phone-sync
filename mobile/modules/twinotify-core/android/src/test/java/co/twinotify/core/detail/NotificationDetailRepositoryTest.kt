package co.twinotify.core.detail

import co.twinotify.core.actions.ActionInvokeIdentity
import co.twinotify.core.actions.MirrorActionInvokeResult
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.NotificationDetailCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NotificationDetailRepositoryTest {
    @Test
    fun unknownOrExpiredDetailReturnsNull() = runTest {
        val repository = repository(cache = null)

        assertNull(repository.get(DETAIL_ID))
    }

    @Test
    fun cancelledCacheKeepsContentAndInvocationStateReadable() = runTest {
        val repository = repository(
            cache = cache(cancelledAt = 5_000),
            canonical = canonical(state = "CANCELLED"),
            invocations = listOf(invocation("DISPATCHED")),
        )

        val detail = requireNotNull(repository.get(DETAIL_ID))
        assertEquals("CANCELLED", detail.state)
        assertEquals("Example", detail.sourceAppName)
        assertEquals("Title", detail.title)
        assertEquals("DISPATCHED", detail.actions.single().invocationState)
        assertEquals("Paired phone", detail.originDeviceLabel)
        assertTrue(detail.smallIconDataUri?.startsWith("data:image/png;base64,") == true)
    }

    @Test
    fun invokeUsesSharedInvokerWithCurrentStableMirrorIdentity() = runTest {
        var captured: ActionInvokeIdentity? = null
        var reply: String? = null
        val repository = repository(
            cache = cache(cancelledAt = null),
            canonical = canonical(state = "ACTIVE"),
            invoke = NotificationDetailActionInvoker { identity, replyText ->
                captured = identity
                reply = replyText
                MirrorActionInvokeResult.Queued(INVOCATION_ID)
            },
        )

        assertEquals(
            MirrorActionInvokeResult.Queued(INVOCATION_ID),
            repository.invoke(DETAIL_ID, ACTION_ID, "hello"),
        )
        assertEquals(ActionInvokeIdentity("mirror-tag", 41, ACTION_ID), captured)
        assertEquals("hello", reply)
    }

    @Test
    fun cancelledDetailCannotInvokeAndLaunchabilityIsQueriedLive() = runTest {
        var launchChecks = 0
        val repository = repository(
            cache = cache(cancelledAt = 5_000),
            canonical = canonical(state = "CANCELLED"),
            canLaunch = NotificationSourceLaunchability {
                launchChecks += 1
                launchChecks == 2
            },
        )

        assertEquals(MirrorActionInvokeResult.Gone, repository.invoke(DETAIL_ID, ACTION_ID, null))
        assertEquals(false, repository.canLaunchSourceApp("com.example"))
        assertEquals(true, repository.canLaunchSourceApp("com.example"))
    }

    @Test
    fun sourceOpenResolvesPackageFromOpaqueDetailAtTapTime() = runTest {
        val opened = mutableListOf<String>()
        val repository = repository(
            cache = cache(cancelledAt = null),
            canonical = canonical(state = "ACTIVE"),
            openSource = NotificationSourceOpener { packageName ->
                opened += packageName
                SourceLaunchResult.Launched
            },
        )

        assertEquals(true, repository.openSourceApp(DETAIL_ID))
        assertEquals(listOf("com.example"), opened)
        assertEquals(false, repository.openSourceApp("44444444-4444-4444-8444-444444444444"))
    }

    private fun repository(
        cache: NotificationDetailCache?,
        canonical: CanonicalNotificationState? = null,
        invocations: List<ActionInvocation> = emptyList(),
        invoke: NotificationDetailActionInvoker = NotificationDetailActionInvoker { _, _ -> error("unused") },
        canLaunch: NotificationSourceLaunchability = NotificationSourceLaunchability { false },
        openSource: NotificationSourceOpener = NotificationSourceOpener { SourceLaunchResult.LaunchFailed },
    ) = NotificationDetailRepository(
        store = object : NotificationDetailStore {
            override suspend fun cache(detailId: String) = cache?.takeIf { it.detailId == detailId }
            override suspend fun canonical(canonId: String) = canonical
            override suspend fun invocations(canonId: String, sequence: Long) = invocations
        },
        originLabel = NotificationOriginLabel { "Paired phone" },
        invokeAction = invoke,
        sourceLaunchability = canLaunch,
        sourceOpener = openSource,
    )

    private fun cache(cancelledAt: Long?) = NotificationDetailCache(
        detailId = DETAIL_ID,
        canonId = CANON_ID,
        payloadJson = PAYLOAD,
        originDevice = "peer-device",
        receivedAt = 1_000,
        updatedAt = 2_000,
        cancelledAt = cancelledAt,
    )

    private fun canonical(state: String) = CanonicalNotificationState(
        canonId = CANON_ID,
        originDevice = "peer-device",
        latestSequence = 7,
        state = state,
        desiredPayloadJson = if (state == "ACTIVE") PAYLOAD else null,
        materializedSequence = 7,
        sourceNotificationKey = null,
        mirrorLocalId = 41,
        mirrorLocalTag = "mirror-tag",
        peerCancelPending = false,
        updatedAt = 2_000,
    )

    private fun invocation(state: String) = ActionInvocation(
        invocationId = INVOCATION_ID,
        canonId = CANON_ID,
        actionId = ACTION_ID,
        notificationSequence = 7,
        replyText = null,
        state = state,
        createdAt = 1_500,
        expiresAt = 121_500,
        updatedAt = 2_000,
    )

    private companion object {
        const val DETAIL_ID = "11111111-1111-4111-8111-111111111111"
        const val ACTION_ID = "22222222-2222-4222-8222-222222222222"
        const val INVOCATION_ID = "33333333-3333-4333-8333-333333333333"
        const val CANON_ID = "peer-device:com.example:7:tag"
        const val PAYLOAD = """{"v":1,"type":"notif.post","canon_id":"$CANON_ID","app_name":"Example","package_name":"com.example","id":7,"tag":"tag","title":"Title","text":"Body","sub_text":"Chat","big_text":"Long body","visibility":"private","is_group_summary":false,"is_ongoing":false,"is_clearable":true,"small_icon_png_b64":"aWNvbg==","large_icon_png_b64":null,"ts":1000,"is_auto_cancel":true,"actions":[{"action_id":"$ACTION_ID","title":"Reply","semantic":1,"reply":true,"reply_label":"Message"}]}"""
    }
}
