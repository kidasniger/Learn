package com.kidasniger.learn.updater

import org.junit.Assert.*
import org.junit.Test

class SemanticVersionTest {
    @Test fun comparesNumericPartsRatherThanStrings() {
        assertTrue(SemanticVersion.isNewer("1.10.0", "1.9.9"))
        assertTrue(SemanticVersion.isNewer("2.0.0", "1.99.99"))
        assertFalse(SemanticVersion.isNewer("1.3.0", "1.3.1"))
        assertEquals(0, SemanticVersion.compare("v1.3.0", "1.3.0"))
    }

    @Test fun stableReleaseIsNewerThanPrerelease() {
        assertTrue(SemanticVersion.isNewer("1.4.0", "1.4.0-rc.2"))
        assertTrue(SemanticVersion.isNewer("1.4.0-rc.10", "1.4.0-rc.2"))
        assertTrue(SemanticVersion.isNewer("1.4.0-beta.1", "1.4.0-alpha.9"))
        assertFalse(SemanticVersion.isNewer("1.4.0-rc.1", "1.4.0"))
    }

    @Test fun ignoresBuildMetadata() {
        assertEquals(0, SemanticVersion.compare("1.3.0+build.4", "1.3.0+build.7"))
    }

    @Test fun rejectsInvalidOrAmbiguousVersions() {
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("1.3"))
        assertNull(SemanticVersion.parse("release-1.3.0"))
        assertNull(SemanticVersion.parse("1.3.0-01"))
        assertNull(SemanticVersion.parse("1.3.0..rc"))
        assertThrows(IllegalArgumentException::class.java) { SemanticVersion.compare("latest", "1.0.0") }
    }
}
