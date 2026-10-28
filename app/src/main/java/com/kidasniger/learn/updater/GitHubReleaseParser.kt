package com.kidasniger.learn.updater

import org.json.JSONObject
import java.net.URL

internal object GitHubReleaseParser {
    fun parse(json: String): GitHubRelease {
        val root = try { JSONObject(json) } catch (error: Exception) {
            throw IllegalArgumentException("La réponse GitHub n'est pas un JSON valide.", error)
        }
        if (root.optBoolean("draft", false)) throw IllegalArgumentException("La release est encore en brouillon.")
        if (root.optBoolean("prerelease", false)) throw IllegalArgumentException("Les prépublications ne sont pas installées automatiquement.")
        val tag = root.optString("tag_name").trim()
        if (SemanticVersion.parse(tag) == null) throw IllegalArgumentException("Version distante invalide : $tag")
        val releaseUrl = root.optString("html_url").trim()
        require(isGitHubHttpsUrl(releaseUrl)) { "URL de release GitHub invalide." }

        val array = root.optJSONArray("assets")
            ?: throw IllegalArgumentException("La release ne contient pas de liste d'artefacts.")
        val assets = ArrayList<ReleaseAsset>(array.length())
        for (index in 0 until array.length()) {
            val asset = array.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            val url = asset.optString("browser_download_url").trim()
            val size = asset.optLong("size", -1L)
            if (name.isBlank() || !isGitHubHttpsUrl(url) || size < 0L) continue
            val rawDigest = asset.optString("digest").trim()
            val digest = when {
                rawDigest.isBlank() || rawDigest == "null" -> null
                rawDigest.matches(Regex("(?i)^sha256:[a-f0-9]{64}$")) -> rawDigest.substringAfter(':').lowercase()
                else -> throw IllegalArgumentException("L'empreinte de l'artefact $name n'est pas un SHA-256 valide.")
            }
            assets += ReleaseAsset(name, size, url,
                asset.optString("content_type").takeIf { it.isNotBlank() && it != "null" }, digest)
        }

        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val apk = apkAssets.firstOrNull { it.name.contains("learn", ignoreCase = true) }
            ?: apkAssets.singleOrNull()
            ?: throw IllegalArgumentException("La release $tag ne contient pas un APK Learn identifiable.")
        if (apk.size <= 0L) throw IllegalArgumentException("La taille de l'APK publié est invalide.")
        val checksum = assets.firstOrNull {
            it.name.equals("${apk.name}.sha256", ignoreCase = true) ||
                it.name.equals("${apk.name}.sha256sum", ignoreCase = true)
        }
        return GitHubRelease(
            tagName = tag,
            name = root.optString("name").takeIf { it.isNotBlank() && it != "null" },
            body = root.optString("body").takeIf { it.isNotBlank() && it != "null" },
            htmlUrl = releaseUrl,
            assets = assets,
            checksumUrl = checksum?.downloadUrl
        )
    }

    fun parseChecksum(content: String): String? {
        val token = content.trim().lineSequence().firstOrNull()?.trim()
            ?.split(Regex("""\s+"""))?.firstOrNull()?.removePrefix("*")?.lowercase() ?: return null
        return token.takeIf { it.matches(Regex("^[a-f0-9]{64}$")) }
    }

    private fun isGitHubHttpsUrl(value: String): Boolean {
        return try {
            val url = URL(value)
            url.protocol.equals("https", true) && url.host.equals("github.com", true)
        } catch (_: Exception) { false }
    }
}
