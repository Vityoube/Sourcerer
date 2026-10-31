package dev.sourcerer.loadtest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Closed-loop HTTP load generator for the read path (repo details + tree + blob).
 * usage: LoadTest [baseUrl] [seconds] [concurrency] [warmupSeconds]
 */
public final class LoadTest {
    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        int conc = args.length > 2 ? Integer.parseInt(args[2]) : 128;
        int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 5;

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        String user = "load" + (System.nanoTime() % 1_000_000);
        post(client, base + "/api/auth/register",
                "{\"username\":\"" + user + "\",\"email\":\"" + user + "@x.io\",\"password\":\"password-1\"}", null);
        String login = post(client, base + "/api/auth/login", "{\"username\":\"" + user + "\",\"password\":\"password-1\"}", null);
        String token = login.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        post(client, base + "/api/repos", "{\"name\":\"bench\",\"description\":\"load\"}", token);

        String[] paths = {
            "/api/repos/" + user + "/bench",
            "/api/repos/" + user + "/bench/tree",
            "/api/repos/" + user + "/bench/blob?path=README.md",
        };
        System.out.printf("target=%s concurrency=%d warmup=%ds measure=%ds%n", base, conc, warmup, seconds);

        LongAdder ok = new LongAdder(), bad = new LongAdder();
        long[] lat = new long[4_000_000];
        AtomicLong idx = new AtomicLong();
        long t0 = System.nanoTime();
        long measureStart = t0 + warmup * 1_000_000_000L;
        long end = measureStart + seconds * 1_000_000_000L;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < conc; i++) {
                final int seed = i;
                pool.submit(() -> {
                    int n = seed;
                    while (System.nanoTime() < end) {
                        HttpRequest r = HttpRequest.newBuilder(URI.create(base + paths[n++ % paths.length])).GET().build();
                        long s = System.nanoTime();
                        try {
                            HttpResponse<Void> resp = client.send(r, HttpResponse.BodyHandlers.discarding());
                            long e = System.nanoTime();
                            if (s >= measureStart) {
                                if (resp.statusCode() == 200) {
                                    ok.increment();
                                    long k = idx.getAndIncrement();
                                    if (k < lat.length) lat[(int) k] = e - s;
                                } else {
                                    bad.increment();
                                }
                            }
                        } catch (IOException | InterruptedException ex) {
                            if (s >= measureStart) bad.increment();
                        }
                    }
                });
            }
        }
        int n = (int) Math.min(idx.get(), lat.length);
        long[] sorted = Arrays.copyOf(lat, n);
        Arrays.sort(sorted);
        System.out.printf("requests ok=%d errors=%d  =>  %.0f req/s%n", ok.sum(), bad.sum(), ok.sum() / (double) seconds);
        if (n > 0) {
            System.out.printf("latency ms  p50=%.2f  p90=%.2f  p99=%.2f  p99.9=%.2f  max=%.2f%n",
                    ms(sorted[n / 2]), ms(sorted[(int) (n * 0.90)]), ms(sorted[(int) (n * 0.99)]),
                    ms(sorted[(int) (n * 0.999)]), ms(sorted[n - 1]));
        }
    }

    private static double ms(long nanos) { return nanos / 1e6; }

    private static String post(HttpClient c, String url, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
