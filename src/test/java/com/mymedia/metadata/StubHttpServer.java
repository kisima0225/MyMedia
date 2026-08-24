package com.mymedia.metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的本地 HTTP 桩。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver}（JDK 25 下可从 classpath 直接用，已实测），
 * <b>不引 MockWebServer</b>——为了几个测试多一个依赖说不出理由；也<b>不赌
 * {@code MockRestServiceServer} 对 {@code RestClient} 的支持</b>，那是没验证过的事。
 *
 * <p>它是真的在监听端口、真的走 HTTP，因此连 User-Agent 头有没有发出去都能断言。
 */
class StubHttpServer implements AutoCloseable {

    private record Canned(int status, String body, Duration delay) {
    }

    private final HttpServer server;
    private final Map<String, Canned> responses = new HashMap<>();
    private final List<String> requestedUris = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final Map<String, String> lastHeaders = new HashMap<>();

    private StubHttpServer(HttpServer server) {
        this.server = server;
    }

    static StubHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubHttpServer stub = new StubHttpServer(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动桩 HTTP 服务器", e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 注册一条应答。{@code path} 只比对路径部分，不含查询串。 */
    void respond(String path, int status, String body) {
        respondAfter(path, Duration.ZERO, status, body);
    }

    void respondAfter(String path, Duration delay, int status, String body) {
        responses.put(path, new Canned(status, body, delay));
    }

    List<String> requestedUris() {
        return List.copyOf(requestedUris);
    }

    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    String lastHeader(String name) {
        return lastHeaders.get(name.toLowerCase());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestedUris.add(exchange.getRequestURI().toString());
        exchange.getRequestHeaders().forEach((name, values) ->
                lastHeaders.put(name.toLowerCase(), String.join(",", values)));
        try (InputStream in = exchange.getRequestBody()) {
            requestBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        Canned canned = responses.getOrDefault(exchange.getRequestURI().getPath(),
                new Canned(404, "{\"detail\":\"not found\"}", Duration.ZERO));
        try {
            Thread.sleep(canned.delay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        byte[] body = canned.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(canned.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
