package de.localyhost.backend.rewardshttpproxy;

import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

@Component
public class HtmlProcessor {

    public String processResponseBody(String body, String targetUrl, String proxyBaseUrl) {
        if (body == null || body.trim().isEmpty()) return body;

        if (!isHtmlContent(body)) return body;

        try {
            Document doc = Jsoup.parse(body);
            String baseHost = extractHost(targetUrl);
            String baseProtocol = extractProtocol(targetUrl);

            rewriteLinks(doc, "a", "href", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "form", "action", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "link", "href", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "script", "src", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "img", "src", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "iframe", "src", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "video", "src", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "audio", "src", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "source", "src", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "embed", "src", baseHost, baseProtocol, proxyBaseUrl);
            rewriteLinks(doc, "object", "data", baseHost, baseProtocol, proxyBaseUrl);

            rewriteLinks(doc, "video", "poster", baseHost, baseProtocol, proxyBaseUrl);

            rewriteCssUrls(doc, baseHost, baseProtocol, proxyBaseUrl);

            rewriteMetaRefresh(doc, baseHost, baseProtocol, proxyBaseUrl);

            injectProxyScript(doc, proxyBaseUrl);

            return doc.html();

        } catch (Exception e) {
            e.printStackTrace();
            return body;
        }
    }

    private boolean isHtmlContent(@NotNull String body) {
        String trimmed = body.trim().toLowerCase();
        return trimmed.contains("<html") || trimmed.contains("<!doctype") ||
                trimmed.contains("<head") || trimmed.contains("<body") ||
                trimmed.contains("<title") || trimmed.contains("<meta") ||
                (trimmed.startsWith("<") && trimmed.contains(">") && trimmed.length() > 20);
    }

    private void rewriteLinks(@NotNull Document doc, String tag, String attr,
                              String baseHost, String baseProtocol, String proxyPath) {
        for (Element element : doc.select(tag + "[" + attr + "]")) {
            String url = element.attr(attr);
            String rewrittenUrl = rewriteUrl(url, baseHost, baseProtocol, proxyPath);
            element.attr(attr, rewrittenUrl);
        }
    }

    private void rewriteCssUrls(@NotNull Document doc, String baseHost, String baseProtocol, String proxyPath) {
        for (Element element : doc.select("[style]")) {
            String style = element.attr("style");
            String rewrittenStyle = rewriteCssUrlsInText(style, baseHost, baseProtocol, proxyPath);
            element.attr("style", rewrittenStyle);
        }

        for (Element styleElement : doc.select("style")) {
            String cssContent = styleElement.html();
            String rewrittenCss = rewriteCssUrlsInText(cssContent, baseHost, baseProtocol, proxyPath);
            styleElement.html(rewrittenCss);
        }
    }

    private void rewriteMetaRefresh(@NotNull Document doc, String baseHost, String baseProtocol, String proxyPath) {
        for (Element meta : doc.select("meta[http-equiv=refresh]")) {
            String content = meta.attr("content");
            if (content.contains("url=")) {
                String[] parts = content.split("url=", 2);
                if (parts.length == 2) {
                    String url = parts[1].trim();
                    String rewrittenUrl = rewriteUrl(url, baseHost, baseProtocol, proxyPath);
                    meta.attr("content", parts[0] + "url=" + rewrittenUrl);
                }
            }
        }
    }

    private String rewriteCssUrlsInText(String cssText, String baseHost, String baseProtocol, String proxyPath) {
        if (cssText == null) return null;

        return Pattern.compile("url\\s*\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)")
                .matcher(cssText)
                .replaceAll(matchResult -> {
                    String url = matchResult.group(1);
                    String rewrittenUrl = rewriteUrl(url, baseHost, baseProtocol, proxyPath);
                    return "url('" + rewrittenUrl + "')";
                });
    }

    private String rewriteUrl(String url, String baseHost, String baseProtocol, String proxyPath) {
        if (url == null || url.trim().isEmpty()) return url;
        if (isSkipUrl(url)) return url;

        try {
            String absoluteUrl = resolveUrl(url, baseHost, baseProtocol);
            String encodedUrl = Base64.getEncoder().encodeToString(absoluteUrl.getBytes(StandardCharsets.UTF_8));
            return proxyPath + "/proxy/" + encodedUrl;
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isSkipUrl(@NotNull String url) {
        String lowerUrl = url.toLowerCase().trim();
        return lowerUrl.startsWith("javascript:") ||
                lowerUrl.startsWith("mailto:") ||
                lowerUrl.startsWith("tel:") ||
                lowerUrl.startsWith("data:") ||
                lowerUrl.startsWith("#") ||
                lowerUrl.startsWith("about:") ||
                lowerUrl.startsWith("blob:") ||
                lowerUrl.startsWith("filesystem:") ||
                lowerUrl.isEmpty() ||
                lowerUrl.equals("/");
    }

    private @NotNull String resolveUrl(String url, String baseHost, String baseProtocol) {
        url = url.trim();

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        } else if (url.startsWith("//")) {
            return baseProtocol + ":" + url;
        } else if (url.startsWith("/")) {
            return baseProtocol + "://" + baseHost + url;
        } else {
            return baseProtocol + "://" + baseHost + "/" + url;
        }
    }

    private String extractHost(String url) {
        try {
            URL parsedUrl = new URL(url);
            return parsedUrl.getHost();
        } catch (Exception e) {
            return url.replaceFirst("^https?://", "").split("/")[0].split("\\?")[0].split("#")[0];
        }
    }

    private String extractProtocol(String url) {
        try {
            URL parsedUrl = new URL(url);
            return parsedUrl.getProtocol();
        } catch (Exception e) {
            return url.startsWith("https://") ? "https" : "http";
        }
    }

    private void injectProxyScript(@NotNull Document doc, String proxyPath) {
        Element head = doc.head();

        String script = String.format("""
            <script type='text/javascript'>
            (function() {
                var PROXY_BASE = '%s';
               \s
                function isProxyUrl(url) {
                    return url && url.includes(PROXY_BASE + '/proxy/');
                }
               \s
                function encodeProxyUrl(url) {
                    if (!url || isProxyUrl(url)) return url;
                    if (url.startsWith('javascript:') || url.startsWith('mailto:') ||\s
                        url.startsWith('tel:') || url.startsWith('data:') ||\s
                        url.startsWith('blob:') || url.startsWith('#')) {
                        return url;
                    }
                    try {
                        var encodedUrl = btoa(url);
                        return PROXY_BASE + '/proxy/' + encodedUrl;
                    } catch(e) {
                        console.warn('Failed to encode URL:', url);
                        return url;
                    }
                }

                // Override window.open
                var originalOpen = window.open;
                window.open = function(url, name, features) {
                    url = encodeProxyUrl(url);
                    return originalOpen.call(window, url, name, features);
                };

                // Handle ALL clicks on links - not just special ones
                document.addEventListener('click', function(e) {
                    var link = e.target.closest('a');
                    if (link && link.href) {
                        e.preventDefault();
                        var newUrl = encodeProxyUrl(link.href);
                       \s
                        // Check if it should open in new window/tab
                        if (link.target === '_blank' || e.ctrlKey || e.metaKey || e.button === 1) {
                            window.open(newUrl, link.target || '_blank');
                        } else {
                            window.location.href = newUrl;
                        }
                    }
                }, true);

                // Handle form submissions
                document.addEventListener('submit', function(e) {
                    var form = e.target;
                    if (form.action) {
                        form.action = encodeProxyUrl(form.action);
                    }
                });

                // Override history.pushState and replaceState
                var originalPushState = history.pushState;
                var originalReplaceState = history.replaceState;
               \s
                history.pushState = function(state, title, url) {
                    url = encodeProxyUrl(url);
                    return originalPushState.call(history, state, title, url);
                };
               \s
                history.replaceState = function(state, title, url) {
                    url = encodeProxyUrl(url);
                    return originalReplaceState.call(history, state, title, url);
                };

                // Handle AJAX requests
                var originalXHROpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
                    url = encodeProxyUrl(url);
                    return originalXHROpen.call(this, method, url, async, user, password);
                };

                // Handle fetch requests
                if (window.fetch) {
                    var originalFetch = window.fetch;
                    window.fetch = function(input, init) {
                        if (typeof input === 'string') {
                            input = encodeProxyUrl(input);
                        } else if (input && input.url) {
                            input.url = encodeProxyUrl(input.url);
                        }
                        return originalFetch.call(window, input, init);
                    };
                }

                // Handle location changes
                var originalLocationSetter = Object.getOwnPropertyDescriptor(window, 'location') ||\s
                                           Object.getOwnPropertyDescriptor(Location.prototype, 'href');
               \s
                if (originalLocationSetter && originalLocationSetter.set) {
                    Object.defineProperty(window.location, 'href', {
                        set: function(url) {
                            url = encodeProxyUrl(url);
                            originalLocationSetter.set.call(this, url);
                        },
                        get: originalLocationSetter.get
                    });
                }

                // Intercept any dynamically created links
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.type === 'childList') {
                            mutation.addedNodes.forEach(function(node) {
                                if (node.nodeType === 1) { // Element node
                                    // Check if the node itself is a link
                                    if (node.tagName === 'A' && node.href && !isProxyUrl(node.href)) {
                                        node.href = encodeProxyUrl(node.href);
                                    }
                                    // Check for links within the added node
                                    var links = node.querySelectorAll ? node.querySelectorAll('a[href]') : [];
                                    for (var i = 0; i < links.length; i++) {
                                        if (!isProxyUrl(links[i].href)) {
                                            links[i].href = encodeProxyUrl(links[i].href);
                                        }
                                    }
                                }
                            });
                        }
                    });
                });
               \s
                observer.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true
                });

                console.log('Proxy script loaded for: ' + PROXY_BASE);
            })();
            </script>
           \s""", proxyPath);

        head.append(script);
    }
}