package com.oaktech.smsapp.data.telephony

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.testing.MainDispatcherRule
import java.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ProviderChangeObserverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Creates an observer whose import launches into [scope], so the test scheduler drives it. */
    private fun TestScope.observer(debounceMillis: Long, imports: () -> Unit) = ProviderChangeObserver(
        context = context,
        onProviderChanged = { imports() },
        scope = this,
        debounceMillis = debounceMillis,
    )

    @Test
    fun `a change this process made is ignored`() = runTest(mainDispatcherRule.testDispatcher) {
        var imports = 0
        val observer = observer(debounceMillis = 0L) { imports += 1 }

        // The app's own writer triggers the observer; re-importing rows it just
        // mirrored would be a pointless full scan.
        observer.contentObserver.onChange(true, null)

        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertEquals(0, imports)
        observer.stop()
    }

    @Test
    fun `an external change triggers exactly one import`() = runTest(mainDispatcherRule.testDispatcher) {
        var imports = 0
        val observer = observer(debounceMillis = 0L) { imports += 1 }

        // A burst of row writes, as a multi-part message or an MMS with
        // companion rows, must coalesce into a single import: each fires the
        // observer, but the debounce only lets the last one through.
        observer.contentObserver.onChange(false, null)
        observer.contentObserver.onChange(false, null)
        observer.contentObserver.onChange(false, null)

        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertEquals(1, imports)
        observer.stop()
    }

    @Test
    fun `a second burst some time later imports again`() = runTest(mainDispatcherRule.testDispatcher) {
        var imports = 0
        val observer = observer(debounceMillis = 1_000L) { imports += 1 }

        observer.contentObserver.onChange(false, null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500L))
        advanceUntilIdle()
        assertEquals(1, imports)

        // After the debounce window closes, a fresh change starts a new import.
        observer.contentObserver.onChange(false, null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500L))
        advanceUntilIdle()
        assertEquals(2, imports)
        observer.stop()
    }

    @Test
    fun `stop cancels the pending import`() = runTest(mainDispatcherRule.testDispatcher) {
        var imports = 0
        val observer = observer(debounceMillis = 1_000L) { imports += 1 }

        observer.contentObserver.onChange(false, null)
        observer.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500L))
        advanceUntilIdle()

        assertEquals(0, imports)
    }
}