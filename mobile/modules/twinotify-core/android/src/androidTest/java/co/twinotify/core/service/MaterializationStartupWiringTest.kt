package co.twinotify.core.service

import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertIs
import org.junit.Test

class MaterializationStartupWiringTest {
    @Test
    fun productionStartupUsesDurableAlarmScheduler() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertIs<AlarmManagerMaterializationScheduler>(materializationStartupScheduler(context))
    }
}
