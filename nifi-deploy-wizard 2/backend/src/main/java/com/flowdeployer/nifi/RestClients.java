package com.flowdeployer.nifi;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds (and caches) a RestClient per base URL.
 *
 * Prefer adding your cluster CA to the JVM truststore. insecureSkipTlsVerify mirrors the script's
 * curl -k; with the JDK HttpClient you must ALSO start the JVM with
 * -Djdk.internal.httpclient.disableHostnameVerification=true if hostnames don't match the cert.
 */
@Component
public class RestClients {

    private final Map<String, RestClient> cache = new ConcurrentHashMap<>();

    public RestClient forUrl(String baseUrl, boolean insecure) {
        return cache.computeIfAbsent(baseUrl + "|" + insecure, k -> build(baseUrl, insecure));
    }

    private static RestClient build(String baseUrl, boolean insecure) {
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (insecure) {
            http.sslContext(trustAllContext());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build trust-all SSL context", e);
        }
    }
}
