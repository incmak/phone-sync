package co.twinotify.core.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.json.JSONObject
import java.nio.file.Files
import java.io.File
import co.twinotify.core.listener.PendingPeerCancel
import co.twinotify.core.service.SnapshotConvergence
import co.twinotify.core.service.StateDigest
import co.twinotify.core.service.forceRepairSnapshotForE2e
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class E2eControlSecurityTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emulatorNetworkFaultControlIsAuthenticatedClosedAndDelegated() {
        val calls = mutableListOf<String>()
        val receiver = E2eControlReceiver(
            networkControl = object : E2eNetworkControl {
                override suspend fun setExpected(context: Context, expected: String): E2eControlOutcome {
                    calls += expected
                    return E2eControlOutcome("ok", "transport_$expected")
                }
            },
        )
        val token = E2eSessionToken.forTest(context, "network-fault-control")

        assertEquals("unauthorized", receiver.executeForTest(
            context,
            E2eCommand("network-unauthorized", "SET_NETWORK_EXPECTED", token = "wrong", params = mapOf("expected" to "offline")),
        ).code)
        assertEquals("invalid", receiver.executeForTest(
            context,
            E2eCommand("network-extra", "SET_NETWORK_EXPECTED", token = token, params = mapOf("expected" to "offline", "relay_url" to "forbidden")),
        ).code)
        assertEquals("invalid", receiver.executeForTest(
            context,
            E2eCommand("network-invalid", "SET_NETWORK_EXPECTED", token = token, params = mapOf("expected" to "degraded")),
        ).code)

        for (expected in listOf("offline", "online")) {
            val result = receiver.executeForTest(
                context,
                E2eCommand("network-$expected", "SET_NETWORK_EXPECTED", token = token, params = mapOf("expected" to expected)),
            )
            assertEquals("ok", result.code)
        }
        assertEquals(listOf("offline", "online"), calls)
    }

    @Test
    fun userDismissStimulusCancelsPersistedIdentityWithoutPlantingPeerTombstone() {
        PendingPeerCancel.clearForTest()
        val cancelled = mutableListOf<Pair<String, Int>>()

        cancelMirrorAsUser("persisted-tag", 42) { tag, id -> cancelled += tag to id }

        assertEquals(listOf("persisted-tag" to 42), cancelled)
        assertEquals(0, PendingPeerCancel.sizeForTest())
    }

    @Test
    fun productionBackedControlsAreAuthenticatedClosedWorldAndContentFree() {
        val calls = mutableListOf<String>()
        val receiver = E2eControlReceiver(
            controls = object : E2eProductionControls {
                override suspend fun dismissNewestMirror(context: Context): E2eControlOutcome {
                    calls += "dismiss"
                    return E2eControlOutcome("ok", "requested")
                }

                override suspend fun emitSnapshot(context: Context): E2eControlOutcome {
                    calls += "snapshot"
                    return E2eControlOutcome("ok", "emitted")
                }

                override suspend fun forceRepairSnapshot(context: Context): E2eControlOutcome {
                    calls += "force-repair"
                    return E2eControlOutcome("ok", "repair_started")
                }

                override suspend fun localUnpair(context: Context): E2eControlOutcome {
                    calls += "unpair"
                    return E2eControlOutcome("ok", "lan")
                }
            },
        )
        val token = E2eSessionToken.forTest(context, "production-control-allowlist")

        for ((name, call) in listOf(
            "DISMISS_NEWEST_MIRROR" to "dismiss",
            "EMIT_SNAPSHOT" to "snapshot",
            "FORCE_REPAIR_SNAPSHOT" to "force-repair",
            "LOCAL_UNPAIR" to "unpair",
        )) {
            assertEquals("unauthorized", receiver.executeForTest(
                context,
                E2eCommand("$name-unauthorized", name, token = "wrong"),
            ).code)
            assertEquals("invalid", receiver.executeForTest(
                context,
                E2eCommand("$name-extra", name, token = token, params = mapOf("raw_id" to "forbidden")),
            ).code)
            val result = receiver.executeForTest(context, E2eCommand(name, name, token = token))
            assertEquals("ok", result.code)
            val retained = result.toJson().toString()
            assertFalse(retained.contains("raw_id"))
            assertFalse(retained.contains("canon", ignoreCase = true))
            assertFalse(retained.contains("message", ignoreCase = true))
            assertEquals(call, calls.last())
        }
    }

    @Test
    fun forcedRepairSnapshotDerivesMismatchAndDelegatesOnlyToProductionCoordinator() = runBlocking {
        val digest = StateDigest("local-origin", 0, "0".repeat(64))
        val calls = mutableListOf<String>()
        var delegated: StateDigest? = null
        var forced = false

        val started = forceRepairSnapshotForE2e(
            localDigest = { calls += "local-digest"; digest },
            onDigest = { mismatch, force ->
                calls += "on-digest"
                delegated = mismatch
                forced = force
                SnapshotConvergence.RepairStarted("private-id", 0)
            },
        )

        assertTrue(started)
        assertEquals(listOf("local-digest", "on-digest"), calls)
        assertTrue(forced)
        assertEquals(digest.originDevice, delegated?.originDevice)
        assertEquals(digest.count, delegated?.count)
        assertTrue(delegated?.digest?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue(delegated?.digest != digest.digest)

        val cancellation = CancellationException("fixture")
        val escaped = assertFailsWith<CancellationException> {
            forceRepairSnapshotForE2e(
                localDigest = { digest },
                onDigest = { _, _ -> throw cancellation },
            )
        }
        assertTrue(escaped === cancellation)
    }

    @Test
    fun productionObservationBlockIsClosedBoundedAndContentFree() {
        val root = JSONObject(E2eStateProvider.snapshotJson(context))
        val observation = root.getJSONObject("product_observations")
        assertEquals(
            setOf(
                "paired", "custody_counts", "peer_receipt_count", "snapshot_digest_count",
                "snapshot_begin_count", "snapshot_end_count", "snapshot_commit_count", "user_dismiss_count",
                "unpair_inbound_count", "unpair_outcome", "active_queue_count",
                "active_queue_bytes", "peak_queue_count", "peak_queue_bytes",
            ),
            observation.keys().asSequence().toSet(),
        )
        assertTrue(observation.getLong("active_queue_count") in 0..2_000)
        assertTrue(observation.getLong("active_queue_bytes") in 0..134_217_728)
        assertTrue(observation.getLong("peak_queue_count") in 0..2_000)
        assertTrue(observation.getLong("peak_queue_bytes") in 0..134_217_728)
        assertTrue(observation.getLong("snapshot_commit_count") in 0..1_000_000_000)
        val custody = observation.getJSONObject("custody_counts")
        assertEquals(setOf("lan", "relay"), custody.keys().asSequence().toSet())
        for (route in listOf("lan", "relay")) {
            val counts = custody.getJSONObject(route)
            assertTrue(counts.keys().asSequence().all { it in E2eStateProvider.ALLOWED_EVENT_COUNT_KEYS })
            assertTrue(counts.keys().asSequence().all { counts.getLong(it) in 0..1_000_000_000 })
        }
        val serialized = observation.toString()
        for (forbidden in listOf("device_id", "package", "canon", "msg_id", "title", "text", "token", "tls", "relay_url")) {
            assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
        }
    }

    @Test
    fun notificationActionObservationBlockIsClosedBoundedAndContentFree() {
        val observation = JSONObject(E2eStateProvider.snapshotJson(context))
            .getJSONObject("notification_action_observations")
        assertEquals(
            setOf(
                "invocation_pending", "invocation_dispatched", "invocation_outcome_unknown",
                "invocation_failed", "invocation_action_gone", "invocation_notification_gone",
                "invocation_expired", "execution_claimed", "execution_completed",
                "detail_active", "detail_cancelled", "latest_terminal_status",
            ),
            observation.keys().asSequence().toSet(),
        )
        observation.keys().asSequence().filterNot { it == "latest_terminal_status" }.forEach {
            assertTrue(observation.getLong(it) in 0..1_000_000_000, it)
        }
        val serialized = observation.toString()
        for (forbidden in listOf("reply_text", "title", "text", "canon_id", "action_id", "package", "component")) {
            assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
        }
    }

    @Test
    fun callCanonicalSemanticStateIsClosedWorldBoundedAndContentFree() {
        val session = "11111111-1111-4111-8111-111111111111"
        val ringing = JSONObject()
            .put("call_session_id", session)
            .put("state", "ringing")
            .put("direction", "incoming")
            .toString()
        val active = JSONObject(ringing).put("state", "active").toString()

        assertEquals("RINGING", E2eStateProvider.callSemanticState("call:$session", "ACTIVE", ringing))
        assertEquals("ACTIVE", E2eStateProvider.callSemanticState("call:$session", "ACTIVE", active))
        assertEquals("IDLE", E2eStateProvider.callSemanticState("call:$session", "CANCELLED", null))
        assertEquals(null, E2eStateProvider.callSemanticState("notification:fixture", "ACTIVE", "not-json"))

        for (malformed in listOf(
            JSONObject(ringing).put("state", "unknown").toString(),
            JSONObject(ringing).put("title", "forbidden").toString(),
            JSONObject(ringing).put("call_session_id", "22222222-2222-4222-8222-222222222222").toString(),
            "not-json",
            "{" + " ".repeat(4_097) + "}",
        )) {
            assertFailsWith<IllegalArgumentException> {
                E2eStateProvider.callSemanticState("call:$session", "ACTIVE", malformed)
            }
        }
    }

    @Test
    fun wrongSessionTokenCannotExecuteCommand() {
        val receiver = E2eControlReceiver()
        val result = receiver.executeForTest(
            context,
            E2eCommand(requestId = "wrong-token", name = "STATUS", token = "wrong"),
        )
        assertEquals("unauthorized", result.code)
    }

    @Test
    fun missingSessionTokenCannotExecuteCommand() {
        val result = E2eControlReceiver().executeForTest(
            context,
            E2eCommand(requestId = "missing-token", name = "STATUS"),
        )
        assertEquals("unauthorized", result.code)
    }

    @Test
    fun unknownCommandIsRejectedAfterAuthentication() {
        val token = E2eSessionToken.forTest(context, "allowlisted")
        val result = E2eControlReceiver().executeForTest(
            context,
            E2eCommand(requestId = "unknown", name = "SHELL", token = token),
        )
        assertEquals("forbidden", result.code)
    }

    @Test
    fun notificationFixtureControlAcceptsOnlyClosedFixtureAndOperationEnums() {
        val receiver = E2eControlReceiver()
        val token = E2eSessionToken.forTest(context, "notification-fixture-closed-world")

        for (fixture in listOf("reply", "mark_read", "auto_cancel", "persistent")) {
            for (operation in listOf("post", "update", "cancel", "reset_counters")) {
                val result = receiver.executeForTest(
                    context,
                    E2eCommand(
                        requestId = "fixture-$fixture-$operation",
                        name = "NOTIFICATION_FIXTURE",
                        token = token,
                        params = mapOf("fixture" to fixture, "operation" to operation),
                    ),
                )
                assertTrue(result.code == "ok" || result.code == "unavailable", result.toJson().toString())
            }
        }

        for (forbidden in listOf("title", "text", "package", "component", "action", "intent", "intent_extra", "reply_text")) {
            val result = receiver.executeForTest(
                context,
                E2eCommand(
                    requestId = "fixture-forbidden-$forbidden",
                    name = "NOTIFICATION_FIXTURE",
                    token = token,
                    params = mapOf("fixture" to "reply", "operation" to "post", forbidden to "arbitrary"),
                ),
            )
            assertEquals("invalid", result.code, forbidden)
        }

        for (params in listOf(
            emptyMap(),
            mapOf("fixture" to "arbitrary", "operation" to "post"),
            mapOf("fixture" to "reply", "operation" to "arbitrary"),
        )) {
            assertEquals(
                "invalid",
                receiver.executeForTest(
                    context,
                    E2eCommand("fixture-invalid", "NOTIFICATION_FIXTURE", token = token, params = params),
                ).code,
            )
        }
    }

    @Test
    fun notificationActionControlsAreClosedWorldAndContentFree() {
        val receiver = E2eControlReceiver()
        val token = E2eSessionToken.forTest(context, "notification-action-controls")
        for (operation in listOf("invoke_reply", "invoke_mark_read", "replay_last_invoke", "arm_reply", "arm_mark_read", "invoke_armed", "tap")) {
            val result = receiver.executeForTest(
                context,
                E2eCommand("mirror-$operation", "NOTIFICATION_MIRROR", token, mapOf("operation" to operation)),
            )
            assertTrue(result.code in setOf("ok", "unavailable", "not_found"), result.toJson().toString())
        }
        for (operation in listOf("pause_after_claim", "release_claim_pause")) {
            val result = receiver.executeForTest(
                context,
                E2eCommand("origin-$operation", "NOTIFICATION_ORIGIN", token, mapOf("operation" to operation)),
            )
            assertTrue(result.code in setOf("ok", "unavailable"), result.toJson().toString())
        }
        for (name in listOf("NOTIFICATION_MIRROR", "NOTIFICATION_ORIGIN")) {
            for (forbidden in listOf("title", "text", "reply_text", "canon_id", "action_id", "package", "component")) {
                val operation = if (name == "NOTIFICATION_MIRROR") "tap" else "pause_after_claim"
                assertEquals(
                    "invalid",
                    receiver.executeForTest(
                        context,
                        E2eCommand("$name-$forbidden", name, token, mapOf("operation" to operation, forbidden to "arbitrary")),
                    ).code,
                    "$name $forbidden",
                )
            }
        }
    }

    @Test
    fun pairingWaitAndSignatureCommandsAreAllowlistedButValidateInputs() {
        val token = E2eSessionToken.forTest(context, "pairing-command-allowlist")
        listOf("AWAIT_PEER_HELLO", "SIGN_CONFIRMATION", "SEND_CONFIRMATION_SIG", "AWAIT_PAIR_SIG").forEach { command ->
            val result = E2eControlReceiver().executeForTest(
                context,
                E2eCommand(requestId = command, name = command, token = token),
            )
            assertEquals("invalid", result.code, "$command must be allowlisted and reject missing parameters")
        }
    }

    @Test
    fun offlinePairingCommandsAreAllowlistedButRejectMissingClosedWorldInputs() {
        val token = E2eSessionToken.forTest(context, "offline-pairing-command-allowlist")
        listOf(
            "OFFLINE_PAIR_START",
            "OFFLINE_PAIR_JOIN",
            "OFFLINE_PAIR_CONFIRM",
            "OFFLINE_PAIR_CANCEL",
        ).forEach { command ->
            val result = E2eControlReceiver().executeForTest(
                context,
                E2eCommand(requestId = command, name = command, token = token),
            )
            assertEquals("invalid", result.code, "$command must reject missing parameters")
        }

        val query = E2eControlReceiver().executeForTest(
            context,
            E2eCommand(
                requestId = "offline-query-extra",
                name = "OFFLINE_PAIR_QUERY",
                token = token,
                params = mapOf("unexpected" to "value"),
            ),
        )
        assertEquals("invalid", query.code)
    }

    @Test
    fun secretControlPayloadNeverEntersExportedResultJson() {
        val result = E2eCommandResult(
            requestId = "secret-result",
            code = "ok",
            payload = JSONObject().put("phase", "verify_code"),
            secretPayload = "fixture-private-control-value".encodeToByteArray(),
        ).toJson().toString()
        assertFalse(result.contains("fixture-private-control-value"))
        assertFalse(result.contains("secretPayload"))
        assertTrue(result.length <= E2eCommandResult.MAX_JSON_BYTES)
    }

    @Test
    fun syntheticCallStateIsAllowlistedButRejectsMissingAndPhoneFields() {
        val token = E2eSessionToken.forTest(context, "call-state-allowlist")
        val missing = E2eControlReceiver().executeForTest(
            context,
            E2eCommand(requestId = "call-state-missing", name = "CALL_STATE", token = token),
        )
        assertEquals("invalid", missing.code)
        val forbidden = E2eControlReceiver().executeForTest(
            context,
            E2eCommand(
                requestId = "call-state-phone",
                name = "CALL_STATE",
                token = token,
                params = mapOf("state" to "ringing", "phone_number" to "+15551234567"),
            ),
        )
        assertEquals("invalid", forbidden.code)
    }

    @Test
    fun lanFaultControlRequiresAuthClosedParamsAndBoundedBoolean() {
        val receiver = E2eControlReceiver()
        val token = E2eSessionToken.forTest(context, "lan-fault-control")
        assertEquals("unauthorized", receiver.executeForTest(
            context, E2eCommand("lan-wrong-token", "SET_LAN_AVAILABLE", token = "wrong", params = mapOf("available" to "false")),
        ).code)
        assertEquals("invalid", receiver.executeForTest(
            context, E2eCommand("lan-missing", "SET_LAN_AVAILABLE", token = token),
        ).code)
        assertEquals("invalid", receiver.executeForTest(
            context, E2eCommand("lan-extra", "SET_LAN_AVAILABLE", token = token, params = mapOf("available" to "false", "ssid" to "forbidden")),
        ).code)
        assertEquals("error", receiver.executeForTest(
            context, E2eCommand("lan-invalid", "SET_LAN_AVAILABLE", token = token, params = mapOf("available" to "maybe")),
        ).code)
        assertEquals("ok", receiver.executeForTest(
            context, E2eCommand("lan-disable", "SET_LAN_AVAILABLE", token = token, params = mapOf("available" to "false")),
        ).code)
        assertTrue(context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE).getLong("lan_fault_until_ms", 0L) > System.currentTimeMillis())
        assertEquals("ok", receiver.executeForTest(
            context, E2eCommand("lan-enable", "SET_LAN_AVAILABLE", token = token, params = mapOf("available" to "true")),
        ).code)
        assertFalse(context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE).contains("lan_fault_until_ms"))
    }

    @Test
    fun stateQueryContainsNoNotificationContent() {
        val token = E2eSessionToken.forTest(context, "state-query")
        val uri = E2eStateProvider.stateUri(context).buildUpon().appendQueryParameter("token", token).build()
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        assertNotNull(cursor)
        cursor.use {
            assertTrue(it.moveToFirst())
            val state = it.getString(it.getColumnIndexOrThrow("state_json"))
            assertFalse(state.contains("title", ignoreCase = true))
            assertFalse(state.contains("text", ignoreCase = true))
            assertFalse(state.contains("ciphertext", ignoreCase = true))
            assertFalse(state.contains("nonce", ignoreCase = true))
            assertFalse(state.contains("canonical_id", ignoreCase = true))
            assertFalse(state.contains("qr", ignoreCase = true))
            assertFalse(state.contains("session_token", ignoreCase = true))
            assertFalse(state.contains("transcript", ignoreCase = true))
            assertFalse(state.contains("\"sas\"", ignoreCase = true))
            val root = JSONObject(state)
            val offline = root.getJSONObject("offline_pairing")
            assertTrue(offline.keys().asSequence().all {
                it in setOf("role", "phase", "error_code", "completed", "session_id_hash", "sas_hash")
            })
            assertFalse(root.has("device_id"))
            assertFalse(root.has("paired_peer"))
            assertTrue(root.getString("device_id_hash").matches(Regex("[0-9a-f]{64}")))
            if (!root.isNull("paired_peer_hash")) {
                assertTrue(root.getString("paired_peer_hash").matches(Regex("[0-9a-f]{64}")))
            }
            assertFalse(state.contains("tls_pin", ignoreCase = true))
            assertEquals(
                setOf("route", "phase", "route_generation", "queued_count", "queued_bytes", "receipt_at_ms", "error_code"),
                root.getJSONObject("route_evidence").keys().asSequence().toSet(),
            )
        }
    }

    @Test
    fun missingStateTokenCannotReadProvider() {
        assertFailsWith<SecurityException> {
            context.contentResolver.query(E2eStateProvider.stateUri(context), null, null, null, null)
        }
    }

    @Test
    fun wrongStateTokenCannotReadProvider() {
        val uri = E2eStateProvider.stateUri(context).buildUpon().appendQueryParameter("token", "wrong").build()
        assertFailsWith<SecurityException> {
            context.contentResolver.query(uri, null, null, null, null)
        }
    }

    @Test
    fun tokenIsInstallScopedAndPersistedOnlyThroughRunAsFile() {
        val first = E2eSessionToken.ensure(context)
        val second = E2eSessionToken.ensure(context)
        assertEquals(first, second)
        val tokenFile = context.getFileStreamPath("e2e-token")
        assertTrue(tokenFile.exists())
        assertEquals(first, tokenFile.readText())
    }

    @Test
    fun providerStartupPublishesTokenForHostBootstrap() {
        val tokenFile = context.getFileStreamPath("e2e-token")
        assertTrue(tokenFile.delete() || !tokenFile.exists())

        E2eStateProvider().attachInfo(
            context,
            ProviderInfo().apply { authority = "${context.packageName}.e2e.bootstrap" },
        )

        assertTrue(tokenFile.isFile)
        assertTrue(tokenFile.readText().isNotBlank())
    }

    @Test
    fun debugComponentsUseShellPermissionAndApplicationAuthority() {
        val intent = Intent(E2eControlReceiver.ACTION_CONTROL)
        assertEquals(E2eControlReceiver.ACTION_CONTROL, intent.action)
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, E2eControlReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertEquals(android.Manifest.permission.DUMP, receiver.permission)
        val provider = context.packageManager.resolveContentProvider(
            "${context.packageName}.e2e",
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertEquals("${context.packageName}.e2e", assertNotNull(provider).authority)
    }

    @Test
    fun shellControlIsClosedAndRejectsEverySecretHandle() = runBlocking {
        val receiver = E2eControlReceiver()
        assertEquals("unauthorized", receiver.executeForTest(context, E2eCommand("r", "STATUS")).code)
        assertEquals("forbidden", receiver.executeShellForTest(context, E2eCommand("r", "PAIR_INIT")).code)
        for (command in listOf(
            E2eCommand("r", "STATUS", token = "forbidden"),
            E2eCommand("r", "STATUS", params = mapOf("auth_input_id" to "r")),
            E2eCommand("r", "STATUS", params = mapOf("secret_input_id" to "r")),
        )) {
            assertEquals("invalid", receiver.executeShellForTest(context, command).code)
        }
    }

    @Test
    fun privateRequestHandleBindsTokenCommandAndExpiry() {
        val token = "fixture-install-token"
        val now = 2_000_000_000_000L
        val handle = E2eRequestHandle.forTest(token, "OFFLINE_PAIR_QUERY", now + 30_000L, ByteArray(16) { 7 })
        assertTrue(E2eRequestHandle.matches(token, "OFFLINE_PAIR_QUERY", handle, now))
        assertFalse(E2eRequestHandle.matches("wrong", "OFFLINE_PAIR_QUERY", handle, now))
        assertFalse(E2eRequestHandle.matches(token, "STATUS", handle, now))
        assertFalse(E2eRequestHandle.matches(token, "OFFLINE_PAIR_QUERY", handle, now + 31_000L))
        assertFalse(handle.contains(token))
    }

    @Test
    fun privateInputIsOneTimeBoundedSymlinkSafeAndCleanedOnFailure() {
        val receiver = E2eControlReceiver()
        val directory = context.getFileStreamPath("e2e-inputs").apply { mkdirs(); Os.chmod(path, 448) }
        val replay = directory.resolve("replay-handle")
        replay.writeBytes("fixture-private-value".encodeToByteArray())
        Os.chmod(replay.path, 384)
        val consumed = receiver.consumePrivateInputForTest(context, "replay-handle", "e2e-inputs")
        consumed.fill(0)
        assertFalse(replay.exists())
        assertFailsWith<IllegalArgumentException> {
            receiver.consumePrivateInputForTest(context, "replay-handle", "e2e-inputs")
        }

        val oversize = directory.resolve("oversize-handle")
        oversize.writeBytes(ByteArray(4_097))
        Os.chmod(oversize.path, 384)
        assertFailsWith<IllegalArgumentException> {
            receiver.consumePrivateInputForTest(context, "oversize-handle", "e2e-inputs")
        }
        assertFalse(oversize.exists())

        val target = context.getFileStreamPath("e2e-symlink-target").apply { writeText("fixture") }
        val link = directory.resolve("symlink-handle")
        runCatching { Files.deleteIfExists(link.toPath()) }
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertFailsWith<IllegalArgumentException> {
            receiver.consumePrivateInputForTest(context, "symlink-handle", "e2e-inputs")
        }
        assertFalse(link.exists())
        target.delete()
    }

    @Test
    fun privateResultUsesOwnerOnlyModeAndRejectsStaleOrSymlinkHandles() {
        val receiver = E2eControlReceiver()
        val directory = context.getFileStreamPath("e2e-secrets")
        val fresh = directory.resolve("fresh-result")
        Files.deleteIfExists(fresh.toPath())
        receiver.writeSecretResultForTest(context, "fresh-result", "fixture-private-result".encodeToByteArray())
        assertEquals(384, Os.lstat(fresh.path).st_mode and 511)
        assertEquals(android.os.Process.myUid(), Os.lstat(fresh.path).st_uid)
        Files.delete(fresh.toPath())

        val stale = directory.resolve("stale-result")
        stale.writeText("existing")
        Os.chmod(stale.path, 384)
        assertFailsWith<IllegalArgumentException> {
            receiver.writeSecretResultForTest(context, "stale-result", "replacement".encodeToByteArray())
        }
        assertEquals("existing", stale.readText())
        Files.delete(stale.toPath())

        val linkTarget = context.getFileStreamPath("e2e-result-link-target").apply { writeText("safe") }
        val link = directory.resolve("linked-result")
        Files.deleteIfExists(link.toPath())
        Files.createSymbolicLink(link.toPath(), linkTarget.toPath())
        assertFailsWith<IllegalArgumentException> {
            receiver.writeSecretResultForTest(context, "linked-result", "replacement".encodeToByteArray())
        }
        assertEquals("safe", linkTarget.readText())
        Files.delete(link.toPath())
        linkTarget.delete()
    }

    @Test
    fun exportedBroadcastAuthenticatesPublishesAndCleansPrivateFiles() {
        val token = E2eSessionToken.forTest(context, "exported-broadcast-boundary")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shellWord(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9._-]+")))
            return value
        }
        fun sendFromShell(name: String?, handle: String?, extras: Map<String, String> = emptyMap()) {
            val args = mutableListOf(
                "am", "broadcast",
                "-n", "${context.packageName}/${E2eControlReceiver::class.java.name}",
                "-a", E2eControlReceiver.ACTION_CONTROL,
            )
            handle?.let { args += listOf("--es", E2eControlReceiver.EXTRA_REQUEST_ID, shellWord(it)) }
            name?.let { args += listOf("--es", E2eControlReceiver.EXTRA_COMMAND, shellWord(it)) }
            extras.toSortedMap().forEach { (key, value) ->
                args += listOf("--es", shellWord(key), shellWord(value))
            }
            instrumentation.uiAutomation.executeShellCommand(args.joinToString(" ")).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
        }
        fun send(name: String, handle: String, extras: Map<String, String> = emptyMap()): JSONObject {
            val resultFile = File(context.filesDir, "e2e-results/$handle.json")
            Files.deleteIfExists(resultFile.toPath())
            sendFromShell(name, handle, extras + ("auth_input_id" to handle))
            val deadline = System.currentTimeMillis() + 5_000
            while (!resultFile.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(resultFile.exists(), "exported receiver did not finish and publish")
            return JSONObject(resultFile.readText()).also { Files.deleteIfExists(resultFile.toPath()) }
        }
        fun privateFile(bucket: String, handle: String, value: ByteArray) {
            val directory = File(context.filesDir, bucket).apply { mkdirs(); Os.chmod(path, 448) }
            val file = File(directory, handle)
            Files.deleteIfExists(file.toPath())
            file.writeBytes(value); Os.chmod(file.path, 384)
        }
        fun handle(command: String, expiry: Long = System.currentTimeMillis() + 30_000) =
            E2eRequestHandle.forTest(token, command, expiry, ByteArray(16) { (it + command.length).toByte() })

        val wrong = handle("SHELL")
        privateFile("e2e-auth", wrong, token.encodeToByteArray())
        assertEquals("forbidden", send("SHELL", wrong).getString("code"))
        assertFalse(File(context.filesDir, "e2e-auth/$wrong").exists())

        val expired = handle("SHELL", System.currentTimeMillis() - 1)
        privateFile("e2e-auth", expired, token.encodeToByteArray())
        assertEquals("unauthorized", send("SHELL", expired).getString("code"))
        assertFalse(File(context.filesDir, "e2e-auth/$expired").exists())

        val replay = handle("SHELL")
        privateFile("e2e-auth", replay, token.encodeToByteArray())
        assertEquals("forbidden", send("SHELL", replay).getString("code"))
        assertEquals("unauthorized", send("SHELL", replay).getString("code"))

        val confirm = handle("OFFLINE_PAIR_CONFIRM")
        privateFile("e2e-auth", confirm, token.encodeToByteArray())
        privateFile("e2e-inputs", confirm, "fixture-session-value".encodeToByteArray())
        val failed = send("OFFLINE_PAIR_CONFIRM", confirm, mapOf("secret_input_id" to confirm))
        assertEquals("error", failed.getString("code"))
        assertTrue(failed.optString("detail").length <= 256)
        assertFalse(File(context.filesDir, "e2e-auth/$confirm").exists())
        assertFalse(File(context.filesDir, "e2e-inputs/$confirm").exists())

        val query = handle("OFFLINE_PAIR_QUERY")
        privateFile("e2e-auth", query, token.encodeToByteArray())
        assertEquals("ok", send("OFFLINE_PAIR_QUERY", query).getString("code"))
        val output = File(context.filesDir, "e2e-secrets/$query")
        assertTrue(output.exists())
        Files.deleteIfExists(output.toPath())
        assertFalse(output.exists())

        val malformed = File(context.filesDir, "e2e-results/missing-request.json")
        Files.deleteIfExists(malformed.toPath())
        sendFromShell(null, null, mapOf("auth_input_id" to "missing-request"))
        val deadline = System.currentTimeMillis() + 5_000
        while (!malformed.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(malformed.exists(), "malformed exported intent did not finish")
        val malformedJson = JSONObject(malformed.readText())
        assertEquals("unauthorized", malformedJson.getString("code"))
        assertTrue(malformed.readText().length <= E2eCommandResult.MAX_JSON_BYTES)
        Files.deleteIfExists(malformed.toPath())
    }
}
