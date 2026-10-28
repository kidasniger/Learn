package com.kidasniger.learn.updater

import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseParserTest {
    private fun validRelease(extraAssetJson: String = ""): String = """
        {
          "tag_name":"v1.4.0",
          "name":"Learn v1.4.0",
          "body":"## Nouveautés\\n- Correctifs",
          "html_url":"https://github.com/kidasniger/Learn/releases/tag/v1.4.0",
          "draft":false,
          "prerelease":false,
          "assets":[
            {
              "name":"Learn-v1.4.0.apk",
              "size":12345,
              "browser_download_url":"https://github.com/kidasniger/Learn/releases/download/v1.4.0/Learn-v1.4.0.apk",
              "content_type":"application/vnd.android.package-archive",
              "digest":"sha256:${"a".repeat(64)}"
            }
            $extraAssetJson
          ]
        }
    """.trimIndent()

    @Test fun parsesReleaseAndSha256Digest() {
        val release = GitHubReleaseParser.parse(validRelease())
        assertEquals("v1.4.0", release.tagName)
        assertEquals("Learn v1.4.0", release.name)
        assertEquals(12345L, release.assets.single().size)
        assertEquals("a".repeat(64), release.assets.single().digest)
        assertNull(release.checksumUrl)
    }

    @Test fun supportsChecksumSidecarWhenAssetDigestIsNotPublished() {
        val digestMember = "\"digest\":\"sha256:${"a".repeat(64)}\""
        val json = validRelease().replace(digestMember, "\"digest\":\"\"")
            .replace(
                """          ]
        }""",
                """            ,{
              "name":"Learn-v1.4.0.apk.sha256",
              "size":88,
              "browser_download_url":"https://github.com/kidasniger/Learn/releases/download/v1.4.0/Learn-v1.4.0.apk.sha256",
              "content_type":"text/plain"
            }
          ]
        }"""
            )
        val release = GitHubReleaseParser.parse(json)
        assertEquals("https://github.com/kidasniger/Learn/releases/download/v1.4.0/Learn-v1.4.0.apk.sha256", release.checksumUrl)
        assertEquals("b".repeat(64), GitHubReleaseParser.parseChecksum("b".repeat(64) + "  Learn-v1.4.0.apk"))
    }

    @Test fun rejectsInvalidJsonVersionAndPreRelease() {
        assertThrows(IllegalArgumentException::class.java) { GitHubReleaseParser.parse("{") }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseParser.parse(validRelease().replace("\"tag_name\":\"v1.4.0\"", "\"tag_name\":\"latest\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseParser.parse(validRelease().replace("\"prerelease\":false", "\"prerelease\":true"))
        }
    }

    @Test fun rejectsMissingApkAndInvalidDigest() {
        val noAssets = validRelease().replace(Regex("""(?s)"assets":\[.*?\]"""), "\"assets\":[]")
        assertThrows(IllegalArgumentException::class.java) { GitHubReleaseParser.parse(noAssets) }
        val badDigest = validRelease().replace("sha256:${"a".repeat(64)}", "sha1:bad")
        assertThrows(IllegalArgumentException::class.java) { GitHubReleaseParser.parse(badDigest) }
    }
}
