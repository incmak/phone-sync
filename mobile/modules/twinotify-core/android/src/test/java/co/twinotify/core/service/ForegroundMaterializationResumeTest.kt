package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundMaterializationResumeTest {
    @Test
    fun foregroundRefreshesPermissionAndOnlyResumesAnActiveService() {
        val permissions = mutableListOf<Boolean>()
        var requests = 0

        resumePermissionBlockedMaterializationOnForeground(
            postPermissionGranted = true,
            setPostPermission = { granted -> permissions += granted },
            requestActiveMaterialization = { requests += 1 },
        )
        resumePermissionBlockedMaterializationOnForeground(
            postPermissionGranted = false,
            setPostPermission = { granted -> permissions += granted },
            requestActiveMaterialization = null,
        )

        assertEquals(listOf(true, false), permissions)
        assertEquals(1, requests)
    }
}
