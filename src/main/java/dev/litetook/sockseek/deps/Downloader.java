package dev.litetook.sockseek.deps;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Fetches a URL. An interface so the tests never reach the network. */
@FunctionalInterface
public interface Downloader {

    Duration TIMEOUT = Duration.ofSeconds(300);

    byte[] get(String url) throws IOException;

    static Downloader system() {
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .build();
            try (HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(TIMEOUT)
                    .build()) {
                HttpResponse<byte[]> response = client.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                return response.body();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("the download was interrupted");
            }
        };
    }
}
