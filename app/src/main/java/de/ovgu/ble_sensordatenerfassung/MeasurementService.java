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
import android.nfc.Tag;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;

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
    private final static String measurementServiceUUID =        "5d8eee9c-f629-4429-a328-baf6ecb10bbb";
    public  final static String voltageCharacterisitcUUID =     "4b4369f0-0929-4af2-90ee-9be9df9779fc";
    public  final static String currentCharacterisitcUUID =     "e570157c-65b9-45ff-83ff-0d963f8f7056";
    public  final static String speedCharacterisitcUUID =       "d0524569-7ad1-4a2c-9b41-a62977af90f1";
    public  final static String torqueCharacterisitcUUID =      "0506ecfe-b5e1-43df-8129-dcda57e2d1b0";
    public  final static String efficiencyCharacterisitcUUID =  "20b0b221-492e-4c1a-90d6-4b8a12f91a43";
    //private final static String CccdUUID =                   "00002902-0000-1000-8000-00805f9b34fb";

    // Variables to keep track of the values
    private static String mVoltageValue = "0.0";
    private static String mCurrentValue = "0.0";
    private static String mSpeedValue = "0";
    private static String mTorqueValue = "0.0";
    private static String mEfficiencyValue = "0";

    // Actions used during broadcasts to the main activity
    public final static String ACTION_BLESCAN_CALLBACK =
            "de.ovgu.ble_sensordatenerfassung.ACTION_BLESCAN_CALLBACK";
    public final static String ACTION_CONNECTED =
            "de.ovgu.ble_sensordatenerfassung.ACTION_CONNECTED";
    public final static String ACTION_DISCONNECTED =
            "de.ovgu.ble_sensordatenerfassung.ACTION_DISCONNECTED";
    public final static String ACTION_SERVICES_DISCOVERED =
            "de.ovgu.ble_sensordatenerfassung.ACTION_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_RECEIVED =
            "de.ovgu.ble_sensordatenerfassung.ACTION_DATA_RECEIVED";

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

        //mBluetoothAdapter.startLeScan(measurementServiceArray, mLeScanCallback);


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

    public void readCharacteristics() {
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



    public String getVoltageValue() {
        return mVoltageValue;
    }

    public String getCurrentValue() {
        return mCurrentValue;
    }

    public String getSpeedValue() {
        return mSpeedValue;
    }

    public String getTorqueValue() {
        return mTorqueValue;
    }

    public String getEfficiencyValue() {
        return mEfficiencyValue;
    }

    /* Enables notifications*/
    public void enableNotifications() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        mBluetoothGatt.setCharacteristicNotification(mVoltageCharacterisitc, true);
        mBluetoothGatt.setCharacteristicNotification(mCurrentCharacterisitc, true);
        mBluetoothGatt.setCharacteristicNotification(mSpeedCharacterisitc, true);
        mBluetoothGatt.setCharacteristicNotification(mTorqueCharacterisitc, true);
        mBluetoothGatt.setCharacteristicNotification(mEfficiencyCharacterisitc, true);
        Log.v(TAG, "Notifications enabled.");
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
                    Log.v(TAG, "Device found.");
                    broadcastUpdate(ACTION_BLESCAN_CALLBACK); // Tell the main activity that a device has been found
                }
            };

    /**
     * Implements the callback for when scanning for devices has found a device with
     * the service we are looking for.
     *
     * This is the callback for BLE scanning for LOLLIPOP and later
     */
    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            mLeDevice = result.getDevice();
            mLEScanner.stopScan(mScanCallback); // Stop scanning after the first device is found
            Log.v(TAG, "Device found.");
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
            readCharacteristics();

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

                if(uuid.equals(voltageCharacterisitcUUID)) {
                    mVoltageValue = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_FLOAT,0).toString();
                }else if(uuid.equals(currentCharacterisitcUUID)) {
                    mCurrentValue = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_FLOAT,0).toString();
                }else if(uuid.equals(speedCharacterisitcUUID)) {
                    mSpeedValue = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_FLOAT,0).toString();
                }else if(uuid.equals(torqueCharacterisitcUUID)) {
                    mTorqueValue = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_FLOAT,0).toString();
                }else if(uuid.equals(efficiencyCharacterisitcUUID)) {
                    mEfficiencyValue = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_FLOAT,0).toString();
                }
            }
        }

        /**
         * This is called when a characteristic with notify set changes.
         * It broadcasts an update to the main activity with the changed data.
         *
         * @param gatt The GATT database object
         * @param characteristic The characteristic that was changed
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            String uuid = characteristic.getUuid().toString();

            byte[] tmp;
            float floatTmp = characteristicToFloat(characteristic.getValue());

            // New values are stored
            switch(uuid)
            {
                case voltageCharacterisitcUUID:
                    mVoltageValue = String.format("%.1f",floatTmp);
                    break;

                case currentCharacterisitcUUID:
                    mCurrentValue = String.format("%.1f",floatTmp);
                    break;

                case speedCharacterisitcUUID:
                    mSpeedValue = String.format("%.0f",floatTmp);
                    break;

                case torqueCharacterisitcUUID:
                    mTorqueValue = String.format("%.1f",floatTmp);
                    break;

                case efficiencyCharacterisitcUUID:
                    mEfficiencyValue = String.format("%.1f",floatTmp);
                    break;
            }

            // Notify the main activity that new data is available
            broadcastUpdate(ACTION_DATA_RECEIVED);
        }
    }; // End of GATT event callback methods

    private float characteristicToFloat(byte[] b){
        byte tmp;
        tmp = b[0];
        b[0] = b[3];
        b[3] = tmp;
        tmp = b[1];
        b[1] = b[2];
        b[2] = tmp;

        ByteBuffer buf = ByteBuffer.wrap(b);
        return buf.getFloat();

    }

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
