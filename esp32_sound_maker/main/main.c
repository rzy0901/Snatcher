#include <stdio.h>
#include <string.h>
#include "nvs_flash.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_ble_api.h"
#include "esp_gattc_api.h"
#include "esp_log.h"
#include "esp_bt_defs.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define GATTC_APP_ID 0

// Device type enum
typedef enum {
    DEVICE_TYPE_AIRTAG = 0,
    DEVICE_TYPE_AIRPODS,
    DEVICE_TYPE_FIND_MY_DEVICE
} device_type_t;

// ========== CONFIG: set device type and MAC address here ==========
// Set device type: DEVICE_TYPE_AIRTAG, DEVICE_TYPE_AIRPODS, or DEVICE_TYPE_FIND_MY_DEVICE
// static device_type_t device_type = DEVICE_TYPE_AIRTAG;
static device_type_t device_type = DEVICE_TYPE_FIND_MY_DEVICE; // My airpods pro 2

// Set target device MAC address
// static uint8_t target_mac[6] = {0xF3, 0xB9, 0xB2, 0x50, 0x41, 0xD2};
// D4:DE:53:8E:03:AB
// static uint8_t target_mac[6] = {0xD4, 0xDE, 0x53, 0x8E, 0x03, 0xAB};
// // F4:E4:88:B2:03:A7
// static uint8_t target_mac[6] = {0xF4, 0xE4, 0x88, 0xB2, 0x03, 0xA7}; // My AirTag

// F6:03:74:E4:69:8C
static uint8_t target_mac[6] = {0xF6, 0x03, 0x74, 0xE4, 0x69, 0x8C};

// =========================================================

// AirTag protocol definitions
// Service UUID: 7DFC9000-7D1C-4951-86AA-8D9728F8D66C
// NOTE: ESP32 BLE stores 128-bit UUID bytes in little-endian order; the debug print prints from index 15 down to 0.
// UUID: 7DFC9000-7D1C-4951-86AA-8D9728F8D66C
// Stored bytes (index 15->0): 7D FC 90 00 7D 1C 49 51 86 AA 8D 97 28 F8 D6 6C
static const uint8_t AIRTAG_SERVICE_UUID128[16] = {
    0x6C, 0xD6, 0xF8, 0x28, 0x97, 0x8D, 0xAA, 0x86,
    0x51, 0x49, 0x1C, 0x7D, 0x00, 0x90, 0xFC, 0x7D
};
// Characteristic UUID: 7DFC9001-7D1C-4951-86AA-8D9728F8D66C
static const uint8_t AIRTAG_CHAR_UUID128[16] = {
    0x6C, 0xD6, 0xF8, 0x28, 0x97, 0x8D, 0xAA, 0x86,
    0x51, 0x49, 0x1C, 0x7D, 0x01, 0x90, 0xFC, 0x7D
};
#define AIRTAG_SOUND_VALUE 175  // 0xAF

// Find My protocol definitions
// Find My service UUID: FD44 (16-bit UUID)
#define FINDMY_SERVICE_UUID16 0xFD44

// Non-owner Control Point characteristic UUID: 4F860003-943B-49EF-BED4-2F730304427A
// UUID: 4F860003-943B-49EF-BED4-2F730304427A
// Stored bytes (index 15->0): 4F 86 00 03 94 3B 49 EF BE D4 2F 73 03 04 42 7A
static const uint8_t FINDMY_CONTROL_POINT_UUID128[16] = {
    0x7A, 0x42, 0x04, 0x03, 0x73, 0x2F, 0xD4, 0xBE,
    0xEF, 0x49, 0x3B, 0x94, 0x03, 0x00, 0x86, 0x4F
};
// Find My Start Sound: [0x01, 0x00, 0x03]
static const uint8_t FINDMY_START_SOUND[3] = {0x01, 0x00, 0x03};
// Find My Stop Sound: [0x01, 0x01, 0x03]
static const uint8_t FINDMY_STOP_SOUND[3] = {0x01, 0x01, 0x03};

static esp_gatt_if_t gattc_if = 0;
static uint16_t gattc_conn_id = 0;
static uint16_t characteristic_handle = 0;
static uint16_t service_start_handle = 0;
static uint16_t service_end_handle = 0;
static bool waiting_for_stop = false;  // Whether we're waiting for the STOP write response
static TaskHandle_t stop_sound_task_handle = NULL;

static esp_ble_scan_params_t ble_scan_params = {
    // .scan_type              = BLE_SCAN_TYPE_ACTIVE,
    .scan_type              = BLE_SCAN_TYPE_PASSIVE,
    .own_addr_type          = BLE_ADDR_TYPE_PUBLIC,
    .scan_filter_policy     = BLE_SCAN_FILTER_ALLOW_ALL,
    .scan_interval          = 0x100,
    .scan_window            = 0x100,
    .scan_duplicate         = BLE_SCAN_DUPLICATE_DISABLE
};

// Send AirTag sound command
void send_airtag_sound_command() {
    if (characteristic_handle) {
        uint8_t value = AIRTAG_SOUND_VALUE;
        esp_ble_gattc_write_char(gattc_if, gattc_conn_id, characteristic_handle, 
                                 1, &value, ESP_GATT_WRITE_TYPE_RSP, ESP_GATT_AUTH_REQ_NONE);
        printf("AirTag: Sent sound play command (value=0x%02X).\n", value);
    } else {
        printf("AirTag: Characteristic handle not found!\n");
    }
}

// Send Find My device START sound command
void send_findmy_sound_start_command() {
    if (characteristic_handle) {
        esp_ble_gattc_write_char(gattc_if, gattc_conn_id, characteristic_handle,
                                 sizeof(FINDMY_START_SOUND), FINDMY_START_SOUND,
                                 ESP_GATT_WRITE_TYPE_RSP, ESP_GATT_AUTH_REQ_NONE);
        printf("Find My: Sent sound start command.\n");
    } else {
        printf("Find My: Control Point handle not found!\n");
    }
}

// Send Find My device STOP sound command
void send_findmy_sound_stop_command() {
    if (characteristic_handle) {
        esp_ble_gattc_write_char(gattc_if, gattc_conn_id, characteristic_handle,
                                 sizeof(FINDMY_STOP_SOUND), FINDMY_STOP_SOUND,
                                 ESP_GATT_WRITE_TYPE_RSP, ESP_GATT_AUTH_REQ_NONE);
        printf("Find My: Sent sound stop command.\n");
    } else {
        printf("Find My: Control Point handle not found!\n");
    }
}

// Task to stop sound after a delay (non-blocking for BLE callbacks)
void stop_sound_task(void *pvParameters) {
    vTaskDelay(pdMS_TO_TICKS(5000));  // Delay 5 seconds
    if (characteristic_handle && gattc_if && gattc_conn_id) {
        waiting_for_stop = true;
        send_findmy_sound_stop_command();
    }
    stop_sound_task_handle = NULL;
    vTaskDelete(NULL);
}

void gattc_callback(esp_gattc_cb_event_t event, esp_gatt_if_t ifx, esp_ble_gattc_cb_param_t *param) {
    switch (event) {
        case ESP_GATTC_REG_EVT:
            gattc_if = ifx;
            printf("GATT client registered, device type: %d, start scanning...\n", device_type);
            esp_ble_gap_set_scan_params(&ble_scan_params);
            ESP_ERROR_CHECK(esp_ble_gap_start_scanning(0));
            break;
        case ESP_GATTC_CONNECT_EVT:
            gattc_conn_id = param->connect.conn_id;
            printf("Connected, start service discovery...\n");
            ESP_ERROR_CHECK(esp_ble_gattc_search_service(gattc_if, gattc_conn_id, NULL));
            break;
        case ESP_GATTC_SEARCH_RES_EVT: {
            // Print all discovered service UUIDs and handles
            printf("Service found: start=0x%04X end=0x%04X, UUID: ", param->search_res.start_handle, param->search_res.end_handle);
            if (param->search_res.srvc_id.uuid.len == ESP_UUID_LEN_16) {
                printf("%04X\n", param->search_res.srvc_id.uuid.uuid.uuid16);
            } else if (param->search_res.srvc_id.uuid.len == ESP_UUID_LEN_32) {
                printf("%08lX\n", (unsigned long)param->search_res.srvc_id.uuid.uuid.uuid32);
            } else if (param->search_res.srvc_id.uuid.len == ESP_UUID_LEN_128) {
                for (int i = 15; i >= 0; --i) {
                    printf("%02X", param->search_res.srvc_id.uuid.uuid.uuid128[i]);
                    if (i == 12 || i == 10 || i == 8 || i == 6) printf("-");
                }
                printf("\n");
            }
            
            // Find the target service based on device type
            if (device_type == DEVICE_TYPE_AIRTAG) {
                // Find AirTag service (128-bit UUID)
                if (param->search_res.srvc_id.uuid.len == ESP_UUID_LEN_128) {
                    if (memcmp(param->search_res.srvc_id.uuid.uuid.uuid128, AIRTAG_SERVICE_UUID128, 16) == 0) {
                        service_start_handle = param->search_res.start_handle;
                        service_end_handle = param->search_res.end_handle;
                        printf("AirTag service found: start=0x%04X end=0x%04X\n", service_start_handle, service_end_handle);
                    }
                }
            } else if (device_type == DEVICE_TYPE_AIRPODS || device_type == DEVICE_TYPE_FIND_MY_DEVICE) {
                // Find My service (16-bit UUID: FD44)
                if (param->search_res.srvc_id.uuid.len == ESP_UUID_LEN_16) {
                    if (param->search_res.srvc_id.uuid.uuid.uuid16 == FINDMY_SERVICE_UUID16) {
                        service_start_handle = param->search_res.start_handle;
                        service_end_handle = param->search_res.end_handle;
                        printf("Find My service (FD44) found: start=0x%04X end=0x%04X\n", service_start_handle, service_end_handle);
                    }
                }
            }
            break;
        }
        case ESP_GATTC_SEARCH_CMPL_EVT:
            if (service_start_handle && service_end_handle) {
                esp_gattc_char_elem_t char_elem_result;
                uint16_t count = 1;
                esp_bt_uuid_t char_uuid;
                char_uuid.len = ESP_UUID_LEN_128;
                
                if (device_type == DEVICE_TYPE_AIRTAG) {
                    printf("Service discovery complete, searching for AirTag characteristic...\n");
                    memcpy(char_uuid.uuid.uuid128, AIRTAG_CHAR_UUID128, 16);
                } else if (device_type == DEVICE_TYPE_AIRPODS || device_type == DEVICE_TYPE_FIND_MY_DEVICE) {
                    printf("Service discovery complete, searching for Find My Control Point characteristic...\n");
                    printf("Expected characteristic UUID: 4F860003-943B-49EF-BED4-2F730304427A\n");
                    memcpy(char_uuid.uuid.uuid128, FINDMY_CONTROL_POINT_UUID128, 16);
                } else {
                    printf("Unknown device type!\n");
                    break;
                }
                
                esp_gatt_status_t status = esp_ble_gattc_get_char_by_uuid(
                    gattc_if,
                    gattc_conn_id,
                    service_start_handle,
                    service_end_handle,
                    char_uuid,
                    &char_elem_result,
                    &count
                );
                
                if (status == ESP_GATT_OK && count > 0) {
                    characteristic_handle = char_elem_result.char_handle;
                    printf("Characteristic found, handle=0x%04X. Sending sound play command...\n", characteristic_handle);
                    
                    if (device_type == DEVICE_TYPE_AIRTAG) {
                        send_airtag_sound_command();
                    } else if (device_type == DEVICE_TYPE_AIRPODS || device_type == DEVICE_TYPE_FIND_MY_DEVICE) {
                        send_findmy_sound_start_command();
                    }
                } else {
                    printf("Characteristic not found (status=%d, count=%d).\n", status, count);
                }
            } else {
                printf("Service not found.\n");
            }
            break;
        case ESP_GATTC_WRITE_CHAR_EVT:
            printf("Write characteristic event, status: %d\n", param->write.status);
            if (param->write.status == ESP_GATT_OK) {
                if (device_type == DEVICE_TYPE_AIRTAG) {
                    printf("AirTag: Sound playback command sent successfully.\n");
                    // AirTag may indicate completion via notification or status code
                } else if (device_type == DEVICE_TYPE_AIRPODS || device_type == DEVICE_TYPE_FIND_MY_DEVICE) {
                    if (waiting_for_stop) {
                        // STOP command acknowledged; we can disconnect now
                        printf("Find My: Sound stopped, disconnecting...\n");
                        waiting_for_stop = false;
                        if (stop_sound_task_handle != NULL) {
                            vTaskDelete(stop_sound_task_handle);
                            stop_sound_task_handle = NULL;
                        }
                        esp_ble_gattc_close(gattc_if, gattc_conn_id);
                    } else {
                        // START command acknowledged; create a task to send STOP after 5 seconds
                        printf("Find My: Sound started, will stop after 5 seconds...\n");
                        if (stop_sound_task_handle == NULL) {
                            xTaskCreate(stop_sound_task, "stop_sound", 2048, NULL, 5, &stop_sound_task_handle);
                        }
                    }
                }
            } else if (param->write.status == 19) {
                // Status code 19 indicates completion (AirTag)
                printf("AirTag: Sound playback completed (status 19).\n");
                esp_ble_gattc_close(gattc_if, gattc_conn_id);
            } else {
                printf("Write failed with status: %d\n", param->write.status);
            }
            break;
        case ESP_GATTC_NOTIFY_EVT:
            printf("Notification received, value[0]=0x%02X\n", param->notify.value[0]);
            // You can handle notification payloads here if needed
            break;
        case ESP_GATTC_DISCONNECT_EVT:
            printf("Disconnected from device.\n");
            characteristic_handle = 0;
            service_start_handle = 0;
            service_end_handle = 0;
            waiting_for_stop = false;
            if (stop_sound_task_handle != NULL) {
                vTaskDelete(stop_sound_task_handle);
                stop_sound_task_handle = NULL;
            }
            break;
        default:
            break;
    }
}

void gap_callback(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {
    if (event == ESP_GAP_BLE_SCAN_RESULT_EVT) {
        esp_ble_gap_cb_param_t *scan_result = param;
        if (scan_result->scan_rst.search_evt == ESP_GAP_SEARCH_INQ_RES_EVT) {
            // Print scan result
            printf("Found device: %02X:%02X:%02X:%02X:%02X:%02X, RSSI: %d\n",
                   scan_result->scan_rst.bda[0], scan_result->scan_rst.bda[1],
                   scan_result->scan_rst.bda[2], scan_result->scan_rst.bda[3],
                   scan_result->scan_rst.bda[4], scan_result->scan_rst.bda[5],
                   scan_result->scan_rst.rssi);
            // Compare MAC address
            if (gattc_if && memcmp(scan_result->scan_rst.bda, target_mac, 6) == 0) {
                const char* device_type_str = (device_type == DEVICE_TYPE_AIRTAG) ? "AirTag" :
                                             (device_type == DEVICE_TYPE_AIRPODS) ? "AirPods" : "Find My Device";
                printf("Target %s found, connecting...\n", device_type_str);
                esp_ble_gap_stop_scanning();
                esp_ble_gattc_open(gattc_if, scan_result->scan_rst.bda, BLE_ADDR_TYPE_RANDOM, true);
            }
        }
    }
}

void app_main() {
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT));
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bt_controller_init(&bt_cfg));
    ESP_ERROR_CHECK(esp_bt_controller_enable(ESP_BT_MODE_BLE));
    ESP_ERROR_CHECK(esp_bluedroid_init());
    ESP_ERROR_CHECK(esp_bluedroid_enable());

    ESP_ERROR_CHECK(esp_ble_gattc_register_callback(gattc_callback));
    ESP_ERROR_CHECK(esp_ble_gap_register_callback(gap_callback));
    ESP_ERROR_CHECK(esp_ble_gattc_app_register(GATTC_APP_ID));

    // Do not start scanning here, wait for ESP_GATTC_REG_EVT
}