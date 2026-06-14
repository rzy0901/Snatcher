# ESP32 Find My / AirTag Sound Trigger 🔊

This specialized module utilizes an ESP32 BLE GATT Client to trigger sound playback on supported Apple Find My devices (such as AirTags or AirPods) in non-owner proximity scenarios. It is designed for researching the security and alerting mechanisms of the Find My network.

## 🛠️ System Requirements

- **Development Framework:** [ESP-IDF](https://github.com/espressif/esp-idf) (v4.4 or newer recommended)
- **Hardware Platform:** ESP32 series

## 📦 Build & Deployment

1. **Firmware Compilation:**
   ```bash
   idf.py build
   ```

2. **Flashing the Device:**
   ```bash
   idf.py -p <PORT> flash monitor
   ```

## ⚙️ Essential `menuconfig` Settings

For optimal performance and BLE compatibility, ensure the following configurations are set:

- `Component config` → `Bluetooth` → `Bluetooth controller` → `Bluetooth controller mode` → **BLE Only**
- `Component config` → `Bluetooth` → `Bluedroid Options` → **Enable Bluedroid**

## 🔍 Target Device Configuration

You can identify the MAC address of your target device using standard tools like nRF Connect or the integrated **SnatchAPP**.

To target a specific device, modify `main/main.c`:

- **Device Type:** Adjust `device_type` to match your target (e.g., AirTag or third-party Find My accessory).
- **Target MAC:** Specify the BLE MAC address in `target_mac`.

Reference: [AirGuard](https://github.com/seemoo-lab/airguard)