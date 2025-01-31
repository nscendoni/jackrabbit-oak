/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.run;

import org.apache.jackrabbit.guava.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.jackrabbit.oak.commons.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Generic concurrent file downloader which uses Java NIO channels to potentially leverage OS internal optimizations.
 */
public class Downloader implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(Downloader.class);

    private final ExecutorService executorService;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int slowLogThreshold;
    private final int maxRetries;
    private final long retryInitialInterval;
    private final boolean failOnError;
    private final String checksumAlgorithm;
    private final int bufferSize;
    private final List<Future<ItemResponse>> responses;

    public Downloader(int concurrency, int connectTimeoutMs, int readTimeoutMs) {
        this(concurrency, connectTimeoutMs, readTimeoutMs, 3, 100L, false, 10_000, null, 16384);
    }

    public Downloader(int concurrency, int connectTimeoutMs, int readTimeoutMs, int maxRetries, long retryInitialInterval,
                      boolean failOnError, int slowLogThreshold, String checksumAlgorithm, int bufferSize) {
        if (concurrency <= 0 || concurrency > 1000) {
            throw new IllegalArgumentException("concurrency range must be between 1 and 1000");
        }
        if (connectTimeoutMs < 0 || readTimeoutMs < 0) {
            throw new IllegalArgumentException("connect and/or read timeouts can not be negative");
        }
        if (maxRetries <= 0 || maxRetries > 100) {
            throw new IllegalArgumentException("maxRetries range must be between 1 and 100");
        }
        LOG.info("Initializing Downloader with max number of concurrent requests={}", concurrency);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.slowLogThreshold = slowLogThreshold;
        this.maxRetries = maxRetries;
        this.retryInitialInterval = retryInitialInterval;
        this.failOnError = failOnError;
        // fail fast in case the algorithm is not supported
        if (checksumAlgorithm != null && checksumAlgorithm.trim().length() > 0) {
            this.checksumAlgorithm = checksumAlgorithm.trim();
            try {
                MessageDigest.getInstance(checksumAlgorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        } else {
            this.checksumAlgorithm = null;
        }
        this.bufferSize = bufferSize;

        this.executorService = new ThreadPoolExecutor(
                (int) Math.ceil(concurrency * .1), concurrency, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder()
                        .setNameFormat("downloader-%d")
                        .setDaemon(true)
                        .build()
        );
        this.responses = new ArrayList<>();
    }

    public void offer(Item item) {
        responses.add(
                this.executorService.submit(new RetryingCallable<>(new DownloaderWorker(item)))
        );
    }

    public DownloadReport waitUntilComplete() {
        List<ItemResponse> itemResponses = responses.stream()
                .map(itemResponseFuture -> {
                    try {
                        return itemResponseFuture.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("thread waiting for the response was interrupted", e);
                    } catch (ExecutionException e) {
                        if (failOnError) {
                            throw new RuntimeException("execution failed, e");
                        } else {
                            LOG.error("Failure downloading item", e);
                            return ItemResponse.FAILURE;
                        }
                    }
                }).collect(Collectors.toList());

        return new DownloadReport(
                itemResponses.stream().filter(r -> !r.failed).count(),
                itemResponses.stream().filter(r -> r.failed).count(),
                itemResponses.stream().filter(r -> !r.failed).mapToLong(r -> r.size).sum()
        );
    }

    @Override
    public void close() throws IOException {
        executorService.shutdown();
    }

    private class DownloaderWorker implements Callable<ItemResponse> {

        private final Item item;

        public DownloaderWorker(Item item) {
            this.item = item;
        }

        @Override
        public ItemResponse call() throws Exception {
            long t0 = System.nanoTime();

            URLConnection sourceUrl = new URL(item.source).openConnection();
            sourceUrl.setConnectTimeout(connectTimeoutMs);
            sourceUrl.setReadTimeout(readTimeoutMs);

            // Updating a MessageDigest from multiple threads is not thread safe, so we cannot reuse a single instance.
            // Creating a new instance is a lightweight operation, no need to increase complexity by creating a pool.
            MessageDigest md = null;
            if (checksumAlgorithm != null && item.checksum != null) {
                md = MessageDigest.getInstance(checksumAlgorithm);
            }

            Path destinationPath = Paths.get(item.destination);
            Files.createDirectories(destinationPath.getParent());

            long size = 0;
            try (InputStream inputStream = sourceUrl.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(destinationPath.toFile())) {
                byte[] buffer = new byte[bufferSize];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (md != null) {
                        md.update(buffer, 0, bytesRead);
                    }
                    outputStream.write(buffer, 0, bytesRead);
                    size += bytesRead;
                }
            }

            if (md != null) {
                byte[] checksumBytes = md.digest();

                // Convert the checksum bytes to a hexadecimal string
                StringBuilder sb = new StringBuilder();
                for (byte b : checksumBytes) {
                    sb.append(String.format("%02x", b));
                }
                String checksum = sb.toString();
                // Warning: most modern checksum algorithms used for cryptographic purposes are designed to be case-insensitive,
                // to ensure that the same checksum value is produced regardless of the input's case. There may be some
                // legacy algorithms that are case-sensitive. Using equalsIgnoreCase can be considered safe here.
                if (!checksum.equalsIgnoreCase(item.checksum)) {
                    Files.deleteIfExists(destinationPath);
                    throw new IOException("Checksum does not match! Expected: " + item.checksum + ", Actual: " + checksum);
                }
            }

            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            if (slowLogThreshold > 0 && elapsed >= slowLogThreshold) {
                LOG.warn("{} [{}] downloaded in {} ms", item.source, IOUtils.humanReadableByteCount(size), elapsed);
            } else {
                LOG.debug("{} [{}] downloaded in {} ms", item.source, IOUtils.humanReadableByteCount(size), elapsed);
            }
            return ItemResponse.success(size);
        }

        @Override
        public String toString() {
            return "DownloaderWorker{" +
                    "item=" + item +
                    '}';
        }
    }

    private class RetryingCallable<V> implements Callable<V> {
        private final Callable<V> callable;

        public RetryingCallable(Callable<V> callable) {
            this.callable = callable;
        }

        public V call() {
            int retried = 0;
            // Save exceptions messages that are thrown after each failure, so they can be printed if all retries fail
            Map<String, Integer> exceptions = new HashMap<>();

            // Loop until it doesn't throw an exception or max number of tries is reached
            while (true) {
                try {
                    return callable.call();
                } catch (IOException e) {
                    retried++;
                    exceptions.compute(e.getClass().getSimpleName() + " - " + e.getMessage(),
                            (key, val) -> val == null ? 1 : val + 1
                    );

                    // Throw exception if number of tries has been reached
                    if (retried == Downloader.this.maxRetries) {
                        // Get a string of all exceptions that were thrown
                        StringBuilder summary = new StringBuilder();
                        for (Map.Entry<String, Integer> entry: exceptions.entrySet()) {
                            summary.append("\n\t").append(entry.getValue()).append("x: ").append(entry.getKey());
                        }

                        throw new RetryException(retried, summary.toString(), e);
                    } else {
                        // simple exponential backoff mechanism
                        long waitTime = (long) (Math.pow(2, retried) * Downloader.this.retryInitialInterval);
                        LOG.warn("Callable {}. Retrying statement after {} ms; number of times failed: {}",
                                callable, waitTime, retried, e);
                        try {
                            Thread.sleep(waitTime);
                        } catch (InterruptedException ignore) {}
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Callable " + callable + " threw an unrecoverable exception", e);
                }
            }
        }
    }

    private static class RetryException extends RuntimeException {

        private final int tries;

        public RetryException(int tries, String message, Throwable cause) {
            super(message, cause);
            this.tries = tries;
        }

        @Override
        public String toString() {
            return "Tried " + tries + " times: \n" + super.toString();
        }
    }

    public static class Item {
        public String source;
        public String destination;
        public String checksum;

        @Override
        public String toString() {
            return "Item{" +
                    "source='" + source + '\'' +
                    ", destination='" + destination + '\'' +
                    (checksum != null ? ", checksum='" + checksum + '\'' : "") +
                    '}';
        }
    }

    private static class ItemResponse {
        public static final ItemResponse FAILURE = new ItemResponse(true, -1);
        public final boolean failed;
        public final long size;

        public ItemResponse(boolean failed, long size) {
            this.failed = failed;
            this.size = size;
        }

        public static ItemResponse success(long size) {
            return new ItemResponse(false, size);
        }
    }

    public static class DownloadReport {
        public final long successes;
        public final long failures;
        public final long totalBytesTransferred;

        public DownloadReport(long successes, long failures, long totalBytesTransferred) {
            this.successes = successes;
            this.failures = failures;
            this.totalBytesTransferred = totalBytesTransferred;
        }
    }

}
