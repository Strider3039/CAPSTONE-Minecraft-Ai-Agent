package com.example.aiagent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class ForgeWebSocketClient extends WebSocketClient {

    public ForgeWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[WS] Connected to AI bridge");
    }

    @Override
    public void onMessage(String message) {
        System.out.println("[WS] Received: " + message);
        // Here we can forward messages to Minecraft later
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[WS] Connection closed: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        ex.printStackTrace();
    }
}
