#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#include <stdio.h>

#include "nvs_flash.h"
#include "esp_partition.h"

#include "esp_bt.h"
#include "esp_gap_ble_api.h"
#include "esp_gattc_api.h"
#include "esp_gatt_defs.h"
#include "esp_bt_main.h"
#include "esp_bt_defs.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "esp_random.h"

static const char* LOG_TAG = "open_haystack";

// Configuration: MAC shuffle period in seconds
#define MAC_SHUFFLE_PERIOD_SEC 30

// Configuration: Device ID (ground truth) - Set to 0x01, 0x02, or 0x03
#define DEVICE_ID 0x01

/** Callback function for BT events */
static void esp_gap_cb(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param);

/** Random device address */
static esp_bd_addr_t rnd_addr = { 0xFF, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF };

/** Timer handle for MAC shuffle */
static esp_timer_handle_t mac_shuffle_timer = NULL;

/** Flag to track if advertising is active */
static bool advertising_active = false;

/** Advertisement payload */
static uint8_t adv_data[31] = {
	0x1e, /* Length (30) */
	0xff, /* Manufacturer Specific Data (type 0xff) */
	0x4c, 0x00, /* Company ID (Apple) */
	0x12, 0x19, /* Offline Finding type and length */
	// 0x00, /* State */
    0x10, /* 0x10 indicates airtag devices */
	DEVICE_ID, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  /* adv_data[7] = DEVICE_ID (ground truth) */
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x00, /* First two bits */
	0x00, /* Hint (0x00) */
};

/* https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/bluetooth/esp_gap_ble.html#_CPPv420esp_ble_adv_params_t */
static esp_ble_adv_params_t ble_adv_params = {
    // Advertising min interval:
    // Minimum advertising interval for undirected and low duty cycle
    // directed advertising. Range: 0x0020 to 0x4000 Default: N = 0x0800
    // (1.28 second) Time = N * 0.625 msec Time Range: 20 ms to 10.24 sec
    .adv_int_min        = 0x0640, // 1s
    // Advertising max interval:
    // Maximum advertising interval for undirected and low duty cycle
    // directed advertising. Range: 0x0020 to 0x4000 Default: N = 0x0800
    // (1.28 second) Time = N * 0.625 msec Time Range: 20 ms to 10.24 sec
    .adv_int_max        = 0x0C80, // 2s
    // Advertisement type
    .adv_type           = ADV_TYPE_NONCONN_IND,
    // Use the random address
    .own_addr_type      = BLE_ADDR_TYPE_RANDOM,
    // All channels
    .channel_map        = ADV_CHNL_ALL,
    // Allow both scan and connection requests from anyone. 
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

static void esp_gap_cb(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    esp_err_t err;

    switch (event) {
        case ESP_GAP_BLE_ADV_DATA_RAW_SET_COMPLETE_EVT:
            esp_ble_gap_start_advertising(&ble_adv_params);
            break;

        case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
            //adv start complete event to indicate adv start successfully or failed
            if ((err = param->adv_start_cmpl.status) != ESP_BT_STATUS_SUCCESS) {
                ESP_LOGE(LOG_TAG, "advertising start failed: %s", esp_err_to_name(err));
                advertising_active = false;
            } else {
                ESP_LOGI(LOG_TAG, "advertising has started.");
                advertising_active = true;
            }
            break;

        case ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT:
            if ((err = param->adv_stop_cmpl.status) != ESP_BT_STATUS_SUCCESS){
                ESP_LOGE(LOG_TAG, "adv stop failed: %s", esp_err_to_name(err));
            }
            else {
                ESP_LOGI(LOG_TAG, "stop adv successfully");
                advertising_active = false;
            }
            break;
        default:
            break;
    }
}

int load_key(uint8_t *dst, size_t size) {
    const esp_partition_t *keypart = esp_partition_find_first(0x40, 0x00, "key");
    if (keypart == NULL) {
        ESP_LOGE(LOG_TAG, "Could not find key partition");
        return 1;
    }
    esp_err_t status;
    status = esp_partition_read(keypart, 0, dst, size);    
    if (status != ESP_OK) {
        ESP_LOGE(LOG_TAG, "Could not read key from partition: %s", esp_err_to_name(status));
    }
    return status;
}

void set_addr_from_key(esp_bd_addr_t addr, uint8_t *public_key) {
	addr[0] = public_key[0] | 0b11000000;
	addr[1] = public_key[1];
	addr[2] = public_key[2];
	addr[3] = public_key[3];
	addr[4] = public_key[4];
	addr[5] = public_key[5];
}

void set_payload_from_key(uint8_t *payload, uint8_t *public_key) {
    /* copy last 22 bytes starting from payload[8] (payload[7] is reserved for DEVICE_ID) */
	memcpy(&payload[8], &public_key[6], 22);
	/* append two bits of public key */
	payload[29] = public_key[0] >> 6;
	/* Set ground truth device ID at adv_data[7] */
	payload[7] = DEVICE_ID;
}

/**
 * Generate a random MAC address
 * The first byte must have bits 0 and 1 set to 1 (0b11xxxxxx) for static random address
 */
void generate_random_mac(esp_bd_addr_t addr) {
	// Generate random bytes
	uint32_t random_val = esp_random();
	addr[0] = (uint8_t)(random_val & 0xFF);
	addr[1] = (uint8_t)((random_val >> 8) & 0xFF);
	addr[2] = (uint8_t)((random_val >> 16) & 0xFF);
	
	random_val = esp_random();
	addr[3] = (uint8_t)(random_val & 0xFF);
	addr[4] = (uint8_t)((random_val >> 8) & 0xFF);
	addr[5] = (uint8_t)((random_val >> 16) & 0xFF);
	
	// Set bits 0 and 1 of first byte to 1 for static random address type
	addr[0] = (addr[0] & 0xFC) | 0xC0;
	
	ESP_LOGI(LOG_TAG, "Generated random MAC: %02x %02x %02x %02x %02x %02x", 
		addr[0], addr[1], addr[2], addr[3], addr[4], addr[5]);
}

/**
 * Timer callback to shuffle MAC address periodically
 */
static void mac_shuffle_timer_callback(void* arg) {
	esp_err_t err;
	
	ESP_LOGI(LOG_TAG, "MAC shuffle timer triggered");
	
	// Stop advertising first
	if (advertising_active) {
		if ((err = esp_ble_gap_stop_advertising()) != ESP_OK) {
			ESP_LOGE(LOG_TAG, "Failed to stop advertising: %s", esp_err_to_name(err));
			return;
		}
		// Wait a bit for advertising to stop
		vTaskDelay(pdMS_TO_TICKS(100));
	}
	
	// Generate new random MAC address
	generate_random_mac(rnd_addr);
	
	// Set the new random address
	if ((err = esp_ble_gap_set_rand_addr(rnd_addr)) != ESP_OK) {
		ESP_LOGE(LOG_TAG, "Failed to set random address: %s", esp_err_to_name(err));
		return;
	}
	
	// Ensure ground truth device ID is set in adv_data[7]
	adv_data[7] = DEVICE_ID;
	
	// Reconfigure advertisement data
	if ((err = esp_ble_gap_config_adv_data_raw((uint8_t*)&adv_data, sizeof(adv_data))) != ESP_OK) {
		ESP_LOGE(LOG_TAG, "Failed to configure BLE adv: %s", esp_err_to_name(err));
		return;
	}
	
	ESP_LOGI(LOG_TAG, "MAC address shuffled, advertising will restart automatically");
}

void app_main(void)
{
    ESP_ERROR_CHECK(nvs_flash_init());
    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT));
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    esp_bt_controller_init(&bt_cfg);
    esp_bt_controller_enable(ESP_BT_MODE_BLE);

    esp_bluedroid_init();
    esp_bluedroid_enable();

    // Load the public key from the key partition
    static uint8_t public_key[28];
    if (load_key(public_key, sizeof(public_key)) != ESP_OK) {
        ESP_LOGE(LOG_TAG, "Could not read the key, stopping.");
        return;
    }

    ESP_LOGI(LOG_TAG, "Public key:");
    for (int i = 0; i < sizeof(public_key); i++) {
        printf("%02x ", public_key[i]);
    }
    printf("\n");

    set_addr_from_key(rnd_addr, public_key);
    set_payload_from_key(adv_data, public_key);
    
    // Ensure ground truth device ID is set
    adv_data[7] = DEVICE_ID;

    ESP_LOGI(LOG_TAG, "Device ID (ground truth): 0x%02x", DEVICE_ID);
    ESP_LOGI(LOG_TAG, "MAC shuffle period: %d seconds", MAC_SHUFFLE_PERIOD_SEC);
    ESP_LOGI(LOG_TAG, "using device address: %02x %02x %02x %02x %02x %02x", rnd_addr[0], rnd_addr[1], rnd_addr[2], rnd_addr[3], rnd_addr[4], rnd_addr[5]);

    ESP_LOGI(LOG_TAG, "using payload:");
    for (int i = 0; i < sizeof(adv_data); i++) {
        printf("%02x ", adv_data[i]);
    }
    printf("\n");

    esp_err_t status;
    //register the scan callback function to the gap module
    if ((status = esp_ble_gap_register_callback(esp_gap_cb)) != ESP_OK) {
        ESP_LOGE(LOG_TAG, "gap register error: %s", esp_err_to_name(status));
        return;
    }

    if ((status = esp_ble_gap_set_rand_addr(rnd_addr)) != ESP_OK) {
        ESP_LOGE(LOG_TAG, "couldn't set random address: %s", esp_err_to_name(status));
        return;
    }
    if ((esp_ble_gap_config_adv_data_raw((uint8_t*)&adv_data, sizeof(adv_data))) != ESP_OK) {
        ESP_LOGE(LOG_TAG, "couldn't configure BLE adv: %s", esp_err_to_name(status));
        return;
    }
    
    // Create periodic timer for MAC address shuffling
    const esp_timer_create_args_t mac_shuffle_timer_args = {
        .callback = &mac_shuffle_timer_callback,
        .name = "mac_shuffle_timer"
    };
    
    if ((status = esp_timer_create(&mac_shuffle_timer_args, &mac_shuffle_timer)) != ESP_OK) {
        ESP_LOGE(LOG_TAG, "Failed to create MAC shuffle timer: %s", esp_err_to_name(status));
        return;
    }
    
    // Start the periodic timer (convert seconds to microseconds)
    if ((status = esp_timer_start_periodic(mac_shuffle_timer, MAC_SHUFFLE_PERIOD_SEC * 1000000ULL)) != ESP_OK) {
        ESP_LOGE(LOG_TAG, "Failed to start MAC shuffle timer: %s", esp_err_to_name(status));
        return;
    }
    
    ESP_LOGI(LOG_TAG, "MAC shuffle timer started (period: %d seconds)", MAC_SHUFFLE_PERIOD_SEC);
    ESP_LOGI(LOG_TAG, "application initialized");
}
