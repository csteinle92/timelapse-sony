package com.thibaudperso.sonycamera.timelapse.control.connection;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.thibaudperso.sonycamera.BuildConfig;
import com.thibaudperso.sonycamera.sdk.CameraAPI;
import com.thibaudperso.sonycamera.sdk.model.Device;
import com.thibaudperso.sonycamera.timelapse.TimelapseApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.thibaudperso.sonycamera.timelapse.Constants.LOG_TAG;
import static com.thibaudperso.sonycamera.timelapse.control.connection.StateMachineConnection.State.BAD_API_ACCESS;
import static com.thibaudperso.sonycamera.timelapse.control.connection.StateMachineConnection.State.CHECK_API;
import static com.thibaudperso.sonycamera.timelapse.control.connection.StateMachineConnection.State.GOOD_API_ACCESS;
import static com.thibaudperso.sonycamera.timelapse.control.connection.StateMachineConnection.State.INIT;
import static com.thibaudperso.sonycamera.timelapse.control.connection.StateMachineConnection.State.TRY_TO_CONNECT_TO_SSID;
import static com.thibaudperso.sonycamera.timelapse.control.connection.StateMachineConnection.State.WIFI_DISABLED;
import static com.thibaudperso.sonycamera.timelapse.control.connection.StateMachineConnection.State.WIFI_SCAN;


public class StateMachineConnection {

    private TimelapseApplication mApplication;
    private WifiHandler mWifiHandler;
    private CameraAPI mCameraAPI;

    private State mCurrentState;
    private StateRegistry mStateRegistry;
    private WifiManager.WifiLock mWifiLock;


    private class StateRegistry {
        WifiInfo wifiInfo;
        List<WifiConfiguration> scanResults;
        int forceConnectionToNetworkId = -1;
        int apiAttempts = 0;
    }


    public StateMachineConnection(TimelapseApplication application) {
        mApplication = application;
        mWifiHandler = application.getWifiHandler();
        mCameraAPI = application.getCameraAPI();
        mStateRegistry = new StateRegistry();
        mCurrentState = INIT;
    }


    public void start() {

        Log.d(LOG_TAG, " ----------- StateMachineConnection START -----------");
        mCurrentState.process(this);

        mWifiHandler.setListener(mWifiListener);
        mCameraAPI.addDeviceChangedListener(mDeviceChangedListener);

        WifiManager wifiManager = (WifiManager) mApplication.getSystemService(Context.WIFI_SERVICE);
        if(Build.VERSION.SDK_INT > 12) {
            mWifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WifiLock2");
            mWifiLock.acquire();
        }
    }

    public void stop() {

        Log.d(LOG_TAG, " ----------- StateMachineConnection STOP -----------");
        mCurrentState.stopAsyncTasks();

        mWifiHandler.setListener(null);
        mCameraAPI.removeDeviceChangedListener(mDeviceChangedListener);

        if (mWifiLock != null && mWifiLock.isHeld())
            mWifiLock.release();

    }


    public void reset() {
        setCurrentState(INIT);
    }

    interface StateInterface {

        void process(StateMachineConnection sm);

        State[] previousPossibleStates();

        void stopAsyncTasks();
    }


    public enum State implements StateInterface {

        INIT {
            @Override
            public void process(StateMachineConnection sm) {
                boolean isWifiEnabled = sm.mWifiHandler.isEnabled();
                if (isWifiEnabled) {
                    sm.setCurrentState(State.WIFI_ENABLED);
                } else {
                    sm.setCurrentState(State.WIFI_DISABLED);
                }
            }

            @Override
            public State[] previousPossibleStates() {
                return State.values();
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        WIFI_ENABLED {
            @Override
            public void process(StateMachineConnection sm) {
                WifiInfo connectedWifi = sm.mWifiHandler.getConnectedWifi();
                if (connectedWifi != null) {
                    sm.mStateRegistry.wifiInfo = connectedWifi;
                    sm.setCurrentState(State.WIFI_CONNECTED);
                } else {
                    sm.setCurrentState(State.WIFI_DISCONNECTED);
                }
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_DISABLED, INIT};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        WIFI_DISABLED {
            @Override
            public void process(StateMachineConnection sm) {
                //TODO: notify wifi disabled
            }

            @Override
            public State[] previousPossibleStates() {
                return State.values();
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        WIFI_CONNECTED {
            @Override
            public void process(StateMachineConnection sm) {

                if (sm.mStateRegistry.forceConnectionToNetworkId
                        == sm.mStateRegistry.wifiInfo.getNetworkId()) {
                    sm.mStateRegistry.forceConnectionToNetworkId = -1;
                }

                String ssid = WifiHandler.parseSSID(sm.mStateRegistry.wifiInfo.getSSID());
                if (WifiHandler.isSonyCameraSSID(ssid)) {
                    sm.setCurrentState(State.SONY_WIFI);
                } else {
                    sm.setCurrentState(State.NOT_SONY_WIFI);
                }
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_ENABLED, WIFI_DISCONNECTED, SONY_WIFI, NOT_SONY_WIFI,
                        WIFI_SCAN, GOOD_API_ACCESS, BAD_API_ACCESS, TRY_TO_CONNECT_TO_SSID,
                        CHECK_API};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        WIFI_DISCONNECTED {
            @Override
            public void process(StateMachineConnection sm) {
                sm.mStateRegistry.wifiInfo = null;
                sm.setCurrentState(State.WIFI_SCAN);
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_ENABLED, WIFI_CONNECTED, SONY_WIFI, NOT_SONY_WIFI,
                        WIFI_SCAN, GOOD_API_ACCESS, BAD_API_ACCESS, WIFI_SCAN_FINISHED, CHECK_API};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        SONY_WIFI {
            @Override
            public void process(final StateMachineConnection sm) {

                // Workaround when there is a data connection more than the wifi one
                // http://stackoverflow.com/questions/33237074/request-over-wifi-on-android-m
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    ConnectivityManager connectivityManager = (ConnectivityManager)
                            sm.mApplication.getSystemService(Context.CONNECTIVITY_SERVICE);
                    for (Network net : connectivityManager.getAllNetworks()) {
                        NetworkInfo netInfo = connectivityManager.getNetworkInfo(net);
                        if (netInfo.getType() == ConnectivityManager.TYPE_WIFI
                                && netInfo.getExtraInfo() != null
                                && netInfo.getExtraInfo().equals(sm.mStateRegistry.wifiInfo.getSSID())) {
                            connectivityManager.bindProcessToNetwork(net);
                            break;
                        }
                    }
                }

                sm.mStateRegistry.apiAttempts = 1;
                sm.setCurrentState(State.CHECK_API);
            }

            @Override
            public void stopAsyncTasks() {
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_CONNECTED};
            }
        },

        CHECK_API {

            boolean mIgnoreNextAsyncResponse = true;

            @Override
            public void process(final StateMachineConnection sm) {

                mIgnoreNextAsyncResponse = false;
                sm.mCameraAPI.testConnection(new CameraAPI.TestConnectionListener() {
                    @Override
                    public void isConnected(boolean isConnected) {

                        if (mIgnoreNextAsyncResponse) return;

                        if (isConnected) {
                            sm.setCurrentState(State.GOOD_API_ACCESS);
                        } else {
                            sm.setCurrentState(BAD_API_ACCESS);
                        }
                    }
                });
            }

            @Override
            public void stopAsyncTasks() {
                mIgnoreNextAsyncResponse = true;
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{SONY_WIFI, CHECK_API, BAD_API_ACCESS, GOOD_API_ACCESS};
            }
        },

        NOT_SONY_WIFI {
            @Override
            public void process(StateMachineConnection sm) {
                sm.setCurrentState(State.WIFI_SCAN);
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_CONNECTED};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        WIFI_SCAN {
            @Override
            public void process(StateMachineConnection sm) {
                if (ContextCompat.checkSelfPermission(sm.mApplication, Manifest.permission
                        .ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    sm.mWifiHandler.startScan();
                } else {
                    sm.setCurrentState(NO_WIFI_SCAN_PERMISSION);
                }
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_SCAN_FINISHED, WIFI_DISCONNECTED, NOT_SONY_WIFI};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        NO_WIFI_SCAN_PERMISSION {
            @Override
            public void process(StateMachineConnection sm) {

            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_SCAN};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        WIFI_SCAN_FINISHED {
            @Override
            public void process(StateMachineConnection sm) {
                List<WifiConfiguration> scans = sm.mStateRegistry.scanResults;
                if (scans.size() == 1) {
                    sm.mStateRegistry.forceConnectionToNetworkId = scans.get(0).networkId;
                    sm.setCurrentState(TRY_TO_CONNECT_TO_SSID);
                } else if (scans.size() > 1) {
                    sm.setCurrentState(MULTIPLE_SONY_SCAN_DETECTED);
                } else {
                    sm.setCurrentState(WIFI_SCAN);
                }
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_SCAN};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },


        MULTIPLE_SONY_SCAN_DETECTED {
            @Override
            public void process(StateMachineConnection sm) {

            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{WIFI_SCAN_FINISHED};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        TRY_TO_CONNECT_TO_SSID {
            @Override
            public void process(StateMachineConnection sm) {
                sm.mWifiHandler.connectToNetworkId(sm.mStateRegistry.forceConnectionToNetworkId);
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{MULTIPLE_SONY_SCAN_DETECTED, WIFI_SCAN_FINISHED};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        GOOD_API_ACCESS {
            @Override
            public void process(StateMachineConnection sm) {
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{CHECK_API};
            }

            @Override
            public void stopAsyncTasks() {
            }
        },

        BAD_API_ACCESS {

            boolean mIgnoreNextAsyncResponse = true;

            @Override
            public void process(final StateMachineConnection sm) {

                mIgnoreNextAsyncResponse = false;
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mIgnoreNextAsyncResponse) return;
                        sm.mStateRegistry.apiAttempts++;
                        sm.setCurrentState(CHECK_API);
                    }
                }, 1000);
            }

            @Override
            public State[] previousPossibleStates() {
                return new State[]{CHECK_API};
            }

            @Override
            public void stopAsyncTasks() {
                mIgnoreNextAsyncResponse = true;
            }
        }

    }


    private final WifiHandler.Listener mWifiListener = new WifiHandler.Listener() {
        @Override
        public void wifiEnabled() {
            setCurrentState(State.WIFI_ENABLED);
        }

        @Override
        public void wifiDisabled() {
            setCurrentState(State.WIFI_DISABLED);
        }

        @Override
        public void wifiConnected(NetworkInfo networkInfo) {
            mStateRegistry.wifiInfo = mWifiHandler.getConnectedWifi();
            setCurrentState(State.WIFI_CONNECTED);
        }

        @Override
        public void wifiDisconnected(NetworkInfo networkInfo) {
            if (mCurrentState == TRY_TO_CONNECT_TO_SSID || mCurrentState == WIFI_DISABLED) return;
            setCurrentState(State.WIFI_DISCONNECTED);
        }

        @Override
        public void onWifiScanFinished(List<WifiConfiguration> configurations) {

            if (mCurrentState != WIFI_SCAN) return;

            mStateRegistry.scanResults = configurations;
            setCurrentState(State.WIFI_SCAN_FINISHED);
        }
    };


    private final CameraAPI.DeviceChangedListener
            mDeviceChangedListener = new CameraAPI.DeviceChangedListener() {
        @Override
        public void onNewDevice(Device device) {
            if (mCurrentState == BAD_API_ACCESS || mCurrentState == GOOD_API_ACCESS ||
                    mCurrentState == CHECK_API) {
                setCurrentState(CHECK_API);
            }
        }
    };


    private void setCurrentState(State newState) {

        if (BuildConfig.DEBUG && !Arrays.asList(newState.previousPossibleStates()).contains(mCurrentState)) {
            throw new RuntimeException("Current State: " + mCurrentState + ", new State: " + newState);
        }

        Log.d(LOG_TAG, "State: " + mCurrentState + " ---> " + newState);
        for (Listener listener : mListeners) listener.onNewState(mCurrentState, newState);

        mCurrentState.stopAsyncTasks();
        mCurrentState = newState;
        mCurrentState.process(this);

    }


    public State getCurrentState() {
        return mCurrentState;
    }


    public void notifyWifiScanPermissionAccepted() {
        setCurrentState(INIT);
    }

    public void tryToConnectToNetworkId(int networkId) {
        mStateRegistry.forceConnectionToNetworkId = networkId;
        setCurrentState(TRY_TO_CONNECT_TO_SSID);
    }

    public List<WifiConfiguration> getWifiConfigurations() {
        return mStateRegistry.scanResults;
    }


    private List<Listener> mListeners = new ArrayList<>();

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public interface Listener {
        void onNewState(State previousState, State newState);
    }

}
