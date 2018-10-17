package goodnightgoodlife.kmutt.com.experiment;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.os.Handler;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import com.choosemuse.libmuse.*;

import org.w3c.dom.Text;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.google.firebase.firestore.FirebaseFirestore;

public class MainActivity extends AppCompatActivity {

    // TAG for debugging
    private final String TAG = "GNGL Experiment";

    // Muse manager for detect muse headband
    private MuseManagerAndroid manager;

    // Refer to muse headband
    private Muse muse;

    // Connection listener for listening to muse
    private ConnectionListener connectionListener;

    // Receive data packet
    private DataListener dataListener;

    // Buffer for keeping data
    private final double[] eegBuffer = new double[6];
    private boolean eegStale;
    private final double[] thetaBuffer = new double[6];
    private boolean thetaStale;
    private final double[] accelBuffer = new double[3];
    private boolean accelStale;

    // Bluetooth Adapter to connect and find bluetooth
    private BluetoothAdapter mBluetoothAdapter;
    private static final int MY_PERMISSIONS_REQUEST_BLUETOOTH = 0;
    private static final int REQUEST_ENABLE_BT = 1;

    // Spinner for containing muse headband
    private ArrayAdapter<String> spinnerAdapter;

    // Pause data from the headband
    private boolean dataTransmission = true;

    // Handler for running the thread;
    private Handler handler = new Handler();

    //UI
    private Button refreshButton;
    private Button connectButton;
    private Button database;
    private Button relaxButton;
    private Button unrelaxButton;
    private TextView theta_1;
    private TextView theta_2;
    private TextView theta_3;
    private TextView theta_4;
    private TextView collection;

    // Database variables
    public FirebaseFirestore db;

    // Temp variable
    private String collectionName;

    private String state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Firebase Firestore instance
        db = FirebaseFirestore.getInstance();

        // Come before any other api call
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);

        Log.i(TAG, "LibMuse version=" + LibmuseVersion.instance().getString());

        WeakReference<MainActivity> weakActivity = new WeakReference<MainActivity>(this);

        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);

        manager.setMuseListener(new MuseL(weakActivity));

        initUI();

        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            /***Android 6.0 and higher need to request permission*****/
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            {

                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, MY_PERMISSIONS_REQUEST_BLUETOOTH);
            }
            else{
                checkConnect();
            }
        }
        else {
            checkConnect();
        }

        handler.post(tickUi);

        relaxButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state = "relax";
            }
        });

        unrelaxButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state = "unrelax";
            }
        });

        database.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!collection.getText().toString().matches("")) {
                    collectionName = collection.getText().toString();
                }else{
                    Toast.makeText(getApplicationContext(), "Please input collection name first", Toast.LENGTH_SHORT ).show();
                }
            }
        });

        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.stopListening();
                manager.startListening();
            }
        });

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.stopListening();

                List<Muse> availableMuses = manager.getMuses();
                Spinner musesSpinner = (Spinner) findViewById(R.id.muse_spinner);

                // Check that we actually have something to connect to.
                if (availableMuses.size() < 1 || musesSpinner.getAdapter().getCount() < 1) {
                    Log.e(TAG, "availableMuse size" + availableMuses.size());
                    Toast.makeText(getApplicationContext(), "AvailableMuse size" + availableMuses.size(), Toast.LENGTH_LONG).show();
                    Log.w(TAG, "There is nothing to connect to");
                    Toast.makeText(getApplicationContext(), "There is nothing to connect to", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getApplicationContext(), "AvailableMuse size" + availableMuses.size(), Toast.LENGTH_LONG).show();
                    Toast.makeText(getApplicationContext(), "Connecting", Toast.LENGTH_SHORT).show();
                    // Cache the Muse that the user has selected.
                    muse = availableMuses.get(musesSpinner.getSelectedItemPosition());
                    // Unregister all prior listeners and register our data listener to
                    // receive the MuseDataPacketTypes we are interested in.  If you do
                    // not register a listener for a particular data type, you will not
                    // receive data packets of that type.
                    muse.unregisterAllListeners();
                    muse.registerConnectionListener(connectionListener);
                    muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
                    muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
                    muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
                    muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
                    muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
                    muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);
                    Toast.makeText(getApplicationContext(), "Connected", Toast.LENGTH_LONG).show();
                    // Initiate a connection to the headband and stream the data asynchronously.
                    muse.runAsynchronously();
                }
            }
        });
    }

    private void checkConnect(){
        if (!mBluetoothAdapter.isEnabled()) {
            /****Request turn on Bluetooth***************/
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }else{
            Log.e("Bluetooth", "bluetooth on");
        }
    }

    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                //updateEeg();
            }
            if (thetaStale) {
                updateAlpha();
            }
            handler.postDelayed(tickUi, 5000 / 60);
        }
    };

    private void updateAlpha(){
        Map<String, Object> thetaMap = new HashMap<>();
        if(!Double.isNaN(thetaBuffer[0])) {
            theta_1.setText("" + thetaBuffer[0]);
            thetaMap.put("sensor_0", thetaBuffer[0]);
        }
        if(!Double.isNaN(thetaBuffer[1])) {
            theta_2.setText("" + thetaBuffer[1]);
            thetaMap.put("sensor_1", thetaBuffer[1]);
        }
        if(!Double.isNaN(thetaBuffer[2])) {
            theta_3.setText("" + thetaBuffer[2]);
            thetaMap.put("sensor_2", thetaBuffer[2]);
        }
        if(!Double.isNaN(thetaBuffer[3])) {
            theta_4.setText("" + thetaBuffer[3]);
            thetaMap.put("sensor_3", thetaBuffer[3]);
        }
        if((!collectionName.matches("") || collectionName != null) && !thetaMap.isEmpty() /*|| (!Double.isNaN(thetaBuffer[0]) &&
                !Double.isNaN(thetaBuffer[1]) && !Double.isNaN(thetaBuffer[2]) && !Double.isNaN(thetaBuffer[3]))*/ ){
            streaming(collectionName, db, state, thetaMap);
        }
    }

    public void initUI(){
        refreshButton = (Button) findViewById(R.id.refresh);
        connectButton = (Button) findViewById(R.id.connect);
        spinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = (Spinner) findViewById(R.id.muse_spinner);
        musesSpinner.setAdapter(spinnerAdapter);
        theta_1 = (TextView) findViewById(R.id.theta_1);
        theta_2 = (TextView) findViewById(R.id.theta_2);
        theta_3 = (TextView) findViewById(R.id.theta_3);
        theta_4 = (TextView) findViewById(R.id.theta_4);
        collection = (TextView) findViewById(R.id.collection);
        database = (Button) findViewById(R.id.database);
        relaxButton = (Button) findViewById(R.id.relaxButton);
        unrelaxButton = (Button) findViewById(R.id.unrelaxButton);
    }

    private void streaming(String colName, FirebaseFirestore db, String state, Map<String, Object> map){
        if(state.equals("relax")) {
            db.collection(colName).document("relax").set(map);
        }else if(state.equals("unrelax")){
            db.collection(colName).document("unrelax").set(map);
        }else{
            db.collection(colName).document("unrelax").set(map);
        }
    }

    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    }

    public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
        // valuesSize returns the number of data values contained in the packet.
        final long n = p.valuesSize();
        switch (p.packetType()) {
            case EEG:
                assert(eegBuffer.length >= n);
                getEegChannelValues(eegBuffer,p);
                eegStale = true;
                break;
            case THETA_ABSOLUTE:
                assert(thetaBuffer.length >= n);
                getEegChannelValues(thetaBuffer,p);
                thetaStale = true;
                break;
            case BATTERY:
                break;
            case DRL_REF:
                break;
            case QUANTIZATION:
                break;
            default:
                break;
        }
    }

    public void museListChanged() {
        final List<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName() + " - " + m.getMacAddress());
        }
    }

    class MuseL extends MuseListener {
        final WeakReference<MainActivity> activityRef;

        MuseL(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }

    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MainActivity> activityRef;

        ConnectionListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
           // activityRef.get().receiveMuseConnectionPacket(p, muse);
        }
    }

    class DataListener extends MuseDataListener {
        final WeakReference<MainActivity> activityRef;

        DataListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p, muse);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            //activityRef.get().receiveMuseArtifactPacket(p, muse);
        }
    }
}
