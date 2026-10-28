package com.kidasniger.learn

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun freshInstallMainActivityStartsWithoutCrashing() {
        context.getSharedPreferences("learn_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("welcome_completed", false)
            .apply()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("MainActivity window should exist after launch", activity.window)
                assertFalse("MainActivity should not finish immediately", activity.isFinishing)
                assertFalse("MainActivity should not be destroyed immediately", activity.isDestroyed)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun returningUserSurvivesAutomaticUpdateCheck() {
        context.getSharedPreferences("learn_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("welcome_completed", true)
            .apply()
        context.getSharedPreferences("learn_update_preferences", Context.MODE_PRIVATE).edit().clear().apply()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            // MainActivity delays the automatic GitHub release check by 1.2 seconds.
            Thread.sleep(3_500L)
            scenario.onActivity { activity ->
                assertFalse("Activity must remain alive after the automatic update check", activity.isFinishing)
                assertFalse("Activity must remain created after the automatic update check", activity.isDestroyed)
            }
        }
    }
}
