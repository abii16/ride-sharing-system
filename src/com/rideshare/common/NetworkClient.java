package com.rideshare.common;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;


public class NetworkClient implements AutoCloseable {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final String host;
    private final int port;

    public NetworkClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws IOException {
        this.socket = new Socket(host, port);
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        System.out.println("[NetworkClient] Connected to " + host + ":" + port);
    }

    public void send(JSONObject json) {
        if (out != null) {
            out.println(json.toString());
        }
    }

    public JSONObject receive() throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new IOException("Connection closed by remote host");
        }
        return new JSONObject(line);
    }

    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    @Override
    public void close() throws IOException {
        if (socket != null) socket.close();
        if (out != null) out.close();
        if (in != null) in.close();
    }
}
