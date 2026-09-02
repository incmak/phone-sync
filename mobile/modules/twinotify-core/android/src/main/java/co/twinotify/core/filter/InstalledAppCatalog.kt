package co.twinotify.core.filter

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.text.Collator

internal data class InstalledAppCandidate(
    val packageName: String,
    val displayName: String,
    val category: Int,
)

internal data class FilterableApp(
    val packageName: String,
    val displayName: String,
    val defaultFiltered: Boolean,
)

internal object InstalledAppCatalog {
    fun load(context: Context): List<FilterableApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0),
        ).mapNotNull { resolveInfo ->
            val applicationInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
            val packageName = applicationInfo.packageName?.trim().orEmpty()
            if (packageName.isEmpty()) return@mapNotNull null
            InstalledAppCandidate(
                packageName = packageName,
                displayName = packageManager.getApplicationLabel(applicationInfo).toString(),
                category = applicationInfo.category,
            )
        }
        return normalize(candidates, context.packageName)
    }

    fun defaultFilteredPackages(context: Context): Set<String> =
        load(context).asSequence()
            .filter(FilterableApp::defaultFiltered)
            .map(FilterableApp::packageName)
            .toSet()

    internal fun normalize(
        candidates: List<InstalledAppCandidate>,
        selfPackage: String,
    ): List<FilterableApp> {
        val collator = Collator.getInstance()
        return candidates.asSequence()
            .filter { it.packageName != selfPackage }
            .distinctBy(InstalledAppCandidate::packageName)
            .map { candidate ->
                FilterableApp(
                    packageName = candidate.packageName,
                    displayName = candidate.displayName.trim().ifEmpty { candidate.packageName },
                    defaultFiltered = candidate.category == ApplicationInfo.CATEGORY_AUDIO,
                )
            }
            .sortedWith { first, second ->
                collator.compare(first.displayName, second.displayName).takeIf { it != 0 }
                    ?: first.packageName.compareTo(second.packageName)
            }
            .toList()
    }
}
