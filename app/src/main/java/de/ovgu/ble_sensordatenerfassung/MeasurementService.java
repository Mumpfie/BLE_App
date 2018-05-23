package de.ovgu.ble_sensordatenerfassung;

import android.annotation.TargetApi;
import android.app.IntentService;
import android.bluetooth.BluetoothGattCharacteristic;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class MeasurementService extends Service {
    private final static String TAG = MeasurementService.class.getSimpleName();

    // Bluetooth objects that we need to interact with
    private static BluetoothManager mBluetoothManager;
    private static BluetoothAdapter mBluetoothAdapter;
    private static BluetoothLeScanner mLEScanner;
    private static BluetoothDevice mLeDevice;
    private static BluetoothGatt mBluetoothGatt;

    // Bluetooth characteristics that we need to read/write
    private static BluetoothGattCharacteristic mVoltageCharacterisitc;
    private static BluetoothGattCharacteristic mCurrentCharacterisitc;
    private static BluetoothGattCharacteristic mSpeedCharacterisitc;
    private static BluetoothGattCharacteristic mTorqueCharacterisitc;
    private static BluetoothGattCharacteristic mEfficiencyCharacterisitc;

    // UUIDs for the service and characteristics that the custom CapSenseLED service uses
    private final static String measurementServiceUUID =        "5D8EEE9C-F629-4429-A328-BAF6ECB10BBB";
    public  final static String voltageCharacterisitcUUID =     "4B4369F0-0929-4AF2-90EE-9BE9DF9779FC";
    public  final static String currentCharacterisitcUUID =     "E570157C-65B9-45FF-83FF-0D963F8F7056";
    public  final static String speedCharacterisitcUUID =       "E570157C-65B9-45FF-83FF-0D963F8F7056";
    public  final static String torqueCharacterisitcUUID =      "E570157C-65B9-45FF-83FF-0D963F8F7056";
    public  final static String efficiencyCharacterisitcUUID =  "E570157C-65B9-45FF-83FF-0D963F8F7056";
    //private final static String CccdUUID =                   "00002902-0000-1000-8000-00805f9b34fb";

    // Variables to keep track of the LED switch state and CapSense Value
    private static float mVoltageValue = 0.0F;
    private static float mCurrentValue = 0.0F;
    private static int mSpeedValue = 0;
    private static float mTorqueValue = 0.0F;
    private static int mEfficiencyValue = 0;

    // Actions used during broadcasts to the main activity
    public final static String ACTION_BLESCAN_CALLBACK =
            "de.ovgu.bluetooth.le.ACTION_BLESCAN_CALLBACK";
    public final static String ACTION_CONNECTED =
            "de.ovgu.bluetooth.le.ACTION_CONNECTED";
    public final static String ACTION_DISCONNECTED =
            "de.ovgu.bluetooth.le.ACTION_DISCONNECTED";
    public final static String ACTION_SERVICES_DISCOVERED =
            "de.ovgu.bluetooth.le.ACTION_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_RECEIVED =
            "de.ovgu.bluetooth.le.ACTION_DATA_RECEIVED";

    public MeasurementService() {
    }

    public class LocalBinder extends Binder {
        MeasurementService getService() {
            return MeasurementService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // The BLE close method is called when we unbind the service to free up the resources.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Scans for BLE devices that support the service we are looking for
     */
    public void scan() {
        /* Scan for devices and look for the one with the service that we want */
        UUID measurementService = UUID.fromString(measurementServiceUUID);
        UUID[] measurementServiceArray = {measurementService};

        // Use old scan method for versions older than lollipop
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            //noinspection deprecation
            mBluetoothAdapter.startLeScan(measurementServiceArray, mLeScanCallback);
        } else { // New BLE scanning introduced in LOLLIPOP
            ScanSettings settings;
            List<ScanFilter> filters;
            mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
            settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            filters = new ArrayList<>();
            // We will scan just for the CAR's UUID
            ParcelUuid PUuid = new ParcelUuid(measurementService);
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(PUuid).build();
            filters.add(filter);
            mLEScanner.startScan(filters, settings, mScanCallback);
        }
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public boolean connect(){
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            return mBluetoothGatt.connect();
        }

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = mLeDevice.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        return true;
    }

    /**
     * Runs service discovery on the connected device.
     */
    public void discoverServices() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.discoverServices();
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * This method is used to read the voltage value from the device
     */
    public void readVoltageCharacteristic() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(mVoltageCharacterisitc);
    }

    /**
     * This method is used to read the current value from the device
     */
    public void readCurrentCharacteristic() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(mCurrentCharacterisitc);
    }

    /**
     * This method is used to read the speed value from the device
     */
    public void readSpeedCharacteristic() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(mSpeedCharacterisitc);
    }

    /**
     * This method is used to read the torque value from the device
     */
    public void readTorqueCharacteristic() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(mTorqueCharacterisitc);
    }

    /**
     * This method is used to read the efficiency value from the device
     */
    public void readEfficiencyCharacteristic() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(mEfficiencyCharacterisitc);
    }

    public void readCharakteristics() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(mVoltageCharacterisitc);
        mBluetoothGatt.readCharacteristic(mCurrentCharacterisitc);
        mBluetoothGatt.readCharacteristic(mSpeedCharacterisitc);
        mBluetoothGatt.readCharacteristic(mTorqueCharacterisitc);
        mBluetoothGatt.readCharacteristic(mEfficiencyCharacterisitc);


        // Notify the main activity that new data is available
        broadcastUpdate(ACTION_DATA_RECEIVED);
    }

    public float getVoltageValue() {
        return mVoltageValue;
    }

    public float getCurrentValue() {
        return mCurrentValue;
    }

    public int getSpeedValue() {
        return mSpeedValue;
    }

    public float getTorqueValue() {
        return mTorqueValue;
    }

    public int getEfficiencyValue() {
        return mEfficiencyValue;
    }

    /**
     * Implements the callback for when scanning for devices has found a device with
     * the service we are looking for.
     *
     * This is the callback for BLE scanning on versions prior to Lollipop
     */
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                    mLeDevice = device;
                    //noinspection declaration
                    mBluetoothAdapter.stopLeScan(mLeScanCallback); // Stop scanning after the first device is found
                    broadcastUpdate(ACTION_BLESCAN_CALLBACK); // Tell the main activity that a device has been found
                }
            };

    /**
     * Implements the callback for when scanning for devices has faound a device with
     * the service we are looking for.
     *
     * This is the callback for BLE scanning for LOLLIPOP and later
     */
    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            mLeDevice = result.getDevice();
            mLEScanner.stopScan(mScanCallback); // Stop scanning after the first device is found
            broadcastUpdate(ACTION_BLESCAN_CALLBACK); // Tell the main activity that a device has been found
        }
    };

    /**
     * Implements callback methods for GATT events that the app cares about.  For example,
     * connection change and services discovered.
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastUpdate(ACTION_CONNECTED);
                Log.i(TAG, "Connected to GATT server.");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(ACTION_DISCONNECTED);
            }
        }

        /**
         * This is called when a service discovery has completed.
         *
         * It gets the characteristics we are interested in and then
         * broadcasts an update to the main activity.
         *
         * @param gatt The GATT database object
         * @param status Status of whether the write was successful.
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status){
            // Get just the service that we are looking for
            BluetoothGattService mService = gatt.getService(UUID.fromString(measurementServiceUUID));
            /* Get characteristics from our desired service */
            mVoltageCharacterisitc = mService.getCharacteristic(UUID.fromString(voltageCharacterisitcUUID));
            mCurrentCharacterisitc = mService.getCharacteristic(UUID.fromString(currentCharacterisitcUUID));
            mSpeedCharacterisitc = mService.getCharacteristic(UUID.fromString(speedCharacterisitcUUID));
            mTorqueCharacterisitc = mService.getCharacteristic(UUID.fromString(torqueCharacterisitcUUID));
            mEfficiencyCharacterisitc = mService.getCharacteristic(UUID.fromString(efficiencyCharacterisitcUUID));

            // Read the current values from the device
            readCharakteristics();

            // Broadcast that service/characteristic/descriptor discovery is done
            broadcastUpdate(ACTION_SERVICES_DISCOVERED);
        }

        /**
         * This is called when a read completes
         *
         * @param gatt the GATT database object
         * @param characteristic the GATT characteristic that was read
         * @param status the status of the transaction
         */
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Verify that the read was the LED state
                String uuid = characteristic.getUuid().toString();
                final byte[] data = characteristic.getValue();
                ByteBuffer buf = ByteBuffer.wrap(data);

                if(uuid.equals(voltageCharacterisitcUUID)) {
                    mVoltageValue = buf.getFloat();
                }else if(uuid.equals(currentCharacterisitcUUID)) {
                    mCurrentValue = buf.getFloat();
                }else if(uuid.equals(speedCharacterisitcUUID)) {
                    mSpeedValue = buf.getInt();
                }else if(uuid.equals(torqueCharacterisitcUUID)) {
                    mTorqueValue = buf.getFloat();
                }else if(uuid.equals(efficiencyCharacterisitcUUID)) {
                    mEfficiencyValue = buf.getInt();
                }
            }
        }
    }; // End of GATT event callback methods

    /**
     * Sends a broadcast to the listener in the main activity.
     *
     * @param action The type of action that occurred.
     */
    private void broadcastUpdate(final String action){
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

}
