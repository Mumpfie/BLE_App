package de.ovgu.ble_sensordatenerfassung;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    // TAG is used for informational messages
    private final static String TAG = MainActivity.class.getSimpleName();

    // Handler used to repeat the read task
    Handler readHandler = new Handler();

    // Variables to access objects from the layout such as buttons, switches, values
    private static TextView mVoltageValue;
    private static TextView mCurrentValue;
    private static TextView mSpeedValue;
    private static TextView mTorqueValue;
    private static TextView mEfficiencyValue;
    private static Button start_button;
    private static Button stop_button;

    // Variables to manage BLE connection
    private static boolean mConnectState;
    private static boolean mServiceConnected;
    private static MeasurementService mMeasurementService;

    private static final int REQUEST_ENABLE_BLE = 1;

    //This is required for Android 6.0 (Marshmallow)
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;

    /**
     * This manages the lifecycle of the BLE service.
     * When the service starts we get the service object and initialize the service.
     */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        /**
         * This is called when the MeasurementService is connected
         *
         * @param componentName the component name of the service that has been connected
         * @param service service being bound
         */
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.i(TAG, "onServiceConnected");
            mMeasurementService = ((MeasurementService.LocalBinder) service).getService();
            mServiceConnected = true;
            mMeasurementService.initialize();
            mMeasurementService.scan();
            /* After this we wait for the scan callback to detect that a device has been found */
            /* The callback broadcasts a message which is picked up by the mGattUpdateReceiver */
        }

        /**
         * This is called when the PSoCCapSenseService is disconnected.
         *
         * @param componentName the component name of the service that has been connected
         */
        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.i(TAG, "onServiceDisconnected");
            mMeasurementService = null;
        }
    };

    /**
     * This is called when the main activity is first created
     *
     * @param savedInstanceState is any state saved from prior creations of this activity
     */
    @TargetApi(Build.VERSION_CODES.M) // This is required for Android 6.0 (Marshmallow) to work
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Set up variables to point to the values on the display
        mVoltageValue = (TextView) findViewById(R.id.voltage_view);
        mCurrentValue = (TextView) findViewById(R.id.current_view);
        mSpeedValue = (TextView) findViewById(R.id.speed_view);
        mTorqueValue = (TextView) findViewById(R.id.torque_view);
        mEfficiencyValue = (TextView) findViewById(R.id.efficiency_view);

        // Set up a variables for accessing the buttons
        start_button = (Button) findViewById(R.id.start_button);
        stop_button = (Button) findViewById(R.id.stop_button);

        // Initialize service and connection state variable
        mServiceConnected = false;
        mConnectState = false;

        //This section required for Android 6.0 (Marshmallow)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android M Permission check 
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("This app needs location access ");
                builder.setMessage("Please grant location access so this app can detect devices.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }
        } //End of section for Android 6.0 (Marshmallow)
    } //End of onCreate() section

    //This method required for Android 6.0 (Marshmallow)
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("Permission for 6.0:", "Coarse location permission granted");
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover beacons when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }
                    });
                    builder.show();
                }
            }
        }
    } //End of section for Android 6.0 (Marshmallow)

    @Override
    protected void onResume() {
        super.onResume();
        // Register the broadcast receiver. This specified the messages the main activity looks for from the PSoCCapSenseLedService
        final IntentFilter filter = new IntentFilter();
        filter.addAction(MeasurementService.ACTION_BLESCAN_CALLBACK);
        filter.addAction(MeasurementService.ACTION_CONNECTED);
        filter.addAction(MeasurementService.ACTION_DISCONNECTED);
        filter.addAction(MeasurementService.ACTION_SERVICES_DISCOVERED);
        filter.addAction(MeasurementService.ACTION_DATA_RECEIVED);
        registerReceiver(mBleUpdateReceiver, filter);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BLE && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mBleUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Close and unbind the service when the activity goes away
        mMeasurementService.close();
        unbindService(mServiceConnection);
        mMeasurementService = null;
        mServiceConnected = false;
    }

    /**
     * This method handles the start bluetooth button
     *
     * @param view the view object
     */
    public void startBluetooth(View view) {

        // Find BLE service and adapter
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter mBluetoothAdapter = bluetoothManager.getAdapter();

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLE);
        }

        // Start the BLE Service
        Log.d(TAG, "Starting BLE Service");
        Intent gattServiceIntent = new Intent(this, MeasurementService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        Log.d(TAG, "Bluetooth is Enabled");

        /*
        if(mServiceConnected) {
            mMeasurementService.scan();
            Log.v(TAG, "Scanning for devices.");
        } else {
            Log.v(TAG, "No service connected.");
        }
        */
        /* After this we wait for the scan callback to detect that a device has been found */
        /* The callback broadcasts a message which is picked up by the mGattUpdateReceiver */
    }

    /**
     * This method handles the stop button
     *
     * @param view the view object
     */
    public void stopBluetooth(View view) {
        mMeasurementService.disconnect();
        /* After this we wait for the gatt callback to report the device is disconnected */
        /* That event broadcasts a message which is picked up by the mGattUpdateReceiver */
    }

    /**
     * Listener for BLE event broadcasts
     */
    private final BroadcastReceiver mBleUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            switch (action) {
                case MeasurementService.ACTION_BLESCAN_CALLBACK:
                    Log.d(TAG, "Connecting to GATT");
                    mMeasurementService.connect();
                    /* After this we wait for the gatt callback to report the device is connected */
                    /* That event broadcasts a message which is picked up by the mGattUpdateReceiver */
                    break;

                case MeasurementService.ACTION_CONNECTED:
                    // If statement necessary because GATT_CONNECTED action can be triggered when sending notifications
                    if(!mConnectState) {
                        Log.d(TAG, "Trying to Discovering Services");
                        /* This will discover the service and the characteristics */
                        mMeasurementService.discoverServices();
                        /* After this we wait for the gatt callback to report the services and characteristics */
                        /* That event broadcasts a message which is picked up by the mGattUpdateReceiver */
                        start_button.setEnabled(false);
                        stop_button.setEnabled(true);
                        /* This will start the repeated read task*/
                        //readHandler.post(readRoutine);
                    }
                    break;

                case MeasurementService.ACTION_DISCONNECTED:
                    stop_button.setEnabled(false);
                    start_button.setEnabled(true);
                    mConnectState = false;
                    /* Necessary to enable reconnect */
                    unbindService(mServiceConnection);
                    /* This will stop the repeated read task*/
                    //readHandler.removeCallbacks(readRoutine);
                    Log.d(TAG, "Disconnected");
                    break;

                case MeasurementService.ACTION_SERVICES_DISCOVERED:
                    Log.d(TAG, "Services Discovered");

                    /* Enable notifications*/
                    mMeasurementService.enableNotifications();
                    break;

                case MeasurementService.ACTION_DATA_RECEIVED:
                    // This is called after a notify or read completes
                    String Voltage = mMeasurementService.getVoltageValue() + " V";
                    mVoltageValue.setText(Voltage);

                    String Current = mMeasurementService.getCurrentValue() + " A";
                    mCurrentValue.setText(Current);

                    String Speed = mMeasurementService.getSpeedValue() + " min\u207B\u00B9";
                    mSpeedValue.setText(Speed);

                    String Torque = mMeasurementService.getTorqueValue() + " Nm";
                    mTorqueValue.setText(Torque);

                    String Efficiency = mMeasurementService.getEfficiencyValue() + " \u0025";
                    mEfficiencyValue.setText(Efficiency);

                    break;

                default:
                    break;
            }
        }
    };
}
