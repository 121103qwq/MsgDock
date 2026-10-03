package com.xgy.lansms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.UUID;

public class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                if (msgs == null || msgs.length == 0) return;
                String from = msgs[0].getDisplayOriginatingAddress();
                StringBuilder text = new StringBuilder();
                for (SmsMessage m : msgs) text.append(m.getDisplayMessageBody());
                int sim = intent.getIntExtra("subscription", intent.getIntExtra("slot", -1));
                Forwarder.forward(context.getApplicationContext(), UUID.randomUUID().toString(), from,
                        text.toString(), System.currentTimeMillis(), sim);
            } finally {
                pending.finish();
            }
        }, "sms-forward").start();
    }
}
