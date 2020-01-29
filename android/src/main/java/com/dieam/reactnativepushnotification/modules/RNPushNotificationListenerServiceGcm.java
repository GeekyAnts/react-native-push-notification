package com.dieam.reactnativepushnotification.modules;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.google.android.gms.gcm.GcmListenerService; 
import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.pubnub.api.*;

import org.json.JSONObject;

import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Random;
import java.util.Map;

import static com.dieam.reactnativepushnotification.modules.RNPushNotification.LOG_TAG;
import static com.dieam.reactnativepushnotification.modules.RNPushNotification.PUBNUB_SHARED_KEY;
import static com.dieam.reactnativepushnotification.modules.RNPushNotification.MIXPANEL_KEY;
import static com.dieam.reactnativepushnotification.modules.RNPushNotification.myContext;

import android.content.SharedPreferences;

public class RNPushNotificationListenerServiceGcm extends GcmListenerService {
    public RNPushNotificationListenerServiceGcm(){ 
        try{ 
            SharedPreferences pref = myContext.getSharedPreferences("MyPref", 0); // 0 - for private mode
            SharedPreferences.Editor editor = pref.edit();
            editor.putString("SYMMETRIC_KEY", PUBNUB_SHARED_KEY); // Storing string
            editor.putString("MIXPANEL_KEY", MIXPANEL_KEY); // Storing string
            editor.commit(); 
        }catch(Exception e){
            Log.v(LOG_TAG, "SharedPreferences EXCEPTION::::" + e);
        }
    }
    
    @Override
    public void onMessageReceived(String from, final Bundle bundle) { 
        Log.v(LOG_TAG, "onMessageReceived CALLED:::: " + bundle);
        JSONObject data = getPushData(bundle.getString("data"));
        SharedPreferences pref = getApplicationContext().getSharedPreferences("MyPref", 0);
        String SYMMETRIC_KEY_LOCAL = pref.getString("SYMMETRIC_KEY", null);
        String MIXPANEL_KEY_LOCAL = pref.getString("MIXPANEL_KEY", null);
        // Copy `twi_body` to `message` to support Twilio
        PNConfiguration pnConfiguration = new PNConfiguration();
        pnConfiguration.setSubscribeKey("demo");
        pnConfiguration.setPublishKey("demo");
        PubNub pubnub = new PubNub(pnConfiguration);

       

        if (bundle.containsKey("twi_body")) {
            bundle.putString("message", bundle.getString("twi_body"));
        }
        
        if (data != null) {
            if (!bundle.containsKey("message")) {
                bundle.putString("message", data.optString("alert", null));
            }
            if (!bundle.containsKey("title")) {
                bundle.putString("title", data.optString("title", null));
            }
            if (!bundle.containsKey("sound")) {
                bundle.putString("soundName", data.optString("sound", null));
            }
            if (!bundle.containsKey("color")) {
                bundle.putString("color", data.optString("color", null));
            }

            final int badge = data.optInt("badge", -1);
            if (badge >= 0) {
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(this, badge);
            }
        }
        
        String notificationType;
        Boolean isEncrypted =  Boolean.parseBoolean(bundle.getString("encrypted", "false")); 
        if(isEncrypted == true){
            try {
                String decrypedMessage = pubnub.decrypt(bundle.getString("message"),SYMMETRIC_KEY_LOCAL);
                bundle.putString("message", decrypedMessage.substring(1, decrypedMessage.length()-1));
            }catch(Exception e){
                bundle.putString("message", "You have a new message");
                Log.v(LOG_TAG, "EXCEPTION::::" + e);
            }
            try {
                String decrypedMessage = pubnub.decrypt(bundle.getString("title"),SYMMETRIC_KEY_LOCAL);
                bundle.putString("title", decrypedMessage.substring(1, decrypedMessage.length()-1));
            }catch(Exception e){
                bundle.putString("title", "New Message");
                Log.v(LOG_TAG, "EXCEPTION::::" + e);
            }

            try {
                notificationType = pubnub.decrypt(bundle.getString("notificationType"),SYMMETRIC_KEY_LOCAL);
            }catch(Exception e){
                notificationType = "UNKNOWN";
                Log.v(LOG_TAG, "EXCEPTION::::" + e);
            }
        }else{
            notificationType = bundle.getString("notificationType");
        }

        MixpanelAPI instance;
        JSONObject properties = new JSONObject();
        
        try{
            String key = "notificationType";
            properties.put(key, notificationType.substring(1, notificationType.length()-1));
        }catch(Exception e){
            Log.v(LOG_TAG, "JSON PROPERTIES EXCEPTION ::::" + e);
        }
        
        if(MIXPANEL_KEY_LOCAL != null){
            instance = MixpanelAPI.getInstance(getApplicationContext(),MIXPANEL_KEY_LOCAL);
            try{
                if(instance != null){
                    synchronized(instance) {
                        Log.v(LOG_TAG, "MIXPANEL TRACK:::: ");
                        instance.track("Notification Recieved on android native side",properties);
                        instance.flush();
                    }
                }
            }catch(Exception e){
                Log.v(LOG_TAG, "AFTER PUBNUB INIT unmodifiableMap  EXCEPTION:::: " + e );    
            }
            
        }

        Log.v(LOG_TAG, "onMessageReceived :::: " + bundle);

        // We need to run this on the main thread, as the React code assumes that is true.
        // Namely, DevServerHelper constructs a Handler() without a Looper, which triggers:
        // "Can't create handler inside thread that has not called Looper.prepare()"
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                // Construct and load our normal React JS code bundle
                ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                ReactContext context = mReactInstanceManager.getCurrentReactContext();
                // If it's constructed, send a notification
                if (context != null) {
                    handleRemotePushNotification((ReactApplicationContext) context, bundle);
                } else {
                    // Otherwise wait for construction, then send the notification
                    mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                        public void onReactContextInitialized(ReactContext context) {
                            handleRemotePushNotification((ReactApplicationContext) context, bundle);
                        }
                    });
                    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                        // Construct it in the background
                        mReactInstanceManager.createReactContextInBackground();
                    }
                }
            }
        });
    }

    private JSONObject getPushData(String dataString) {
        try {
            return new JSONObject(dataString);
        } catch (Exception e) {
            return null;
        }
    }

    private void handleRemotePushNotification(ReactApplicationContext context, Bundle bundle) {
       
        // If notification ID is not provided by the user for push notification, generate one at random
        if (bundle.getString("id") == null) {
            Random randomNumberGenerator = new Random(System.currentTimeMillis());
            bundle.putString("id", String.valueOf(randomNumberGenerator.nextInt()));
        }

        Boolean isForeground = isApplicationInForeground();

        RNPushNotificationJsDelivery jsDelivery = new RNPushNotificationJsDelivery(context);
        bundle.putBoolean("foreground", isForeground);
        bundle.putBoolean("userInteraction", false);
        jsDelivery.notifyNotification(bundle);

        // If contentAvailable is set to true, then send out a remote fetch event
        if (bundle.getString("contentAvailable", "false").equalsIgnoreCase("true")) {
            jsDelivery.notifyRemoteFetch(bundle);
        }

        if (!isForeground) {
            Application applicationContext = (Application) context.getApplicationContext();
            RNPushNotificationHelper pushNotificationHelper = new RNPushNotificationHelper(applicationContext);
            pushNotificationHelper.sendToNotificationCentre(bundle);
        }
    }

    private boolean isApplicationInForeground() {
        ActivityManager activityManager = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
        if (processInfos != null) {
            for (RunningAppProcessInfo processInfo : processInfos) {
                if (processInfo.processName.equals(getApplication().getPackageName())) {
                    if (processInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        for (String d : processInfo.pkgList) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
