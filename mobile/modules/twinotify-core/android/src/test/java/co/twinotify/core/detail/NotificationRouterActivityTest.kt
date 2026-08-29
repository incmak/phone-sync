package co.twinotify.core.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class NotificationRouterActivityTest {
    @Test
    fun sourceLaunchWinsWithoutOpeningTwinotify() = runTest {
        val fallbacks = mutableListOf<String>()
        val router = NotificationTapRouter(
            load = NotificationRouteLoader { NotificationRouteDetail("com.example") },
            source = NotificationSourceLauncher { SourceLaunchResult.Launched },
            fallback = NotificationFallbackLauncher { fallbacks += it; true },
        )

        assertEquals(NotificationTapResult.SourceOpened, router.route(DETAIL_ID))
        assertEquals(emptyList(), fallbacks)
    }

    @Test
    fun absentNoLauncherAndLaunchFailureOpenOpaqueFallbackRoute() = runTest {
        listOf(
            SourceLaunchResult.PackageMissing,
            SourceLaunchResult.NoLauncher,
            SourceLaunchResult.LaunchFailed,
        ).forEach { sourceResult ->
            val fallbacks = mutableListOf<String>()
            val router = NotificationTapRouter(
                load = NotificationRouteLoader { NotificationRouteDetail("com.example") },
                source = NotificationSourceLauncher { sourceResult },
                fallback = NotificationFallbackLauncher { fallbacks += it; true },
            )

            assertEquals(NotificationTapResult.FallbackOpened, router.route(DETAIL_ID))
            assertEquals(listOf(DETAIL_ID), fallbacks)
        }
    }

    @Test
    fun missingCacheUsesDetailScreenButInvalidIdentityDoesNothing() = runTest {
        val fallbacks = mutableListOf<String>()
        val router = NotificationTapRouter(
            load = NotificationRouteLoader { null },
            source = NotificationSourceLauncher { error("missing cache cannot launch source") },
            fallback = NotificationFallbackLauncher { fallbacks += it; true },
        )

        assertEquals(NotificationTapResult.FallbackOpened, router.route(DETAIL_ID))
        assertEquals(NotificationTapResult.InvalidDetail, router.route("canon:must-not-be-navigation"))
        assertEquals(listOf(DETAIL_ID), fallbacks)
    }

    @Test
    fun failedFallbackStillReturnsAClosedTerminalResult() = runTest {
        val router = NotificationTapRouter(
            load = NotificationRouteLoader { null },
            source = NotificationSourceLauncher { error("unused") },
            fallback = NotificationFallbackLauncher { false },
        )

        assertEquals(NotificationTapResult.Unavailable, router.route(DETAIL_ID))
    }

    private companion object {
        const val DETAIL_ID = "11111111-1111-4111-8111-111111111111"
    }
}
