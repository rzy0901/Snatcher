# ESP32 Find My Mimic (MAC rotation) 🕵️‍♂️

This module enables an ESP32 to high-fidelity emulate an Apple AirTag or any "Find My" compatible device. It leverages randomized MAC addresses (MAC rotation) to explore the privacy implications of the ecosystem and test device tracking resistance.

## 🛠️ System Requirements

- **Development Framework:** [ESP-IDF](https://github.com/espressif/esp-idf) (v4.4 or newer recommended)
- **Supported Hardware:** ESP32, ESP32-S3, or ESP32-C3

## 📦 Build & Deployment

1. **Environment Setup:**
   Ensure your ESP-IDF environment is correctly sourced, then build the project:
   ```bash
   idf.py build
   ```

2. **Flashing the Device:**
   Connect your hardware and flash the firmware:
   ```bash
   idf.py -p <PORT> flash monitor
   ```

## ⚙️ Configuration Guide

### 1. `menuconfig` Optimization
To ensure proper operation, configure the following via `idf.py menuconfig`:

- `Component config` → `Bluetooth` → `Bluetooth controller` → `Bluetooth controller mode` → **BLE Only**
- `Component config` → `Bluetooth` → `Bluedroid Options` → **Enable Bluedroid**

### 2. Device Identity Customization
The core identity of the mimicked device is defined in `main/main.c`. You can customize the simulated device ID:

```c
#define DEVICE_ID 0x01
```

Reference: [openhaystack](https://github.com/seemoo-lab/openhaystack)