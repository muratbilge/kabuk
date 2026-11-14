package app.kabuk

import app.kabuk.term.InstallMachine
import app.kabuk.term.InstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxInstallStateTest {
    @Test fun consentOnlyOnTheFirstPhoneTab() {
        val m = InstallMachine(offered = false, installed = false)
        assertTrue(m.phoneTabOpened())
        assertEquals(InstallState.Offer, m.state)
        assertTrue(m.offered)
        m.notNow()
        assertEquals(InstallState.Idle, m.state)
        assertFalse("Not now: asked again only from the menu", m.phoneTabOpened())
        assertEquals(InstallState.Idle, m.state)
        assertTrue(m.menu())
        assertEquals(InstallState.Offer, m.state)
    }

    @Test fun noConsentWhenAlreadyOfferedOrInstalled() {
        assertFalse(InstallMachine(offered = true, installed = false).phoneTabOpened())
        assertFalse(InstallMachine(offered = false, installed = true).phoneTabOpened())
    }

    @Test fun progressThenDone() {
        val m = InstallMachine(false, false)
        m.phoneTabOpened()
        assertTrue(m.install())
        assertEquals(InstallState.Downloading(0, -1), m.state)
        assertNull(m.fraction())
        m.downloaded(50, 100)
        assertEquals(0.35f, m.fraction()!!, 0.001f)
        m.verifying()
        assertEquals(InstallState.Verifying, m.state)
        m.extracted(1, 4)
        assertEquals(InstallState.Extracting(1, 4), m.state)
        assertEquals(0.775f, m.fraction()!!, 0.001f)
        assertFalse("no second install while running", m.install())
        assertFalse("menu is a no-op while running", m.menu())
        m.finished()
        assertEquals(InstallState.Done, m.state)
        assertTrue(m.installed)
        assertFalse(m.phoneTabOpened())
        m.dismiss()
        assertEquals(InstallState.Idle, m.state)
    }

    @Test fun failureThenRetry() {
        val m = InstallMachine(false, false)
        m.phoneTabOpened(); m.install(); m.downloaded(10, 100)
        m.failed("No internet connection")
        assertEquals(InstallState.Failed("No internet connection"), m.state)
        assertFalse(m.installed)
        assertTrue("Retry", m.install())
        assertEquals(InstallState.Downloading(0, -1), m.state)
        m.downloaded(100, 100); m.verifying(); m.extracted(4, 4); m.finished()
        assertEquals(InstallState.Done, m.state)
    }

    @Test fun failedCanBeClosed() {
        val m = InstallMachine(false, false)
        m.phoneTabOpened(); m.install(); m.failed("x"); m.dismiss()
        assertEquals(InstallState.Idle, m.state)
        assertFalse(m.install())
    }

    @Test fun cancelIgnoresLateProgress() {
        val m = InstallMachine(false, false)
        m.phoneTabOpened(); m.install(); m.downloaded(10, 100)
        assertTrue(m.cancel())
        assertEquals(InstallState.Idle, m.state)
        m.downloaded(20, 100); m.verifying(); m.extracted(1, 2); m.failed("late"); m.finished()
        assertEquals(InstallState.Idle, m.state)
        assertFalse(m.installed)
        assertFalse(m.cancel())
    }

    @Test fun installOnlyFromTheSheetOrAfterAFailure() {
        val m = InstallMachine(false, false)
        assertFalse(m.install())
        m.phoneTabOpened(); m.notNow()
        assertFalse(m.install())
    }

    @Test fun repairRunsOverAnInstalledPrefix() {
        val m = InstallMachine(offered = true, installed = true)
        assertTrue(m.repair())
        assertTrue(m.repairing)
        assertEquals(InstallState.Downloading(0, -1), m.state)
        assertFalse("not twice while running", m.repair())
        m.failed("No internet connection")
        assertTrue("retry stays a repair", m.install())
        assertTrue(m.repairing)
        m.verifying(); m.extracted(1, 1); m.finished()
        assertEquals(InstallState.Done, m.state)
        m.dismiss()
        assertTrue(m.menu())
        assertTrue(m.install())
        assertFalse("an install from the sheet is no repair", m.repairing)
    }

    /** usr there, login or bash missing: the Repair sheet instead of the fresh Install sheet, once per process. */
    @Test fun brokenPrefixOffersRepairOnce() {
        val m = InstallMachine(offered = true, installed = false)
        assertTrue(m.phoneTabOpened(broken = true))
        assertEquals(InstallState.OfferRepair, m.state)
        assertFalse("no Install sheet on top", m.phoneTabOpened(broken = true))
        m.notNow()
        assertEquals(InstallState.Idle, m.state)
        assertFalse("once per process", m.phoneTabOpened(broken = true))
        assertTrue("Repair from the menu still works", m.repair())
        assertTrue(m.repairing)
        m.verifying(); m.extracted(1, 1); m.finished()
        assertTrue(m.installed)
        // never offered before (fresh install state) but broken: still the Repair sheet, not Install
        val fresh = InstallMachine(offered = false, installed = false)
        assertTrue(fresh.phoneTabOpened(broken = true))
        assertEquals(InstallState.OfferRepair, fresh.state)
        assertFalse(fresh.install())
    }
}
