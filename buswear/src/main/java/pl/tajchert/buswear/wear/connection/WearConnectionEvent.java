package pl.tajchert.buswear.wear.connection;

import com.google.android.gms.wearable.Node;

import java.util.List;

/**
 * An event that is sent to the local event bus when a node connects or disconnects that
 * has BusWear running and can accept events from this application.
 */
public class WearConnectionEvent {

    private final List<Node> connectedNodes;

    public WearConnectionEvent(List<Node> connectedNodes) {
        this.connectedNodes = connectedNodes;
    }

    public boolean hasBusWearConnection() {
        return connectedNodes.size() > 0;
    }

    /**
     * The list of currently connected Nodes that can utilize BusWear
     * @return
     */
    public List<Node> getConnectedBusWearNodes() {
        return connectedNodes;
    }

}
