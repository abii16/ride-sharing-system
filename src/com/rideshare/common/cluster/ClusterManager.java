package com.rideshare.common.cluster;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.NetworkInterface;
import java.util.List;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ClusterManager {
    private final int myId;
    private final int myPort;
    private final List<PeerInfo> peers;
    private final ClusterListener listener;
    
    private ClusterState state = ClusterState.FOLLOWER;
    private int leaderId = -1;
    private boolean isRunning = true;
    
    private final ConcurrentHashMap<Integer, PeerConnection> activePeers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> peerHostById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AckTracker> pendingReplicationAcks = new ConcurrentHashMap<>();
    private long lastHeartbeatTime = 0;
    
    // Config
    private static final int HEARTBEAT_INTERVAL = 1000;
    private static final int ELECTION_TIMEOUT = 3000;
    
    // Debug flag
    private static final boolean DEBUG = true;

    public enum ClusterState {
        LEADER, FOLLOWER, ELECTION, RECOVERING
    }

    public static class PeerInfo {
        int id;
        String host;
        int port;
        public PeerInfo(int id, String host, int port) { this.id = id; this.host = host; this.port = port; }
        public JSONObject toJson() { return new JSONObject().put("id", id).put("host", host).put("port", port); }
    }

    public ClusterManager(int myId, int myPort, List<PeerInfo> peers, ClusterListener listener) {
        this.myId = myId;
        this.myPort = myPort;
        this.peers = peers;
        this.listener = listener;
        
        // Add myself to peers list if not present (for sharing/discovery).
        // IMPORTANT: Never advertise "localhost" across a multi-PC cluster.
        boolean alreadyPresent = false;
        for (PeerInfo p : peers) {
            if (p.id == myId) {
                alreadyPresent = true;
                break;
            }
        }

        if (!alreadyPresent) {
            String advertisedHost = System.getProperty("cluster.host");
            if (advertisedHost == null || advertisedHost.trim().isEmpty()) {
                advertisedHost = detectLocalIpv4();
            }
            peers.add(new PeerInfo(myId, advertisedHost, myPort));
        }
    }

    private static String detectLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                        continue;
                    }
                } catch (Exception ignored) {
                    continue;
                }

                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public void start() {
        System.out.println("[Cluster-" + myId + "] Starting ClusterManager on port " + myPort);
        
        // Start Server Thread
        new Thread(this::runServer).start();
        
        // Start Connected Manager (connects to peers)
        new Thread(this::manageConnections).start();
        
        // Start Heartbeat/Election Timer
        new Thread(this::runHeartbeat).start();
        
        // Initial recovery
        // Treat "only myself" as no configured peers.
        int configuredPeers = 0;
        for (PeerInfo p : peers) {
            if (p.id != myId) configuredPeers++;
        }

        if (configuredPeers == 0) {
            if (DEBUG) System.out.println("[Cluster-" + myId + "] No peers configured. Starting as single-node Leader.");
            becomeLeader();
        } else {
            startRecovery();
        }
    }
    
    private void startRecovery() {
        this.state = ClusterState.RECOVERING;
        // Wait for connection to cluster, then ask for state
    }

    public boolean isLeader() {
        return state == ClusterState.LEADER;
    }
    
    public int getLeaderId() {
        return leaderId;
    }

    public String getPeerHost(int peerId) {
        String host = peerHostById.get(peerId);
        if (host != null && !host.isBlank()) {
            return host;
        }
        // Fallback to configured peers list (gossiped host)
        for (PeerInfo p : peers) {
            if (p.id == peerId && p.host != null && !p.host.isBlank()) {
                return p.host;
            }
        }
        return null;
    }

    public void broadcastWrite(String sql) {
        JSONObject msg = new JSONObject();
        msg.put("type", "REPLICATE_SQL");
        msg.put("sql", sql);
        broadcast(msg);
    }

    /**
     * Broadcast a replicated write and wait until a majority of nodes have ACKed.
     * Majority is computed from the currently connected peers.
     */
    public boolean broadcastWriteWithQuorumAck(String sql, long timeoutMs) {
        int totalNodes = activePeers.size() + 1; // +1 = self
        int majority = (totalNodes / 2) + 1;
        int requiredFollowerAcks = Math.max(0, majority - 1);

        // Single node case: immediately satisfied.
        if (requiredFollowerAcks == 0) {
            broadcastWrite(sql);
            return true;
        }

        String replicationId = UUID.randomUUID().toString();
        AckTracker tracker = new AckTracker(requiredFollowerAcks);
        pendingReplicationAcks.put(replicationId, tracker);

        JSONObject msg = new JSONObject();
        msg.put("type", "REPLICATE_SQL");
        msg.put("sql", sql);
        msg.put("rid", replicationId);
        msg.put("ack", true);
        broadcast(msg);

        try {
            return tracker.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            pendingReplicationAcks.remove(replicationId);
        }
    }

    private static final class AckTracker {
        final CountDownLatch latch;
        final AtomicInteger remaining;

        AckTracker(int requiredAcks) {
            this.latch = new CountDownLatch(requiredAcks);
            this.remaining = new AtomicInteger(requiredAcks);
        }

        void ack() {
            int before = remaining.getAndDecrement();
            if (before > 0) {
                latch.countDown();
            }
        }
    }
    
    public void forwardToLeader(JSONObject request) {
        PeerConnection leaderConn = activePeers.get(leaderId);
        if (leaderConn != null && leaderConn.isConnected()) {
             JSONObject msg = new JSONObject();
             msg.put("type", "FORWARD_REQUEST");
             msg.put("payload", request);
             leaderConn.send(msg);
        } else {
            System.err.println("[Cluster-" + myId + "] Cannot forward to leader: Leader not connected.");
        }
    }

    private void runServer() {
        try (ServerSocket serverSocket = new ServerSocket(myPort)) {
            while (isRunning) {
                Socket socket = serverSocket.accept();
                handleNewConnection(socket, false);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleNewConnection(Socket socket, boolean isOutbound) {
        try {
            PeerConnection pc = new PeerConnection(socket, isOutbound);
            // Handshake
            pc.send(new JSONObject().put("type", "HELLO").put("senderId", myId));
            new Thread(pc).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void manageConnections() {
        while (isRunning) {
            for (PeerInfo p : peers) {
                 if (p.id == myId) continue;
                 if (!activePeers.containsKey(p.id)) {
                     try {
                         // Only connect to peers with higher ID or if we are unconnected? 
                         // To avoid duplicate connections, standard is: ID A connects to ID B if A > B (or vice versa).
                         // Here we'll just try to connect and deduplicate in handshake.
                         // Let's use: Smaller ID connects to Larger ID to prevent crossed wires? 
                         // No, just try all.
                         
                         if (DEBUG) System.out.println("[Cluster-" + myId + "] Trying to connect to peer " + p.id + " at " + p.host + ":" + p.port);
                         Socket s = new Socket(p.host, p.port);
                         handleNewConnection(s, true);
                     } catch (Exception e) {
                         // Wait and retry
                     }
                 }
            }
            try { Thread.sleep(5000); } catch (Exception e) {}
        }
    }

    private void runHeartbeat() {
        while (isRunning) {
            long now = System.currentTimeMillis();
            
            if (state == ClusterState.LEADER) {
                // Send heartbeat to all
                JSONObject hb = new JSONObject().put("type", "HEARTBEAT").put("senderId", myId).put("term", 1); // term not fully impl
                broadcast(hb);
            } else {
                 // Check timeout
                 if (now - lastHeartbeatTime > ELECTION_TIMEOUT && state != ClusterState.ELECTION && !activePeers.isEmpty()) {
                     System.out.println("[Cluster-" + myId + "] Leader timeout! Starting election.");
                     startElection();
                 }
            }
            
            try { Thread.sleep(HEARTBEAT_INTERVAL); } catch (Exception e) {}
        }
    }
    
    private void startElection() {
        state = ClusterState.ELECTION;
        leaderId = -1;
        
        // Bully Algorithm: Send ELECTION to all peers with higher ID
        boolean higherExists = false;
        for (Integer pid : activePeers.keySet()) {
            if (pid > myId) {
                activePeers.get(pid).send(new JSONObject().put("type", "ELECTION").put("senderId", myId));
                higherExists = true;
            }
        }
        
        if (!higherExists) {
            // No one higher, I am leader
            becomeLeader();
        }
    }
    
    private void becomeLeader() {
        state = ClusterState.LEADER;
        leaderId = myId;
        System.out.println("[Cluster-" + myId + "] I AM THE LEADER!");
        broadcast(new JSONObject().put("type", "COORDINATOR").put("senderId", myId));
    }

    private void broadcast(JSONObject msg) {
        for (PeerConnection pc : activePeers.values()) {
            pc.send(msg);
        }
    }

    // Inner class for connection handling
    private class PeerConnection implements Runnable {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private int peerId = -1;

        public PeerConnection(Socket socket, boolean outbound) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new PrintWriter(socket.getOutputStream(), true);
        }
        
        public boolean isConnected() { return !socket.isClosed(); }
        
        public void send(JSONObject msg) {
            if(out != null) out.println(msg.toString());
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    JSONObject msg = new JSONObject(line);
                    handleMessage(msg, this);
                }
            } catch (Exception e) {
                 // Disconnected
            } finally {
                close();
            }
        }
        
        public void close() {
            try { socket.close(); } catch(Exception e){}
            if (peerId != -1) {
                activePeers.remove(peerId);
                System.out.println("[Cluster-" + myId + "] Peer " + peerId + " disconnected.");
                if (peerId == leaderId) {
                    // Leader died
                    lastHeartbeatTime = 0; // Trigger timeout immediately
                }
            }
        }
    }

    private synchronized void handleMessage(JSONObject msg, PeerConnection conn) {
        String type = msg.getString("type");
        int senderId = msg.optInt("senderId", -1);
        
        switch (type) {
            case "HELLO":
                conn.peerId = senderId;
                activePeers.put(senderId, conn);
                try {
                    String remoteIp = conn.socket.getInetAddress().getHostAddress();
                    if (remoteIp != null && !remoteIp.isBlank()) {
                        peerHostById.put(senderId, remoteIp);
                    }
                } catch (Exception ignored) {
                }
                System.out.println("[Cluster-" + myId + "] Connected to peer " + senderId);
                
                // EXCHANGE PEERS: If I have peers the sender doesn't know, share them.
                // Simple gossip: Send my full peer list to the new connection.
                org.json.JSONArray peerList = new org.json.JSONArray();
                for (PeerInfo p : peers) {
                    peerList.put(p.toJson());
                }
                conn.send(new JSONObject().put("type", "PEER_LIST").put("peers", peerList));
                
                // If I'm recovering, request a snapshot from whoever I connect to.
                // Only the leader will respond; followers will ignore.
                if (state == ClusterState.RECOVERING) {
                    conn.send(new JSONObject().put("type", "SYNC_REQUEST").put("senderId", myId));
                }
                break;
                
            case "PEER_LIST":
                org.json.JSONArray newPeers = msg.getJSONArray("peers");
                for (int i=0; i<newPeers.length(); i++) {
                    JSONObject pObj = newPeers.getJSONObject(i);
                    int pid = pObj.getInt("id");
                    if (pid != myId) {
                        boolean exists = false;
                        for (PeerInfo existing : peers) { if (existing.id == pid) exists = true; }
                        if (!exists) {
                            String phost = pObj.getString("host");
                            int pport = pObj.getInt("port");
                            System.out.println("[Cluster-" + myId + "] Discovered new peer: " + pid + " at " + phost);
                            peers.add(new PeerInfo(pid, phost, pport));
                        }
                    }
                }
                break;

            case "HEARTBEAT":
                boolean wasRecovering = (state == ClusterState.RECOVERING);
                if (senderId > leaderId || (leaderId == -1)) {
                    leaderId = senderId;
                    state = ClusterState.FOLLOWER;
                }
                lastHeartbeatTime = System.currentTimeMillis();
                if (wasRecovering) {
                    // Found a leader, ask for sync
                    conn.send(new JSONObject().put("type", "SYNC_REQUEST").put("senderId", myId));
                }
                break;
                
            case "ELECTION":
                // Received election from lower ID? (Bully: Lower ID tells Higher ID to take over?)
                // Actually Bully: 
                // 1. P sends ELECTION to all Q > P.
                // 2. If Q receives, sends OK to P, and starts its own election.
                // 3. If P gets no OK, P wins.
                // 4. If P gets OK, P waits for COORDINATOR.
                
                // Simplification for this task:
                // If I get ELECTION from someone with lower ID, I send OK (alive) and start my own election.
                if (senderId < myId) {
                    conn.send(new JSONObject().put("type", "ELECTION_OK").put("senderId", myId));
                    startElection();
                }
                break;
                
            case "ELECTION_OK":
                // Someone higher is alive. Wait for Coordinator message.
                // stop election timeout if we had one?
                break;
                
            case "COORDINATOR":
                boolean wasRecoveringOnCoordinator = (state == ClusterState.RECOVERING);
                leaderId = senderId;
                state = ClusterState.FOLLOWER;
                lastHeartbeatTime = System.currentTimeMillis();
                System.out.println("[Cluster-" + myId + "] New Leader is " + leaderId);
                
                if (wasRecoveringOnCoordinator) {
                    conn.send(new JSONObject().put("type", "SYNC_REQUEST").put("senderId", myId));
                }
                break;
                
            case "REPLICATE_SQL":
                // Leader sent SQL.
                if (state != ClusterState.LEADER) {
                     listener.applyWrite(msg.getString("sql"));

                     // Optional ACK back to leader for quorum replication.
                     if (msg.optBoolean("ack", false) && msg.has("rid")) {
                         conn.send(new JSONObject().put("type", "REPLICATE_ACK").put("rid", msg.getString("rid")).put("senderId", myId));
                     }
                }
                break;

            case "REPLICATE_ACK":
                if (state == ClusterState.LEADER) {
                    String rid = msg.optString("rid", "");
                    AckTracker tracker = pendingReplicationAcks.get(rid);
                    if (tracker != null) {
                        tracker.ack();
                    }
                }
                break;
                
            case "FORWARD_REQUEST":
                 // I am leader, and a follower forwarded a request
                 if (state == ClusterState.LEADER) {
                     // We need to inject this into the DB handler logic
                     // The ClusterListener needs to be able to handle "Request from another node"
                     // The original Client is waiting on the Follower node.
                     // The Follower node sent this.
                     // Ideally we execute and send result back.
                     // IMPORTANT: The Follower is blocking? 
                     // Let's implement Fire-and-Forget for writes for now, or 
                     // Assume the prompt "One node acts as leader... handles all writes".
                     // If we want the follower to respond to client, we need a request ID.
                     
                     // For simplicity: Broadcast the SQL extracted from the request?
                     // The payload is the client request (JSON).
                     // We can execute it, but we can't easily route response back through the raw TCP to the specific thread.
                     // Let's just execute the SQL as if it was local, which will trigger broadcastWrite? 
                     // No, "one node handles all writes", meaning it originates here.
                     
                     // We'll call:
                     // listener.handleForwardedRequest(msg.getJSONObject("payload")); // Not in interface yet
                     
                     // Helper: extract SQL and execute.
                     JSONObject payload = msg.getJSONObject("payload");
                     if (payload.has("sql")) {
                         String sql = payload.getString("sql");
                         listener.applyWrite(sql); // This runs on DB.
                         // It should also trigger replication broadcast if the DB handler does it.
                         // We need to be careful about loops.
                         // The DB handler should logic: if (amLeader) { exec; broadcast; }
                         // So if we just call the DB execution method, it should work.
                     }
                 }
                 break;
                 
            case "SYNC_REQUEST":
                if (state == ClusterState.LEADER) {
                    System.out.println("[Cluster-" + myId + "] Sending Snapshot to " + senderId);
                    JSONObject snapshot = listener.createSnapshot();
                    conn.send(new JSONObject().put("type", "SYNC_SNAPSHOT").put("data", snapshot));
                }
                break;
                
            case "SYNC_SNAPSHOT":
                System.out.println("[Cluster-" + myId + "] Applying Snapshot.");
                listener.applySnapshot(msg.getJSONObject("data"));
                state = ClusterState.FOLLOWER;
                break;
        }
    }
}
