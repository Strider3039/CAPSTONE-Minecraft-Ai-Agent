package com.example.aiagent.server;

import com.google.gson.JsonObject;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

public class ServerCommandQueue {
    public record Cmd(String kind, JsonObject full, JsonObject payload) {}

    private static final ConcurrentLinkedQueue<Cmd> Q = new ConcurrentLinkedQueue<>();

    public static void enqueue(String kind, JsonObject full, JsonObject payload) {
        Q.add(new Cmd(kind, full, payload));
    }

    public static void drain(TriConsumer<String, JsonObject, JsonObject> fn) {
        Cmd c;
        while ((c = Q.poll()) != null) {
            fn.accept(c.kind(), c.full(), c.payload());
        }
    }

    @FunctionalInterface
    public interface TriConsumer<A,B,C> {
        void accept(A a, B b, C c);
    }
}
