package co.twinotify.core.detail

import android.content.Context

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

    // The origin package is arbitrary peer data, so it cannot be enumerated in manifest <queries>.
    // API 33's front-door IntentSender intentionally defers existence and launcher checks until
    // send time and is not restricted by Android package visibility.
    override fun isInstalled(packageName: String): Boolean = packageName.isNotBlank()

    override fun hasLauncher(packageName: String): Boolean = packageName.isNotBlank()

    override fun launch(packageName: String): Boolean = runCatching {
        packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
            appContext,
            0,
            null,
            null,
            null,
        )
        true
    }.getOrDefault(false)
}
