package com.oaktech.smsapp.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a [Dispatchers.Main] test dispatcher for the duration of a test.
 *
 * `viewModelScope` dispatches on Main, which does not exist off-device, so a
 * ViewModel's coroutines simply never run in a plain JVM test.
 *
 * Pass [testDispatcher] to `runTest` so both share one scheduler. Setting Main
 * in `@Before` with a separately created dispatcher gives it a different
 * scheduler than the one `runTest` uses, so work posted to Main is never
 * advanced and every flow assertion times out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}