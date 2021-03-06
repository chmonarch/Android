package cn.com.heaton.blelibrary.ble;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import cn.com.heaton.blelibrary.BuildConfig;
import cn.com.heaton.blelibrary.ble.callback.wrapper.ConnectWrapperLisenter;
import cn.com.heaton.blelibrary.ble.callback.wrapper.NotifyWrapperLisenter;
import cn.com.heaton.blelibrary.ble.model.BleDevice;
import cn.com.heaton.blelibrary.ble.request.ConnectRequest;
import cn.com.heaton.blelibrary.ble.request.NotifyRequest;
import cn.com.heaton.blelibrary.ble.request.Rproxy;
import cn.com.heaton.blelibrary.ota.OtaListener;


@SuppressLint("NewApi")
public class BluetoothLeService extends Service {

    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private Handler mHandler;
    private Ble.Options mOptions;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private final Object mLocker = new Object();
    private List<BluetoothGattCharacteristic> mNotifyCharacteristics = new ArrayList<>();//Notification attribute callback array
    private int mNotifyIndex = 0;//Notification feature callback list
    private BluetoothGattCharacteristic mOtaWriteCharacteristic;//Ota ble send the object
    private boolean mOtaUpdating = false;//Whether the OTA is updated
    private Map<String, BluetoothGattCharacteristic> mWriteCharacteristicMap = new HashMap<>();
    private Map<String, BluetoothGattCharacteristic> mReadCharacteristicMap = new HashMap<>();
    private Map<String, Runnable> mTimeoutTasks = new HashMap<>();

    /**
     * Multiple device connections must put the gatt object in the collection
     */
    private Map<String, BluetoothGatt> mBluetoothGattMap = new HashMap<>();
    /**
     * The address of the connected device
     */
    private List<String> mConnectedAddressList = new ArrayList<>();

    private ConnectWrapperLisenter mConnectWrapperLisenter;

    private NotifyWrapperLisenter<BleDevice> mNotifyWrapperLisenter;

    private OtaListener mOtaListener;//Ota update operation listener

    /**
     * ???????????????????????????????????????????????????
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                            int newState) {
            BluetoothDevice device = gatt.getDevice();
            //remove timeout callback
            Runnable timeoutRunnable = mTimeoutTasks.get(device.getAddress());
            if (timeoutRunnable != null){
                mTimeoutTasks.remove(device.getAddress());
                mHandler.removeCallbacks(timeoutRunnable);
            }
            //There is a problem here Every time a new object is generated that causes the same device to be disconnected and the connection produces two objects
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mConnectedAddressList.add(device.getAddress());
                    if (mConnectWrapperLisenter != null){
                        mConnectWrapperLisenter.onConnectionChanged(device, BleStates.BleStatus.CONNECTED);
                    }
                    L.i(TAG, "handleMessage:>>>>>>>>CONNECTED.");
                    // Attempts to discover services after successful connection.
                    Log.i(TAG, "Attempting to start service discovery");
                    Objects.requireNonNull(mBluetoothGattMap.get(device.getAddress())).discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    L.i(TAG, "Disconnected from GATT server.");
                    if (mConnectWrapperLisenter != null){
                        mConnectWrapperLisenter.onConnectionChanged(device, BleStates.BleStatus.DISCONNECT);
                    }
                    close(device.getAddress());
                }
            } else {
                //Occurrence 133 or 257 19 Equal value is not 0: Connection establishment failed due to protocol stack
                L.e(TAG, "onConnectionStateChange>>>>>>>>: " + "Connection status is abnormal:" + status);
                close(device.getAddress());
                if (mConnectWrapperLisenter != null){
                    mConnectWrapperLisenter.onConnectException(device);
                    mConnectWrapperLisenter.onConnectionChanged(device, BleStates.BleStatus.DISCONNECT);
                }
            }

        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void onMtuChanged(android.bluetooth.BluetoothGatt gatt, int mtu, int status){
            if (gatt != null && gatt.getDevice() != null) {
                BleDevice d = Ble.getInstance().getBleDevice(gatt.getDevice());
                L.e(TAG, "onMtuChanged mtu=" + mtu + ",status=" + status);
                mHandler.obtainMessage(BleStates.BleStatus.MTUCHANGED, mtu, status, d).sendToTarget();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (mNotifyWrapperLisenter != null) {
                    mNotifyWrapperLisenter.onServicesDiscovered(gatt);
                }
                //Empty the notification attribute list
                mNotifyCharacteristics.clear();
                mNotifyIndex = 0;
                //Start setting notification feature
                displayGattServices(gatt.getDevice().getAddress(), getSupportedGattServices(gatt.getDevice().getAddress()));
            } else {
                L.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            L.d(TAG, "onCharacteristicRead:" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mHandler.obtainMessage(BleStates.BleStatus.Read, characteristic).sendToTarget();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            L.i(TAG, "--------write success----- status:" + status);
            synchronized (mLocker) {
                L.i(TAG, gatt.getDevice().getAddress() + " -- onCharacteristicWrite: " + status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (mOptions.uuid_ota_write_cha.equals(characteristic.getUuid())) {
                        if (mOtaListener != null) {
                            mOtaListener.onWrite();
                        }
                        return;
                    }
                    mHandler.obtainMessage(BleStates.BleStatus.Write, characteristic).sendToTarget();
                }
            }
        }

        /**
         * ??????????????????????????????????????????????????????????????????????????????????????????????????????
         * ???setnotify???true????????????????????????MCU????????????????????????????????????????????????????????????
         * @param gatt ??????gatt??????
         * @param characteristic ????????????????????????
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            synchronized (mLocker) {
                if (gatt.getDevice() == null)return;
                BleDevice d = Ble.getInstance().getBleDevice(gatt.getDevice());
                L.i(TAG, gatt.getDevice().getAddress() + " -- onCharacteristicChanged: "
                        + (characteristic.getValue() != null ? Arrays.toString(characteristic.getValue()) : ""));
                if (mOptions.uuid_ota_write_cha.equals(characteristic.getUuid()) || mOptions.uuid_ota_notify_cha.equals(characteristic.getUuid())) {
                    if (mOtaListener != null) {
                        mOtaListener.onChange(characteristic.getValue());
                    }
                    return;
                }

                if(d != null){
                    if (mNotifyWrapperLisenter != null) {
                        mNotifyWrapperLisenter.onChanged(d, characteristic);
                    }
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            UUID uuid = descriptor.getCharacteristic().getUuid();
            L.i(TAG, "onDescriptorWrite");
            L.i(TAG, "descriptor_uuid:" + uuid);
            synchronized (mLocker) {
                L.w(TAG, " -- onDescriptorWrite: " + status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (mNotifyCharacteristics != null && mNotifyCharacteristics.size() > 0 && mNotifyIndex < mNotifyCharacteristics.size()) {
                        setCharacteristicNotification(gatt.getDevice().getAddress(), mNotifyCharacteristics.get(mNotifyIndex++), true);
                    } else {
                        L.i(TAG, "====setCharacteristicNotification is true,ready to sendData===");
                        if (mNotifyWrapperLisenter != null) {
                            mNotifyWrapperLisenter.onNotifySuccess(gatt);
                        }
                    }
                }
                mHandler.obtainMessage(BleStates.BleStatus.DescriptorWriter, gatt).sendToTarget();
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            UUID uuid = descriptor.getCharacteristic().getUuid();
            L.i(TAG, "onDescriptorRead");
            L.i(TAG, "descriptor_uuid:" + uuid);
            mHandler.obtainMessage(BleStates.BleStatus.DescriptorRead, gatt).sendToTarget();
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            System.out.println("rssi = " + rssi);
            mHandler.obtainMessage(BleStates.BleStatus.ReadRssi, rssi).sendToTarget();
        }
    };

    /**
     *
     * @return ???????????????????????????
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (mBluetoothManager == null) return null;
        return mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
    }


    public class LocalBinder extends Binder {
        public BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        L.e(TAG,"onBind>>>>");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        L.e(TAG,"onUnbind>>>>");
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    public void initialize(Ble.Options options) {
        this.mConnectWrapperLisenter = Rproxy.getInstance().getRequest(ConnectRequest.class);
        this.mNotifyWrapperLisenter = Rproxy.getInstance().getRequest(NotifyRequest.class);
        this.mHandler = BleHandler.of();
        this.mOptions = options;
    }

    /**
     * Initialize Bluetooth
     * For API level 18 and above, get a reference to BluetoothAdapter
     * Bluetooth 4.0, that API level> = 18, and supports Bluetooth 4.0 phone can use,
     * if the mobile phone system version API level <18, is not used Bluetooth 4
     * android system 4.3 above, the phone supports Bluetooth 4.0
     * @return
     */
    public boolean initBLE() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                L.e(TAG, "Unable to initBLE BluetoothManager.");
                return false;
            }
        }
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            L.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    private Runnable checkTimeOutTask(final BluetoothDevice device) {
        return new Runnable() {
            @Override
            public void run() {
                if (mConnectWrapperLisenter != null) {
                    mConnectWrapperLisenter.onConnectTimeOut(device);
                    close(device.getAddress());
                }
            }
        };
    }

    /**
     * ????????????
     *
     * @param address Bluetooth address
     * @return Connection result
     */
    public boolean connect(final String address) {
        if (mConnectedAddressList.contains(address)) {
            L.d(TAG, "This is device already connected.");
            return true;
        }
        if (mBluetoothAdapter == null) {
            L.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }
        // getRemoteDevice(address) will throw an exception if the device address is invalid,
        // so it's necessary to check the address
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            L.d(TAG, "the device address is invalid");
            return false;
        }
        // Previously connected device. Try to reconnect. ()
        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            L.d(TAG, "no device");
            return false;
        }
        //10s after the timeout prompt
        Runnable timeOutRunnable = checkTimeOutTask(device);
        mTimeoutTasks.put(device.getAddress(), timeOutRunnable);
        mHandler.postDelayed(timeOutRunnable, mOptions.getConnectTimeout());
        if (mConnectWrapperLisenter != null){
            mConnectWrapperLisenter.onConnectionChanged(device, BleStates.BleStatus.CONNECTING);
        }
        // We want to directly connect to the device, so we are setting the autoConnect parameter to false
        BluetoothGatt bluetoothGatt = device.connectGatt(this, false, mGattCallback);
        if (bluetoothGatt != null) {
            mBluetoothGattMap.put(address, bluetoothGatt);
            L.d(TAG, "Trying to create a new connection.");
            return true;
        }
        return false;
    }

    /**
     * ????????????
     *
     * @param address ????????????
     */
    public void disconnect(final String address) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            L.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mNotifyIndex = 0;
        mBluetoothGattMap.get(address).disconnect();
        mNotifyCharacteristics.clear();
        mWriteCharacteristicMap.remove(address);
        mReadCharacteristicMap.remove(address);
        mOtaWriteCharacteristic = null;
    }

    /**
     * ???????????????????????????????????????????????????
     *
     * @param address ????????????
     */
    public void close(String address) {
        mConnectedAddressList.remove(address);
        if (mBluetoothGattMap.get(address) != null) {
            mBluetoothGattMap.get(address).close();
            mBluetoothGattMap.remove(address);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public boolean setMTU(String address, int mtu){
        L.d(TAG,"setMTU "+mtu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            if(mtu>20){
                if (mBluetoothGattMap.get(address) != null) {
                    boolean result =   mBluetoothGattMap.get(address).requestMtu(mtu);
                    L.d(TAG,"requestMTU "+mtu+" result="+result);
                    return result;
                }
            }
        }
        return false;
    }

    /**
     * ??????????????????????????????
     */
    public void close() {
        if (mConnectedAddressList == null) return;
        for (String address : mConnectedAddressList) {
            if (mBluetoothGattMap.get(address) != null) {
                mBluetoothGattMap.get(address).close();
            }
        }
        mBluetoothGattMap.clear();
        mConnectedAddressList.clear();
    }

    /**
     * ??????????????????
     */
    public boolean refreshDeviceCache(String address) {
        BluetoothGatt gatt = mBluetoothGattMap.get(address);
        if (gatt != null) {
            try {
                Method localMethod = gatt.getClass().getMethod(
                        "refresh", new Class[0]);
                if (localMethod != null) {
                    boolean bool = ((Boolean) localMethod.invoke(
                            gatt, new Object[0])).booleanValue();
                    return bool;
                }
            } catch (Exception localException) {
                L.i(TAG, "An exception occured while refreshing device");
            }
        }
        return false;
    }


    /**
     * ????????????
     *
     * @param address ????????????
     * @param value   ?????????????????????
     * @return ??????????????????(?????????????????????????????????)
     */
    public boolean wirteCharacteristic(String address, byte[] value) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            L.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }
        BluetoothGattCharacteristic gattCharacteristic = mWriteCharacteristicMap.get(address);
        if (gattCharacteristic != null) {
            try {
                if (mOptions.uuid_write_cha.equals(gattCharacteristic.getUuid())) {
                    gattCharacteristic.setValue(value);
                    boolean result = mBluetoothGattMap.get(address).writeCharacteristic(gattCharacteristic);
                    L.d(TAG, address + " -- write data:" + Arrays.toString(value));
                    L.d(TAG, address + " -- write result:" + result);
                    return result;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;

    }

    /**
     * ????????????
     *
     * @param address ????????????
     * @return ??????????????????(?????????????????????????????????)
     */
    public boolean readCharacteristic(String address) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            L.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }
        BluetoothGattCharacteristic gattCharacteristic = mReadCharacteristicMap.get(address);
        if (gattCharacteristic != null) {
            try {
                if (mOptions.uuid_read_cha.equals(gattCharacteristic.getUuid())) {
                    boolean result = mBluetoothGattMap.get(address).readCharacteristic(gattCharacteristic);
                    L.d(TAG, address + " -- read result:" + result);
                    return result;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;

    }

    /**
     * ????????????RSSI
     * @param address ????????????
     * @return ????????????RSSI??????(?????????????????????????????????)
     */
    public boolean readRssi(String address) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            L.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }
        BluetoothGattCharacteristic gattCharacteristic = mReadCharacteristicMap.get(address);
        if (gattCharacteristic != null) {
            try {
                boolean result = mBluetoothGattMap.get(address).readRemoteRssi();
                L.d(TAG, address + " -- read result:" + result);
                return result;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;

    }

    /**
     * ????????????
     * @param address   ????????????
     * @param characteristic ??????????????????
     */
    public void readCharacteristic(String address, BluetoothGattCharacteristic characteristic) {
        L.d(TAG, "readCharacteristic: " + characteristic.getProperties());
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            L.d(TAG, "BluetoothAdapter is null");
            return;
        }
        mBluetoothGattMap.get(address).readCharacteristic(characteristic);
    }

    /**
     * ????????????????????????????????????
     *
     * @param address        ????????????
     * @param characteristic ??????????????????
     * @param enabled   ????????????????????????
     */
    public void setCharacteristicNotification(String address,
                                              BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            L.d(TAG, "BluetoothAdapter is null");
            return;
        }
        mBluetoothGattMap.get(address).setCharacteristicNotification(characteristic, enabled);
        //If the number of descriptors in the eigenvalue of the notification is greater than zero
        if (characteristic.getDescriptors().size() > 0) {
            //Filter descriptors based on the uuid of the descriptor
            List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
            for(BluetoothGattDescriptor descriptor : descriptors){
                if (descriptor != null) {
                    //Write the description value
                    if((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0){
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    }else if((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0){
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    }
                    mBluetoothGattMap.get(address).writeDescriptor(descriptor);
                }
            }
        }

    }

    /**
     * ??????????????????
     * @param address ????????????
     * @param gattServices ??????????????????
     */
    private void displayGattServices(final String address, List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            String uuid = gattService.getUuid().toString();
            L.d(TAG, "displayGattServices: " + uuid);
            if (uuid.equals(mOptions.uuid_service.toString()) || isContainUUID(uuid)) {
                L.d(TAG, "service_uuid: " + uuid);
                List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                    /*int charaProp = gattCharacteristic.getProperties();
                    if ((charaProp & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        Log.e(TAG, "The readable UUID for gattCharacteristic is:" + gattCharacteristic.getUuid());
                        mReadCharacteristicMap.put(address, gattCharacteristic);
                    }
                    if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        Log.e(TAG, "The writable UUID for gattCharacteristic is:" + gattCharacteristic.getUuid());
                        mWriteCharacteristicMap.put(address, gattCharacteristic);
                    }
                    if ((charaProp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        Log.e(TAG, "The PROPERTY_NOTIFY characteristic's UUID:" + gattCharacteristic.getUuid());
                        mNotifyCharacteristics.add(gattCharacteristic);
                    }
                    if((charaProp & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0){
                        Log.e(TAG, "The PROPERTY_INDICATE characteristic's UUID:" + gattCharacteristic.getUuid());
                        mNotifyCharacteristics.add(gattCharacteristic);
                    }*/
                    uuid = gattCharacteristic.getUuid().toString();
                    L.d(TAG, "Characteristic_uuid: " + uuid);
                    if (uuid.equals(mOptions.uuid_write_cha.toString())) {
                        L.e("mWriteCharacteristic", uuid);
                        mWriteCharacteristicMap.put(address, gattCharacteristic);
                        //Notification feature
                    } if (uuid.equals(mOptions.uuid_read_cha.toString())) {
                        L.e("mReadCharacteristic", uuid);
                        mReadCharacteristicMap.put(address, gattCharacteristic);
                        //Notification feature
                    } if ((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        mNotifyCharacteristics.add(gattCharacteristic);
                        L.e("mNotifyCharacteristics", "PROPERTY_NOTIFY");
                    } if((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0){
                        mNotifyCharacteristics.add(gattCharacteristic);
                        L.e("mNotifyCharacteristics", "PROPERTY_INDICATE");
                    }
                }
                //Really set up notifications
                if (mNotifyCharacteristics != null && mNotifyCharacteristics.size() > 0) {
                    L.e("setCharaNotification", "setCharaNotification");
                    setCharacteristicNotification(address, mNotifyCharacteristics.get(mNotifyIndex++), true);
                }
            }
        }
    }

    //???????????????uuid
    private boolean isContainUUID(String uuid) {
        for (UUID u : mOptions.uuid_services_extra){
            if(u != null && uuid.equals(u.toString())){
                return true;
            }
        }
        return false;
    }

    /**
     * ????????????????????????
     * @param address ????????????
     * @return  ??????????????????
     */
    public BluetoothGattCharacteristic getWriteCharacteristic(String address) {
        synchronized (mLocker) {
            if (mWriteCharacteristicMap != null) {
                return mWriteCharacteristicMap.get(address);
            }
            return null;
        }
    }

    /**
     * ????????????????????????
     * @param address ????????????
     * @return  ??????????????????
     */
    public BluetoothGattCharacteristic getReadCharacteristic(String address) {
        synchronized (mLocker) {
            if (mReadCharacteristicMap != null) {
                return mReadCharacteristicMap.get(address);
            }
            return null;
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This
     * should be invoked only after {@code BluetoothGatt#discoverServices()}
     * completes successfully.
     *
     * @param address ble address
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices(String address) {
        if (mBluetoothGattMap.get(address) == null)
            return null;

        return mBluetoothGattMap.get(address).getServices();
    }

    /**
     * .??????????????????????????????RSSI
     *
     * @param address ????????????
     * @return ??????RSSI????????????
     */
    public boolean getRssiVal(String address) {
        if (mBluetoothGattMap.get(address) == null)
            return false;

        return mBluetoothGattMap.get(address).readRemoteRssi();
    }

    /**
     * ??????OTA??????
     *
     * @param address ????????????
     * @param value   ??????????????????
     * @return ??????????????????
     */
    public boolean writeOtaData(String address, byte[] value) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            L.w(TAG, address + " -- BluetoothAdapter not initialized");
            return false;
        }
        try {
            if (mOtaWriteCharacteristic == null) {
                mOtaUpdating = true;
                BluetoothGattService bluetoothGattService = this.mBluetoothGattMap.get(address).getService(mOptions.uuid_ota_service);
                if (bluetoothGattService == null) {
                    return false;
                } else {
                    BluetoothGattCharacteristic mOtaNotifyCharacteristic = bluetoothGattService.getCharacteristic(mOptions.uuid_ota_notify_cha);
                    if (mOtaNotifyCharacteristic != null) {
                        this.mBluetoothGattMap.get(address).setCharacteristicNotification(mOtaNotifyCharacteristic, true);
                    }
                    mOtaWriteCharacteristic = bluetoothGattService.getCharacteristic(mOptions.uuid_ota_write_cha);
                }

            }
            if (mOtaWriteCharacteristic != null && mOptions.uuid_ota_write_cha.equals(mOtaWriteCharacteristic.getUuid())) {
                mOtaWriteCharacteristic.setValue(value);
                boolean result = writeCharacteristic(mBluetoothGattMap.get(address), mOtaWriteCharacteristic);
                L.d(TAG, address + " -- write data:" + Arrays.toString(value));
                L.d(TAG, address + " -- write result:" + result);
                return result;
            }
            return true;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            close();
            return false;
        }
    }

    //The basic method of writing data
    public boolean writeCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        synchronized (mLocker) {
            return !(gatt == null || characteristic == null) && gatt.writeCharacteristic(characteristic);
        }
    }

    /**
     * OTA????????????
     */
    public void otaUpdateComplete() {
        mOtaUpdating = false;
    }

    /**
     * ??????OTA??????????????????
     *
     * @param updating ????????????
     */
    public void setOtaUpdating(boolean updating) {
        this.mOtaUpdating = updating;
    }

    /**
     * ??????OTA??????????????????
     *
     * @param otaListener ????????????
     */
    public void setOtaListener(OtaListener otaListener) {
        this.mOtaListener = otaListener;
    }

}
