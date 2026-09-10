package dev.ccpocket.app.telemetry

import kotlin.test.*

class Ga4ClientIdTest {
    @Test fun legacyUuidKeepsEveryBitIncludingUnsignedHalves() {
        val seed = "00112233-4455-6677-8899-aabbccddeeff"
        val expected = "4822678189205111.9843086184167632639"
        assertEquals(expected, ga4ClientId(seed))
        assertEquals(expected, ga4ClientId(seed.uppercase()))
        assertEquals("18446744073709551615.18446744073709551615",
            ga4ClientId("ffffffff-ffff-ffff-ffff-ffffffffffff"))
    }

    @Test fun existingNumericIdentityIsPreserved() {
        assertEquals("123456789.1234567890", ga4ClientId("123456789.1234567890"))
    }

    @Test fun invalidStateDoesNotCreateANewIdentity() {
        for (value in listOf("", "broken", "1.2.3", "1-1-1-1-1", "-1.2", " 1.2", "1.2\n")) {
            assertNull(ga4ClientId(value), value)
        }
    }
}
