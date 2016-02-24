package pl.tajchert.buswear.wear;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.greenrobot.eventbus.WearEventBus;

public class EventCatcher extends WearableListenerService {

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        WearEventBus.syncEvent(getApplicationContext(), messageEvent);
        super.onMessageReceived(messageEvent);
    }

}
