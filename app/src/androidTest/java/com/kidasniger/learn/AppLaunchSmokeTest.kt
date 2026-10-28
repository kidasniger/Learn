package com.kidasniger.learn

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @Test
    fun mainActivityStartsAndRemainsResumedWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("MainActivity window should exist after launch", activity.window)
                assertFalse("MainActivity should not finish immediately", activity.isFinishing)
                assertFalse("MainActivity should not be destroyed immediately", activity.isDestroyed)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }
}
