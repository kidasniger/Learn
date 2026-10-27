package com.kidasniger.learn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnLaunchTest {
    @Test
    fun activityLaunchesWithoutModelInstalled() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertNotNull(activity.window)
                assertNotNull(activity.window.decorView)
            }
        } finally {
            scenario.close()
        }
    }
}
