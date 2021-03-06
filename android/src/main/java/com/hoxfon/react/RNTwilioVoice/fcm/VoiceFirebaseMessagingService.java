package com.hoxfon.react.RNTwilioVoice.fcm;

import android.annotation.TargetApi;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.hoxfon.react.RNTwilioVoice.BuildConfig;
import com.hoxfon.react.RNTwilioVoice.CallNotificationManager;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;
import com.twilio.voice.Voice;

import java.util.Map;
import java.util.Random;

import org.json.JSONObject;
import org.json.JSONException;

import static com.hoxfon.react.RNTwilioVoice.TwilioVoiceModule.TAG;
import static com.hoxfon.react.RNTwilioVoice.TwilioVoiceModule.ACTION_FCM_TOKEN;
import static com.hoxfon.react.RNTwilioVoice.TwilioVoiceModule.ACTION_INCOMING_CALL;
import static com.hoxfon.react.RNTwilioVoice.TwilioVoiceModule.ACTION_CANCEL_CALL;
import static com.hoxfon.react.RNTwilioVoice.TwilioVoiceModule.INCOMING_CALL_INVITE;
import static com.hoxfon.react.RNTwilioVoice.TwilioVoiceModule.CANCELLED_CALL_INVITE;
import static com.hoxfon.react.RNTwilioVoice.TwilioVoiceModule.INCOMING_CALL_NOTIFICATION_ID;
import com.hoxfon.react.RNTwilioVoice.SoundPoolManager;

public class VoiceFirebaseMessagingService extends FirebaseMessagingService {

    private CallNotificationManager callNotificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        callNotificationManager = new CallNotificationManager();
    }

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "Refreshed token: " + token);

        // Notify Activity of FCM token
        Intent intent = new Intent(ACTION_FCM_TOKEN);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        String titletemp = "";
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Received onMessageReceived()");
            Log.d(TAG, "Bundle data: " + remoteMessage.getData());

            Log.d(TAG, "From: " + remoteMessage.getFrom());
        }

        try {
            JSONObject jsonObj = new JSONObject(remoteMessage.getData());
            
            String up = jsonObj.getString("twi_params");
            String[] strings = up.split("=");
            titletemp = strings[1];
        } catch(JSONException e) {
            
        }
        Log.d(TAG, "Bundle data JSON: " + titletemp);

        final String title = titletemp;
        // Check if message contains a data payload.
        if (remoteMessage.getData().size() > 0) {
            Map<String, String> data = remoteMessage.getData();

            // If notification ID is not provided by the user for push notification, generate one at random
            Random randomNumberGenerator = new Random(System.currentTimeMillis());
            final int notificationId = randomNumberGenerator.nextInt();

            boolean valid = Voice.handleMessage(getApplicationContext(), data, new MessageListener() {
                @Override
                public void onCallInvite(final CallInvite callInvite) {

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
                                int appImportance = callNotificationManager.getApplicationImportance((ReactApplicationContext)context);
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "CONTEXT present appImportance = " + appImportance);
                                }
                                Intent launchIntent = callNotificationManager.getLaunchIntent(
                                        (ReactApplicationContext)context,
                                        notificationId,
                                        callInvite,
                                        false,
                                        appImportance
                                );
                                // app is not in foreground
                                if (appImportance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                                    context.startActivity(launchIntent);
                                }
                                VoiceFirebaseMessagingService.this.handleIncomingCall((ReactApplicationContext)context, notificationId, callInvite, launchIntent, title);
                            } else {
                                // Otherwise wait for construction, then handle the incoming call
                                mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                                    public void onReactContextInitialized(ReactContext context) {
                                        int appImportance = callNotificationManager.getApplicationImportance((ReactApplicationContext)context);
                                        if (BuildConfig.DEBUG) {
                                            Log.d(TAG, "CONTEXT not present appImportance = " + appImportance);
                                        }
                                        Intent launchIntent = callNotificationManager.getLaunchIntent((ReactApplicationContext)context, notificationId, callInvite, true, appImportance);
                                        context.startActivity(launchIntent);
                                        VoiceFirebaseMessagingService.this.handleIncomingCall((ReactApplicationContext)context, notificationId, callInvite, launchIntent, title);
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

                @Override
                public void onCancelledCallInvite(final CancelledCallInvite cancelledCallInvite, final CallException callException) {
                     Handler handler = new Handler(Looper.getMainLooper());
                     handler.post(new Runnable() {
                         public void run() {
                             ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                             ReactContext context = mReactInstanceManager.getCurrentReactContext();
                             VoiceFirebaseMessagingService.this.cancelNotification((ReactApplicationContext)context, cancelledCallInvite);
                             VoiceFirebaseMessagingService.this.sendCancelledCallInviteToActivity(
                                 cancelledCallInvite, title);
                         }
                     });
                }
            });

            if (!valid) {
                Log.e(TAG, "Error handling FCM message");
            }
        }

        // Check if message contains a notification payload.
        if (remoteMessage.getNotification() != null) {
            Log.e(TAG, "Message Notification Body: " + remoteMessage.getNotification().getBody());
        }
    }

    private void handleIncomingCall(ReactApplicationContext context,
                                    int notificationId,
                                    CallInvite callInvite,
                                    Intent launchIntent,
                                    String title
    ) {
        sendIncomingCallMessageToActivity(context, callInvite, notificationId);
        showNotification(context, callInvite, notificationId, launchIntent, title);
    }

    /*
     * Send the IncomingCallMessage to the TwilioVoiceModule
     */
    private void sendIncomingCallMessageToActivity(
            ReactApplicationContext context,
            CallInvite callInvite,
            int notificationId
    ) {
        Intent intent = new Intent(ACTION_INCOMING_CALL);
        intent.putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(INCOMING_CALL_INVITE, callInvite);
        //startService(intent);

         LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    /*
    * Send the CancelledCallInvite to the VoiceActivity
    */
    private void sendCancelledCallInviteToActivity(CancelledCallInvite cancelledCallInvite, String title) {
        Intent intent = new Intent(ACTION_CANCEL_CALL);
        intent.putExtra(CANCELLED_CALL_INVITE, cancelledCallInvite);
        intent.putExtra("TITLE", title);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /*
     * Show the notification in the Android notification drawer
     */
    @TargetApi(20)
    private void showNotification(ReactApplicationContext context,
                                  CallInvite callInvite,
                                  int notificationId,
                                  Intent launchIntent,
                                  String title
    ) {
        callNotificationManager.createIncomingCallNotification(context, callInvite, notificationId, launchIntent, title);
    }

    private void cancelNotification(ReactApplicationContext context, CancelledCallInvite cancelledCallInvite) {
        SoundPoolManager.getInstance((this)).stopRinging();
        callNotificationManager.removeIncomingCallNotification(context, cancelledCallInvite, 0);
    }

}
