package com.rideshare.common.cluster;

import org.json.JSONObject;

public interface ClusterListener {
    /**
     * Called when a replicated write command is received from the Leader.
     */
    void applyWrite(String sql);

    /**
     * Called when this node needs to generate a full state snapshot for a new follower.
     */
    JSONObject createSnapshot();

    /**
     * Called when this node receives a full state snapshot to apply.
     */
    void applySnapshot(JSONObject snapshot);
    
    /**
     * Called when this node needs to forward a write request to the leader.
     */
    void forwardToLeader(JSONObject request);
}
