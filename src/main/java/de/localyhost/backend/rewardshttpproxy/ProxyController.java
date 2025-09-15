package de.localyhost.backend.rewardshttpproxy;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/proxy")
public class ProxyController {

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private SessionService sessionService;

    @GetMapping({"", "/"})
    public Mono<ResponseEntity<String>> proxyDefaultGet(
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return proxyService.handleProxyRequest("", null, request, response, HttpMethod.GET);
    }

    @PostMapping({"", "/"})
    public Mono<ResponseEntity<String>> proxyDefaultPost(
            @RequestBody(required = false) String body,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return proxyService.handleProxyRequest("", body, request, response, HttpMethod.POST);
    }

    @GetMapping("/{token}")
    public Mono<ResponseEntity<String>> proxyGet(
            @PathVariable String token,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return handleProxyRequest(token, null, request, response, HttpMethod.GET);
    }

    @PostMapping("/{token}")
    public Mono<ResponseEntity<String>> proxyPost(
            @PathVariable String token,
            @RequestBody(required = false) String body,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return handleProxyRequest(token, body, request, response, HttpMethod.POST);
    }

    @PutMapping("/{token}")
    public Mono<ResponseEntity<String>> proxyPut(
            @PathVariable String token,
            @RequestBody(required = false) String body,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return handleProxyRequest(token, body, request, response, HttpMethod.PUT);
    }

    @DeleteMapping("/{token}")
    public Mono<ResponseEntity<String>> proxyDelete(
            @PathVariable String token,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        return handleProxyRequest(token, null, request, response, HttpMethod.DELETE);
    }

    @RequestMapping(value = "/{token}/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public Mono<ResponseEntity<String>> proxyWithPath(
            @PathVariable String token,
            @RequestBody(required = false) String body,
            @NotNull ServerHttpRequest request,
            ServerHttpResponse response) {

        String fullPath = request.getPath().value();
        String proxyPath = fullPath.substring(fullPath.indexOf(token) + token.length());

        if (!proxyPath.isEmpty()) {
            try {
                String decodedUrl = new String(Base64.getDecoder().decode(token));
                String fullUrl = decodedUrl + proxyPath;
                String newToken = Base64.getEncoder().encodeToString(fullUrl.getBytes());
                token = newToken;
            } catch (Exception e) {
            }
        }

        HttpMethod method = request.getMethod();
        return handleProxyRequest(token, body, request, response, method);
    }

    private Mono<ResponseEntity<String>> handleProxyRequest(
            String token, String body, ServerHttpRequest request,
            @NotNull ServerHttpResponse response, HttpMethod method) {

        response.getHeaders().add("Access-Control-Allow-Origin", "*");
        response.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.getHeaders().add("Access-Control-Allow-Headers", "*");

        return proxyService.handleProxyRequest(token, body, request, response, method);
    }

    @GetMapping("/test")
    public ResponseEntity<String> testProxy(
            @RequestParam(required = false) String url,
            ServerHttpRequest request) {

        if (url == null || url.isEmpty()) {
            url = "https://www.google.com";
        }

        String encodedUrl = Base64.getEncoder().encodeToString(url.getBytes());
        String proxyUrl = getBaseUrl(request) + "/proxy/" + encodedUrl;

        return ResponseEntity.ok(String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Proxy Test</title>
                <meta charset="UTF-8">
            </head>
            <body>
                <h2>Proxy Test</h2>
                <p>Target URL: <code>%s</code></p>
                <p>Encoded Token: <code>%s</code></p>
                <p>Proxy URL: <a href="%s" target="_blank">%s</a></p>
                
                <form method="get" action="/proxy/test">
                    <label>Test another URL:</label><br>
                    <input type="text" name="url" value="%s" style="width: 400px;"><br>
                    <button type="submit">Generate Proxy Link</button>
                </form>
            </body>
            </html>
            """, url, encodedUrl, proxyUrl, proxyUrl, url));
    }

    private String getBaseUrl(ServerHttpRequest request) {
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

    @GetMapping("/api/credentials/{uuid}")
    public ResponseEntity<List<Map<String, String>>> getCredentials(
            @PathVariable String uuid,
            ServerHttpRequest request) {
        return sessionService.getCredentials(uuid, request);
    }

    @DeleteMapping("/api/credentials/{uuid}/{index}")
    public ResponseEntity<Void> deleteCredential(
            @PathVariable String uuid,
            @PathVariable int index,
            ServerHttpRequest request) {
        return sessionService.deleteCredential(uuid, index, request);
    }
}