package com.gpsupdater.app.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationHintPolicyTest {
    @Test
    fun `模擬中優先顯示模擬提示`() {
        assertEquals(
            LocationHint.MOCK_SIMULATION,
            LocationHintPolicy.resolve(
                isMocking = true,
                hasPermission = true,
                locationServicesEnabled = true,
                timedOut = false,
            ),
        )
        assertEquals(
            LocationHint.MOCK_SIMULATION,
            LocationHintPolicy.resolve(
                isMocking = true,
                hasPermission = false,
                locationServicesEnabled = false,
                timedOut = true,
            ),
        )
    }

    @Test
    fun `沒有定位權限時顯示授權提示`() {
        assertEquals(
            LocationHint.MISSING_PERMISSION,
            LocationHintPolicy.resolve(
                isMocking = false,
                hasPermission = false,
                locationServicesEnabled = true,
                timedOut = false,
            ),
        )
    }

    @Test
    fun `手機定位服務關閉時顯示開啟提示`() {
        assertEquals(
            LocationHint.LOCATION_SERVICES_DISABLED,
            LocationHintPolicy.resolve(
                isMocking = false,
                hasPermission = true,
                locationServicesEnabled = false,
                timedOut = false,
            ),
        )
    }

    @Test
    fun `定位逾時時顯示逾時提示`() {
        assertEquals(
            LocationHint.TIMEOUT,
            LocationHintPolicy.resolve(
                isMocking = false,
                hasPermission = true,
                locationServicesEnabled = true,
                timedOut = true,
            ),
        )
    }

    @Test
    fun `條件都正常且未逾時時不顯示提示`() {
        assertEquals(
            LocationHint.NONE,
            LocationHintPolicy.resolve(
                isMocking = false,
                hasPermission = true,
                locationServicesEnabled = true,
                timedOut = false,
            ),
        )
    }

    @Test
    fun `逾時的優先順序低於沒有權限與定位服務關閉`() {
        assertEquals(
            LocationHint.MISSING_PERMISSION,
            LocationHintPolicy.resolve(
                isMocking = false,
                hasPermission = false,
                locationServicesEnabled = false,
                timedOut = true,
            ),
        )
        assertEquals(
            LocationHint.LOCATION_SERVICES_DISABLED,
            LocationHintPolicy.resolve(
                isMocking = false,
                hasPermission = true,
                locationServicesEnabled = false,
                timedOut = true,
            ),
        )
    }
}
