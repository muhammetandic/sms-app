package com.oaktech.smsapp.permission

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The flag that separates a first launch from a revocation.
 *
 * If it never sticks, every role loss would re-run the first-launch gate and
 * hide the local message store; if it sticks too early, a user who denies the
 * prompt would be dropped into an app that cannot receive anything.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionSetupTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a fresh install has not completed setup`() {
        assertFalse(PermissionSetup.everGranted(context))
    }

    @Test
    fun `a grant is remembered across instances`() {
        PermissionSetup.markGranted(context)

        assertTrue(PermissionSetup.everGranted(context))
    }
}
