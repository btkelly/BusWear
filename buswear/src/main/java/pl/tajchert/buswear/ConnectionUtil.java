package pl.tajchert.buswear;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import pl.tajchert.buswear.wear.connection.WearConnectionEvent;

class ConnectionUtil implements GoogleApiClient.ConnectionCallbacks, CapabilityApi.CapabilityListener {

    private final GoogleApiClient googleApiClient;
    private final String busWearCapability;
    private final Context context;
    private final List<Node> lastSeenNodes;

    ConnectionUtil(@NonNull Context context) {
        this.busWearCapability = context.getString(R.string.wear_capability);
        this.context = context.getApplicationContext();
        this.lastSeenNodes = new ArrayList<Node>();

        notifyNodeUpdates(null);

        googleApiClient = new GoogleApiClient.Builder(context.getApplicationContext())
                .addConnectionCallbacks(this)
                .addApi(Wearable.API)
                .build();
        googleApiClient.connect();
    }

    public void disconnect() {
        Wearable.CapabilityApi.removeCapabilityListener(googleApiClient, this, busWearCapability);
        googleApiClient.disconnect();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Wearable.CapabilityApi.getCapability(googleApiClient, busWearCapability, CapabilityApi.FILTER_REACHABLE)
                .setResultCallback(new ResultCallback<CapabilityApi.GetCapabilityResult>() {
                    @Override
                    public void onResult(CapabilityApi.GetCapabilityResult getCapabilityResult) {
                        onCapabilityChanged(getCapabilityResult.getCapability());
                    }
                });

        Wearable.CapabilityApi.addCapabilityListener(googleApiClient, this, busWearCapability);
    }

    @Override
    public void onConnectionSuspended(int i) {
        notifyNodeUpdates(null);
    }

    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        if (busWearCapability.equals(capabilityInfo.getName())) {
            notifyNodeUpdates(capabilityInfo.getNodes());
        }
    }

    private synchronized void notifyNodeUpdates(Set<Node> connectedNodes) {
        List<Node> nodeList = new ArrayList<>();

        if (connectedNodes != null) {
            for (Node node : connectedNodes) {
                nodeList.add(node);
            }
        }

        if (nodesChanged(nodeList, lastSeenNodes)) {
            EventBus.getDefault(context).postStickyLocal(new WearConnectionEvent(nodeList));
        }

        lastSeenNodes.clear();
        lastSeenNodes.addAll(nodeList);
    }

    private boolean nodesChanged(List<Node> nodeList, List<Node> lastSeenNodes) {

        if (nodeList.size() != lastSeenNodes.size()) {
            return true;
        } else {
            NEW_NODES: for (Node newNode : nodeList) {
                for (Node oldNode : lastSeenNodes) {
                    if (newNode.getId().equals(oldNode.getId())) {
                        continue NEW_NODES;
                    }
                }

                return true;
            }
            return false;
        }
    }
}
