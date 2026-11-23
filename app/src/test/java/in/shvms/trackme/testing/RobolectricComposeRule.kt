package `in`.shvms.trackme.testing

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows

/**
 * TASK-370: Registers [ComponentActivity] in Robolectric's ShadowPackageManager
 * before [createComposeRule] launches it, so `:app:testReleaseUnitTest` can host Compose
 * without shipping `<activity android:name="androidx.activity.ComponentActivity" />` in
 * the production release manifest.
 */
fun createRobolectricComposeRule(): ComposeContentTestRule {
    val delegate = createComposeRule()
    return object : ComposeContentTestRule by delegate {
        override fun apply(base: Statement, description: Description): Statement {
            val wrapped = delegate.apply(base, description)
            return object : Statement() {
                override fun evaluate() {
                    val app = ApplicationProvider.getApplicationContext<Application>()
                    val info = ActivityInfo().apply {
                        name = ComponentActivity::class.java.name
                        packageName = app.packageName
                        theme = android.R.style.Theme_Material_Light_NoActionBar
                    }
                    Shadows.shadowOf(app.packageManager).addOrUpdateActivity(info)
                    wrapped.evaluate()
                }
            }
        }
    }
}
