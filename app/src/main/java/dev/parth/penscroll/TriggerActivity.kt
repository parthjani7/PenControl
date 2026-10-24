package dev.parth.penscroll

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible entry point behind the launcher icon, which Air Actions launches for
 * "Hold down Pen button to" → PenScroll. A tap on the icon from the home screen opens
 * settings; any other launch counts as a pen long-press. Finishes immediately.
 */
class TriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = referrer?.host
        val service = PenScrollService.instance
        when {
            service == null -> {
                Toast.makeText(this, R.string.enable_service_first, Toast.LENGTH_LONG).show()
                openSettings()
            }
            caller == packageName || caller in homePackages() -> openSettings()
            // Give the previous app a moment to regain focus before any gesture starts.
            else -> service.onLongPress(delayMs = 300)
        }
        finish()
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun homePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }
}
