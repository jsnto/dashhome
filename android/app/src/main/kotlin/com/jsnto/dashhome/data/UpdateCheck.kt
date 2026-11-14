package com.jsnto.dashhome.data

import org.json.JSONObject

/**
 * The app's self-update support (pure, JVM-testable, no network, no
 * Android): how a dotted version string compares to another, and how the
 * public GitHub `/releases/latest` response is parsed into the two facts
 * the settings "Update" section needs (the release's tag and the APK
 * asset to download).
 *
 * The app is sideloaded as an APK (there is no store), so "update to
 * latest" means fetching the newest public release of the `jsnto/dashhome`
 * repository and installing its APK asset. The network + download +
 * install plumbing lives in [com.jsnto.dashhome.ui.UpdateManager]; this
 * file is the version/ordering truth and the response shape it feeds.
 */

/**
 * Compares two dotted version strings NUMERICALLY, per dot-separated
 * component, so that `0.10.0 > 0.9.0` (a plain string compare would say
 * the opposite: `"0.10.0" < "0.9.0"` because `"1" < "9"` at index 1).
 *
 * - A missing trailing component counts as `0`, so `"1.0" == "1.0.0"` and
 *   `"1.0.1" > "1.0"`.
 * - A component that is not a plain, non-negative integer (for example a
 *   pre-release suffix such as `"2rc"`) falls back to a string compare for
 *   THAT component only, so mixed shapes still order deterministically.
 *
 * The result is the sign of (a - b): negative when [a] is older, zero when
 * the versions are equal, positive when [a] is newer.
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".")
    val pb = b.split(".")
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val ca = pa.getOrNull(i) ?: "0"
        val cb = pb.getOrNull(i) ?: "0"
        val result = compareVersionComponent(ca, cb)
        if (result != 0) return result
    }
    return 0
}

/**
 * Compares one version component: numerically when both sides are plain
 * non-negative integers (the common case, and what makes `10 > 9`),
 * otherwise by string order so non-numeric suffixes still compare.
 */
private fun compareVersionComponent(a: String, b: String): Int {
    val na = a.toIntOrNull()
    val nb = b.toIntOrNull()
    return if (na != null && nb != null) na.compareTo(nb) else a.compareTo(b)
}

/**
 * Strips a release tag's leading version marker: `"v0.2.0"` -> `"0.2.0"`,
 * `"V0.2.0"` -> `"0.2.0"`, `"0.2.0"` -> `"0.2.0"` (a tag with no marker is
 * its own version). Surrounding whitespace is trimmed. A tag with no
 * marker is returned unchanged.
 */
fun releaseVersion(tag: String): String {
    val trimmed = tag.trim()
    return when {
        trimmed.startsWith("v", ignoreCase = true) -> trimmed.drop(1)
        else -> trimmed
    }
}

/**
 * Whether the release [tag] (its version, [releaseVersion]) is STRICTLY
 * newer than [currentVersion]. Equal (the running build IS the latest) or
 * older is not an update, so the update action stays disabled.
 */
fun releaseIsNewer(tag: String, currentVersion: String): Boolean =
    compareVersions(releaseVersion(tag), currentVersion) > 0

/**
 * One asset of a GitHub release: its file name and the url to download it
 * from. (The API response carries many more fields per asset; the parser
 * ignores the ones the app does not use.)
 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

/**
 * The public GitHub `/releases/latest` response, narrowed to the fields
 * the app uses: the release's tag and its assets. Unknown fields (the
 * dozens of metadata fields the API returns) are ignored on parse, and a
 * response with no `assets` field at all reads as an empty list.
 */
data class LatestRelease(
    val tagName: String,
    val assets: List<ReleaseAsset> = emptyList(),
)

/**
 * Parses a `/releases/latest` response body into a [LatestRelease]. The
 * body is the shape GitHub returns (a `tag_name` string and an `assets`
 * array of objects carrying, among other fields, `name` and
 * `browser_download_url`). Throws when the body is not a JSON object with
 * a `tag_name` (the caller turns that into a plain "couldn't check"
 * message).
 */
fun parseLatestRelease(json: String): LatestRelease {
    val o = JSONObject(json) // throws JSONException on a non-object body
    val tag = o.optString("tag_name", "")
    if (tag.isBlank()) throw Exception("the response has no release tag_name")
    val assets = mutableListOf<ReleaseAsset>()
    o.optJSONArray("assets")?.let { arr ->
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.isBlank()) continue
            assets += ReleaseAsset(name, a.optString("browser_download_url", ""))
        }
    }
    return LatestRelease(tag, assets)
}

/**
 * Picks the asset to install: the first asset whose name ends in `.apk`
 * (case-insensitive). The file name is NOT hard-coded - a release with a
 * differently named APK still installs. `null` when the release carries no
 * `.apk` asset (the caller reports "no APK to install").
 */
fun pickApkAsset(release: LatestRelease): ReleaseAsset? =
    release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
