package app.kabuk

import app.kabuk.Notifications.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsTest {
    private fun step(sdk: Int = 35, target: Int = 28, enabled: Boolean = false, fresh: Boolean = false, offered: Boolean = false) =
        Notifications.nextStep(sdk, target, enabled, fresh, offered)

    @Test fun onAlready() = assertEquals(Step.NONE, step(enabled = true, fresh = true))
    @Test fun belowAndroid13NothingToDo() = assertEquals(Step.NONE, step(sdk = 32, fresh = true))
    @Test fun modernTargetAsksInOnCreate() = assertEquals(Step.NONE, step(target = 35))
    @Test fun freshInstallWaitsForTheSystemPrompt() = assertEquals(Step.WAIT_FOR_SYSTEM_PROMPT, step(fresh = true))
    @Test fun offWithoutPromptOffersSettings() = assertEquals(Step.OFFER_SETTINGS, step())
    @Test fun offeredOnlyOnce() = assertEquals(Step.NONE, step(offered = true))
}
