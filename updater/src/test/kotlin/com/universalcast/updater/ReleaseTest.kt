package com.universalcast.updater

import kotlin.test.*

class ReleaseTest {
    @Test fun versionOrdering() {
        assertTrue(Versions.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("2", "1.9"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.0-debug"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
        assertFalse(Versions.isNewer("0.9.9", "1.0.0"))
        assertFalse(Versions.isNewer("nightly", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.1", "garbage"))
    }

    private fun release(draft: Boolean = false, pre: Boolean = false, digest: String = "sha256:" + "a".repeat(64)) = """
        {"tag_name":"v1.2.0","draft":$draft,"prerelease":$pre,"body":"Notes",
         "assets":[
          {"name":"UniversalCast-Sender-1.2.0.apk","state":"uploaded","size":10,"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/s.apk"},
          {"name":"UniversalCast-Receiver-1.2.0.apk","state":"uploaded","size":20,"digest":"$digest","browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/r.apk"},
          {"name":"notes.txt","size":1,"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/notes.txt"}]}
    """

    @Test fun picksAssetForThisApp() {
        val r = assertNotNull(ReleaseParser.parse(release(), "universalcast-receiver"))
        assertEquals("1.2.0", r.version)
        assertEquals("UniversalCast-Receiver-1.2.0.apk", r.assetName)
        assertEquals(20, r.sizeBytes)
        assertEquals("a".repeat(64), r.sha256)
        val s = assertNotNull(ReleaseParser.parse(release(), "universalcast-sender"))
        assertEquals("UniversalCast-Sender-1.2.0.apk", s.assetName)
        assertNull(s.sha256)
    }

    @Test fun ignoresDraftsPrereleasesAndMissingAssets() {
        assertNull(ReleaseParser.parse(release(draft = true), "universalcast-receiver"))
        assertNull(ReleaseParser.parse(release(pre = true), "universalcast-receiver"))
        assertNull(ReleaseParser.parse(release(), "other-app"))
    }

    @Test fun badDigestIsIgnored() {
        assertNull(ReleaseParser.parse(release(digest = "sha256:xyz"), "universalcast-receiver")!!.sha256)
    }

    @Test fun trustedHosts() {
        assertTrue(TrustedHosts.allowed("github.com"))
        assertTrue(TrustedHosts.allowed("objects.githubusercontent.com"))
        assertFalse(TrustedHosts.allowed("evilgithub.com"))
        assertFalse(TrustedHosts.allowed("github.com.evil.io"))
        assertFalse(TrustedHosts.allowed(null))
    }
}
