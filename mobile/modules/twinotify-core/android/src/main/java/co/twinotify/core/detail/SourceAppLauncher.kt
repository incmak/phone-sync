package co.twinotify.core.detail

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

enum class SourceLaunchResult {
    Launched,
    PackageMissing,
    NoLauncher,
    LaunchFailed,
}

interface SourceAppPlatform {
    fun isInstalled(packageName: String): Boolean
    fun hasLauncher(packageName: String): Boolean
    fun launch(packageName: String): Boolean
}

class SourceAppLauncher(private val platform: SourceAppPlatform) {
    fun launch(packageName: String): SourceLaunchResult {
        if (!runCatching { platform.isInstalled(packageName) }.getOrDefault(false)) {
            return SourceLaunchResult.PackageMissing
        }
        if (!runCatching { platform.hasLauncher(packageName) }.getOrDefault(false)) {
            return SourceLaunchResult.NoLauncher
        }
        return if (runCatching { platform.launch(packageName) }.getOrDefault(false)) {
            SourceLaunchResult.Launched
        } else {
            SourceLaunchResult.LaunchFailed
        }
    }
}

class AndroidSourceAppPlatform(context: Context) : SourceAppPlatform {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override fun isInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun hasLauncher(packageName: String): Boolean =
        packageManager.getLaunchIntentForPackage(packageName)?.resolveActivity(packageManager) != null

    override fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return true
    }
}
