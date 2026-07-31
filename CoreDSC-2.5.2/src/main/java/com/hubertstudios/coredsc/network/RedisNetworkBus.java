package com.hubertstudios.coredsc.network;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

                                                                                       
public final class RedisNetworkBus implements NetworkBus {
    private final String host;
    private final int port;
    private final boolean tls;
    private final String username;
    private final String password;
    private final int database;
    private final String channel;
    private final int timeoutMillis;
    private final ExecutorService io = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "CoreDSC-Redis");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean connected;
    private volatile BiConsumer<String, Map<String, String>> listener;
    private volatile Socket subscriberSocket;

    public RedisNetworkBus(
            String host, int port, boolean tls, String username, String password,
            int database, String channel, int timeoutMillis
    ) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.database = database;
        this.channel = channel;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public CompletableFuture<Void> publish(String eventType, Map<String, String> data) {
        return command("PUBLISH", channel, encode(eventType, data)).thenApply(ignored -> null);
    }

    @Override
    public void subscribe(BiConsumer<String, Map<String, String>> listener) {
        this.listener = listener;
        io.execute(this::subscriberLoop);
    }

    @Override
    public CompletableFuture<Void> put(String key, String value, long ttlSeconds) {
        if (ttlSeconds > 0L) {
            return command("SET", key, value, "EX", Long.toString(ttlSeconds)).thenApply(ignored -> null);
        }
        return command("SET", key, value).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        return command("GET", key).thenApply(value -> Optional.ofNullable(value));
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        return command("DEL", key).thenApply(ignored -> null);
    }

    @Override public boolean isConnected() { return connected && !closed.get(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        Socket socket = subscriberSocket;
        subscriberSocket = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        io.shutdownNow();
    }

    private CompletableFuture<String> command(String... parts) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Redis bus is closed"));
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = openSocket(false);
                 BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                initialise(input, output);
                writeCommand(output, parts);
                Object response = readResp(input);
                connected = true;
                return response == null ? null : response.toString();
            } catch (IOException exception) {
                connected = false;
                throw new IllegalStateException("Redis command failed: " + exception.getMessage(), exception);
            }
        }, io);
    }

    private void subscriberLoop() {
        long delay = 1_000L;
        while (!closed.get()) {
            try (Socket socket = openSocket(true);
                 BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                subscriberSocket = socket;
                initialise(input, output);
                writeCommand(output, "SUBSCRIBE", channel);
                readResp(input);                              
                connected = true;
                delay = 1_000L;
                while (!closed.get()) {
                    Object raw = readResp(input);
                    if (!(raw instanceof Object[] values) || values.length < 3) continue;
                    if (!"message".equals(String.valueOf(values[0]))) continue;
                    Decoded decoded = decode(String.valueOf(values[2]));
                    BiConsumer<String, Map<String, String>> current = listener;
                    if (current != null) current.accept(decoded.type(), decoded.data());
                }
            } catch (Exception exception) {
                connected = false;
                if (closed.get()) break;
                try { Thread.sleep(delay); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                delay = Math.min(30_000L, delay * 2L);
            } finally {
                subscriberSocket = null;
            }
        }
    }

    private Socket openSocket(boolean subscriber) throws IOException {
        Socket socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(subscriber ? 0 : timeoutMillis);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private void initialise(BufferedInputStream input, BufferedOutputStream output) throws IOException {
        if (!password.isBlank()) {
            if (username.isBlank()) writeCommand(output, "AUTH", password);
            else writeCommand(output, "AUTH", username, password);
            readResp(input);
        }
        if (database > 0) {
            writeCommand(output, "SELECT", Integer.toString(database));
            readResp(input);
        }
    }

    private static void writeCommand(BufferedOutputStream output, String... parts) throws IOException {
        output.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.write('\r'); output.write('\n');
        }
        output.flush();
    }

    private static Object readResp(BufferedInputStream input) throws IOException {
        int prefix = input.read();
        if (prefix < 0) throw new EOFException("Redis closed the connection");
        return switch (prefix) {
            case '+' -> readLine(input);
            case '-' -> throw new IOException("Redis error: " + readLine(input));
            case ':' -> Long.parseLong(readLine(input));
            case '$' -> {
                int length = Integer.parseInt(readLine(input));
                if (length < 0) yield null;
                byte[] data = input.readNBytes(length);
                if (data.length != length) throw new EOFException("Incomplete Redis bulk string");
                expectCrLf(input);
                yield new String(data, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int count = Integer.parseInt(readLine(input));
                if (count < 0) yield null;
                Object[] values = new Object[count];
                for (int i = 0; i < count; i++) values[i] = readResp(input);
                yield values;
            }
            default -> throw new IOException("Unsupported Redis RESP prefix: " + (char) prefix);
        };
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder value = new StringBuilder();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new EOFException("Incomplete Redis response");
            if (previous == '\r' && current == '\n') {
                value.setLength(value.length() - 1);
                return value.toString();
            }
            value.append((char) current);
            previous = current;
        }
    }

    private static void expectCrLf(BufferedInputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid Redis terminator");
    }

    private static String encode(String type, Map<String, String> data) {
        StringBuilder encoded = new StringBuilder(url(type));
        for (Map.Entry<String, String> entry : data.entrySet()) {
            encoded.append('&').append(url(entry.getKey())).append('=').append(url(entry.getValue()));
        }
        return encoded.toString();
    }

    private static Decoded decode(String value) {
        String[] parts = value.split("&");
        String type = parts.length == 0 ? "UNKNOWN" : unurl(parts[0]);
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int separator = parts[i].indexOf('=');
            if (separator < 0) continue;
            data.put(unurl(parts[i].substring(0, separator)), unurl(parts[i].substring(separator + 1)));
        }
        return new Decoded(type, Map.copyOf(data));
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    private static String unurl(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
    private record Decoded(String type, Map<String, String> data) { }
}
