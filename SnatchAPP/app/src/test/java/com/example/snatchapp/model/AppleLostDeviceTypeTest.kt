package com.example.snatchapp.model

import com.example.snatchapp.model.BleDevice.AppleLostDeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the (status & 0x18) classification logic used by AppleLostDeviceType.match.
 *
 * The historically-buggy case is the one where battery bits (bit 5/6) flip on a
 * physical AirTag — e.g. status 0x10 → 0x50 — and a pure-equality matcher silently
 * dropped the device. Each AirTag/AirPods test below pairs the canonical zero-battery
 * value with a non-zero-battery counterpart to lock that behaviour in.
 */
class AppleLostDeviceTypeTest {

    private fun ad(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        require(clean.length % 2 == 0) { "hex must be even length: $hex" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun airtag_fullBattery_status0x10_classifiedAsAirTag() {
        val frame = ad("1EFF4C001219" + "10" + "8749AABBCCDDEEFF00112233445566778899AABBCCDD")
        assertEquals(AppleLostDeviceType.AIRTAG, AppleLostDeviceType.match(frame))
    }

    @Test
    fun airtag_mediumBattery_status0x50_stillClassifiedAsAirTag() {
        // Regression: the pre-mask matcher only accepted 0x10, so an AirTag whose
        // battery dropped to medium (bit 6 set → 0x50) was classified as unknown.
        val frame = ad("1EFF4C001219" + "50" + "8749AABBCCDDEEFF00112233445566778899AABBCCDD")
        assertEquals(AppleLostDeviceType.AIRTAG, AppleLostDeviceType.match(frame))
    }

    @Test
    fun airpods_status0x18_classifiedAsAirPods() {
        val frame = ad("1EFF4C001219" + "18" + "8749AABBCCDDEEFF00112233445566778899AABBCCDD")
        assertEquals(AppleLostDeviceType.AIRPODS, AppleLostDeviceType.match(frame))
    }

    @Test
    fun airpods_withBatteryBits_status0x58_stillClassifiedAsAirPods() {
        val frame = ad("1EFF4C001219" + "58" + "8749AABBCCDDEEFF00112233445566778899AABBCCDD")
        assertEquals(AppleLostDeviceType.AIRPODS, AppleLostDeviceType.match(frame))
    }

    @Test
    fun appleAccessory_status0x00_classifiedAsAppleDevice() {
        // bit 4 = 0 → owner-present / nearby mode (not in detached broadcast).
        val frame = ad("1EFF4C001219" + "00" + "8749AABBCCDDEEFF00112233445566778899AABBCCDD")
        assertEquals(AppleLostDeviceType.APPLE_DEVICE, AppleLostDeviceType.match(frame))
    }

    @Test
    fun nonAppleCompanyId_returnsNull() {
        // 0x0059 = Nordic Semiconductor; same byte slot as Apple but obviously not Apple.
        val frame = ad("1EFF590012191087490000000000000000000000000000000000000000")
        assertNull(AppleLostDeviceType.match(frame))
    }

    @Test
    fun appleType_notFindMy_returnsNull() {
        // 0x07 = AirPods pairing advert, NOT Find My (which is 0x12).
        val frame = ad("1EFF4C0007191087490000000000000000000000000000000000000000")
        assertNull(AppleLostDeviceType.match(frame))
    }

    @Test
    fun frameTooShortToContainStatus_returnsNull() {
        val frame = ad("1EFF4C001219")  // truncated before the status byte
        assertNull(AppleLostDeviceType.match(frame))
    }

    @Test
    fun matchesStatus_isMaskBased_notEquality() {
        // Sanity check at the unit level: any byte sharing bit 4=1 / bit 3=0 with the
        // AIRTAG fingerprint must match, regardless of the other bits.
        listOf(0x10, 0x11, 0x14, 0x50, 0x91, 0xD3).forEach { raw ->
            assertEquals(
                "0x${"%02X".format(raw)} should match AIRTAG",
                true,
                AppleLostDeviceType.AIRTAG.matchesStatus(raw.toByte()),
            )
        }
    }
}
