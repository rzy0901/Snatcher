package com.example.snatchapp.model

data class BleDevice(
    val macAddress: String,
    val rssi: Int,
    val deviceName: String? = null,
    val payloadBytes: ByteArray,        // Raw payload byte array
    val payloadHex: String,             // Hex string (trimmed trailing zeros)
    val payloadLength: Int,             // Actual payload length
    val timestamp: Long = System.currentTimeMillis(),
    val isConnectable: Boolean,
) {
    /**
     * Apple Find My device sub-types.
     *
     * The status byte in Apple's Find My manufacturer-specific data is a bit-field, not a
     * simple integer code. Only specific bits encode the device class; the rest track
     * battery level, owner-connection state, and the lost-mode flag, and they all change
     * over the lifetime of a single physical device. So we match with
     * `(status & mask) == (expected & mask)` rather than equality.
     *
     * Status byte layout (from community reverse-engineering — NOT official Apple docs):
     * ```
     * bit 7 6 5 4 3 2 1 0
     *     └─┬─┘ │ │ │ └─┬─┘
     *       │   │ │ │   └── bit 0-1: battery level (full / medium / low / critical)
     *       │   │ │ └────── bit 2:    maintained bit (owner connected within last 15 min)
     *       │   │ └──────── bit 3:    sub-class (0 = AirTag-like, 1 = AirPods-like)
     *       │   └────────── bit 4:    detached / lost-mode flag (1 = separated broadcast)
     *       └────────────── bit 6-7: battery level when maintained bit is set
     * ```
     *
     * Only bits 3 and 4 are stable enough to fingerprint the device class, so the canonical
     * mask is `0x18` (binary `0001_1000`). This mirrors what the seemoo-lab/AirGuard Android
     * project does in its BLE-scan filter — see their `OffloadFilter` setup for reference.
     *
     * Because these bit semantics come from reverse engineering, future Apple firmware may
     * change them. When that happens, the [expected] / [mask] values defined here are the
     * single point of adjustment — no other code in this module hardcodes the bit layout.
     *
     * @param expected    the value of `status & mask` that identifies this sub-class
     * @param mask        the bits of the status byte that are stable enough to test
     * @param displayName label to show in the UI
     * @param priority    higher wins when several entries could theoretically match the
     *                    same status byte (today the four entries are mutually exclusive
     *                    under mask 0x18; the field is kept for future-proofing)
     */
    enum class AppleLostDeviceType(
        val expected: Byte,
        val mask: Byte,
        val displayName: String,
        val priority: Int,
    ) {
        /** bit 4 = 1, bit 3 = 0 — detached broadcast from a tag-class device. */
        AIRTAG(expected = 0x10.toByte(), mask = 0x18.toByte(), displayName = "🏷️ AirTag", priority = 3),

        /** bit 4 = 1, bit 3 = 1 — detached broadcast from an earbud-class device. */
        AIRPODS(expected = 0x18.toByte(), mask = 0x18.toByte(), displayName = "🎧 AirPods", priority = 2),

        /** bit 4 = 0, bit 3 = 0 — Find My accessory in nearby/owner-present mode. */
        APPLE_DEVICE(expected = 0x00.toByte(), mask = 0x18.toByte(), displayName = "📱 Apple Device", priority = 1),

        /** bit 4 = 0, bit 3 = 1 — rare; observed on custom-built or anomalous trackers. */
        UNKNOWN_FIND_MY_DEVICE(expected = 0x08.toByte(), mask = 0x18.toByte(), displayName = "🍎 Unknown Find My Device", priority = 1);

        /** True iff this entry's [mask]/[expected] fingerprint matches [status]. */
        fun matchesStatus(status: Byte): Boolean {
            val s = status.toInt() and 0xFF
            val m = mask.toInt() and 0xFF
            val e = expected.toInt() and 0xFF
            return (s and m) == (e and m)
        }

        companion object {
            // Byte offsets within a complete BLE AD payload, starting at the length prefix
            // emitted by the BLE stack (e.g. `1E FF 4C 00 12 19 10 …`).
            private const val OFFSET_AD_TYPE    = 1   // expect 0xFF (Manufacturer Specific Data)
            private const val OFFSET_COMPANY_LO = 2   // expect 0x4C (Apple, low byte — BLE is little-endian)
            private const val OFFSET_COMPANY_HI = 3   // expect 0x00 (Apple, high byte)
            private const val OFFSET_APPLE_TYPE = 4   // expect 0x12 (Find My / Offline Finding)
            private const val OFFSET_STATUS     = 6   // status bit-field we classify on
            private const val MIN_AD_LENGTH     = OFFSET_STATUS + 1

            private const val AD_TYPE_MFG_DATA: Byte    = 0xFF.toByte()
            private const val APPLE_COMPANY_LO: Byte    = 0x4C.toByte()
            private const val APPLE_COMPANY_HI: Byte    = 0x00.toByte()
            private const val APPLE_TYPE_FIND_MY: Byte  = 0x12.toByte()

            private val byPriorityDesc: List<AppleLostDeviceType> =
                values().sortedByDescending { it.priority }

            /**
             * Classify a raw BLE advertising-data payload as an Apple Find My sub-type.
             *
             * Expected layout (offsets are byte indices into [rawAdData]):
             * ```
             * [0] AD length    [1] 0xFF    [2..3] 0x4C 0x00    [4] 0x12    [5] apple-len    [6] status    [7..] data
             * ```
             *
             * @param rawAdData full AD payload as captured by the scanner, **including**
             *                  the leading length byte (e.g. `0x1E FF 4C 00 12 19 10 …`).
             * @return the matched sub-type, or `null` if any pre-condition fails — i.e. the
             *         frame is too short, not Manufacturer Specific Data, not Apple, or not
             *         a Find My (0x12) advert. Other Apple advert types like 0x07 (AirPods
             *         pairing) deliberately fall through to `null`.
             */
            fun match(rawAdData: ByteArray): AppleLostDeviceType? {
                // Length must reach the status byte we want to read.
                if (rawAdData.size < MIN_AD_LENGTH) return null

                // AD Type = 0xFF (Manufacturer Specific Data).
                if (rawAdData[OFFSET_AD_TYPE] != AD_TYPE_MFG_DATA) return null

                // Company ID = 0x004C (Apple). BLE encodes 16-bit fields little-endian, so
                // the low byte (0x4C) precedes the high byte (0x00) on the wire.
                if (rawAdData[OFFSET_COMPANY_LO] != APPLE_COMPANY_LO) return null
                if (rawAdData[OFFSET_COMPANY_HI] != APPLE_COMPANY_HI) return null

                // Apple advert type must be 0x12 = Find My / Offline Finding. Other Apple
                // types share the same Company ID but encode different payloads.
                if (rawAdData[OFFSET_APPLE_TYPE] != APPLE_TYPE_FIND_MY) return null

                // Sub-class is determined by `status & 0x18`. Iterating priority-desc means
                // the highest-priority entry wins if future entries ever overlap.
                val status = rawAdData[OFFSET_STATUS]
                return byPriorityDesc.firstOrNull { it.matchesStatus(status) }
            }
        }
    }

    /**
     * Get connectivity status as display string
     */
    fun getConnectivityStatus(): String {
        return if (isConnectable) "🔗 Connectable" else "🚫 Non-Connectable"
    }

    /**
     * Get connectivity icon
     */
    fun getConnectivityIcon(): String {
        return if (isConnectable) "🔗" else "🚫"
    }

    /**
     * Check if this device is an Apple Lost Device (Find My)
     * Criteria: Contains FF4C0012 in payload and payload length is 31 bytes
     */
    fun isAppleLostDevice(): Boolean {
        return payloadHex.contains("FF4C001219", ignoreCase = true) && payloadLength == 31
    }

    /**
     * Classify this device's Find My sub-type from the status bit-field.
     *
     * Defers to [AppleLostDeviceType.match], which applies `(status & 0x18)` and so
     * correctly handles all battery-level / maintained-bit variations of the same
     * physical device (e.g. an AirTag whose battery dropped from full → medium flips
     * bit 6 of status from 0 → 1, turning 0x10 into 0x50; both still classify as AIRTAG).
     */
    fun getAppleLostDeviceType(): AppleLostDeviceType? {
        if (!isAppleLostDevice()) return null
        return AppleLostDeviceType.match(payloadBytes)
    }

    /**
     * Check if this is an offline Apple Lost Device
     * Based on the status byte patterns from the filters
     */
    fun isOfflineAppleLostDevice(): Boolean {
        if (!isAppleLostDevice()) return false

        val deviceType = getAppleLostDeviceType()
        return deviceType != null // For now, we consider all detected devices as potentially offline
    }

    /**
     * Extract Apple Lost Device public key (corrected method)
     * Public key structure:
     * - First 6 bytes: MAC address
     * - Next 22 bytes: After FF4C00121910 pattern (22 bytes following the status code)
     * Total: 28 bytes public key
     */
    fun getAppleLostDevicePublicKey(): String? {
        if (!isAppleLostDevice()) return null

        // Find FF4C001219 pattern in hex string
        val findMyPattern = "FF4C001219"
        val patternIndex = payloadHex.indexOf(findMyPattern, ignoreCase = true)
        if (patternIndex == -1) return null

        // Extract MAC address (first 6 bytes of public key)
        // Convert device MAC address to hex bytes (remove colons and convert)
        val macHex = macAddress.replace(":", "")

        // Extract the 22 bytes after FF4C00121910 (status code + data)
        // Status code is 1 byte (2 hex chars) after FF4C001219
        val dataStartIndex = patternIndex + findMyPattern.length + 2 // +2 for status code

        // Extract 22 bytes (44 hex characters) after the status code
        val dataBytes = if (dataStartIndex + 44 <= payloadHex.length) {
            payloadHex.substring(dataStartIndex, dataStartIndex + 44)
        } else {
            // If not enough data, take whatever is available
            payloadHex.substring(dataStartIndex)
        }

        // Combine MAC (6 bytes) + Data (22 bytes) = 28 bytes total
        return macHex + dataBytes
    }

    /**
     * Get the raw data portion (22 bytes after status code)
     */
    fun getAppleLostDeviceRawData(): String? {
        if (!isAppleLostDevice()) return null

        val findMyPattern = "FF4C001219"
        val patternIndex = payloadHex.indexOf(findMyPattern, ignoreCase = true)
        if (patternIndex == -1) return null

        // Data starts after FF4C001219 + status code (1 byte)
        val dataStartIndex = patternIndex + findMyPattern.length + 2

        // Extract 22 bytes (44 hex characters)
        return if (dataStartIndex + 44 <= payloadHex.length) {
            payloadHex.substring(dataStartIndex, dataStartIndex + 44)
        } else {
            // Return available data if less than 22 bytes
            if (dataStartIndex < payloadHex.length) {
                payloadHex.substring(dataStartIndex)
            } else {
                null
            }
        }
    }

    /**
     * Get MAC address as hex bytes (6 bytes)
     */
    fun getMacAddressHex(): String {
        return macAddress.replace(":", "").uppercase()
    }

    /**
     * Extract DEVICE_ID from advertisement payload (ground truth)
     * DEVICE_ID is at adv_data[7] in the payload structure:
     * - adv_data[0] = Length (0x1e = 30)
     * - adv_data[1] = 0xFF
     * - adv_data[2] = 0x4C
     * - adv_data[3] = 0x00
     * - adv_data[4] = 0x12
     * - adv_data[5] = 0x19
     * - adv_data[6] = 0x10 (status code for AirTag)
     * - adv_data[7] = DEVICE_ID (ground truth)
     * 
     * DEVICE_ID mapping:
     * - 0x1 = lost device1
     * - 0x2 = lost device2
     * - 0x3 = lost device3
     * 
     * @return DEVICE_ID as Int, or null if not available or invalid
     */
    fun getDeviceId(): Int? {
        if (!isAppleLostDevice()) return null
        
        // Check if payload has enough bytes (at least 8 bytes for index 7)
        if (payloadBytes.size < 8) return null
        
        try {
            val deviceId = payloadBytes[7].toInt() and 0xFF
            // Only return valid device IDs (1, 2, or 3)
            return if (deviceId in 1..3) deviceId else null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parse the payload structure for debugging
     */
    fun parseAppleLostDevicePayload(): AppleLostDevicePayload? {
        if (!isAppleLostDevice()) return null

        val findMyPattern = "FF4C001219"
        val patternIndex = payloadHex.indexOf(findMyPattern, ignoreCase = true)
        if (patternIndex == -1) return null

        try {
            // Extract components
            val statusCodeIndex = patternIndex + findMyPattern.length
            val statusCode = if (statusCodeIndex + 2 <= payloadHex.length) {
                payloadHex.substring(statusCodeIndex, statusCodeIndex + 2).toInt(16).toByte()
            } else {
                return null
            }

            val rawData = getAppleLostDeviceRawData() ?: return null
            val publicKey = getAppleLostDevicePublicKey() ?: return null
            val macHex = getMacAddressHex()

            // Extract hint (last byte of the payload structure)
            val hintIndex = payloadHex.length - 2
            val hint = if (hintIndex >= 0 && hintIndex + 2 <= payloadHex.length) {
                payloadHex.substring(hintIndex, hintIndex + 2).toInt(16).toByte()
            } else {
                0x00.toByte()
            }

            return AppleLostDevicePayload(
                statusCode = statusCode,
                macAddressHex = macHex,
                rawData = rawData,
                publicKey = publicKey,
                hint = hint,
                isConnectable = isConnectable
            )

        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Get detailed device information for debugging (updated)
     */
    fun getAppleLostDeviceInfo(): String? {
        if (!isAppleLostDevice()) return null

        val deviceType = getAppleLostDeviceType()
        val payload = parseAppleLostDevicePayload()
        val isOffline = isOfflineAppleLostDevice()

        return buildString {
            append("Type: ${deviceType?.displayName ?: "Unknown"}\n")
            append("Status Code: 0x${payload?.statusCode?.let { "%02X".format(it) } ?: "??"}\n")
            append("Connectable: ${getConnectivityStatus()}\n")
            append("Offline: $isOffline\n")
            append("MAC (hex): ${payload?.macAddressHex ?: "Unknown"}\n")
            append("Raw Data: ${payload?.rawData?.take(16)}...${if (payload?.rawData != null && payload.rawData.length > 16) "(${payload.rawData.length/2} bytes)" else ""}\n")
            append("Public Key: ${payload?.publicKey?.take(16)}...${if (payload?.publicKey != null && payload.publicKey.length > 16) "(${payload.publicKey.length/2} bytes)" else ""}\n")
            append("Hint: 0x${payload?.hint?.let { "%02X".format(it) } ?: "??"}")
        }
    }

    // Override equals and hashCode because of ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BleDevice

        if (macAddress != other.macAddress) return false
        if (rssi != other.rssi) return false
        if (deviceName != other.deviceName) return false
        if (!payloadBytes.contentEquals(other.payloadBytes)) return false
        if (payloadHex != other.payloadHex) return false
        if (payloadLength != other.payloadLength) return false
        if (isConnectable != other.isConnectable) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = macAddress.hashCode()
        result = 31 * result + rssi
        result = 31 * result + (deviceName?.hashCode() ?: 0)
        result = 31 * result + payloadBytes.contentHashCode()
        result = 31 * result + payloadHex.hashCode()
        result = 31 * result + payloadLength
        result = 31 * result + isConnectable.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Lost Device Payload Pattern
 * [Length][FF][4C00][1219][Status][22 bytes data][Hint]
 *   1      1    2      2      1        22          1
 */
/**
 * Apple Lost Device payload structure
 */
data class AppleLostDevicePayload(
    val statusCode: Byte,           // Status code (0x10 for AirTag, 0x18 for AirPods, etc.)
    val macAddressHex: String,      // MAC address as hex string (6 bytes)
    val rawData: String,            // Raw data after status code (22 bytes)
    val publicKey: String,          // Complete public key: MAC + Raw data (28 bytes)
    val hint: Byte,                  // Hint byte (usually last byte)
    val isConnectable: Boolean
)