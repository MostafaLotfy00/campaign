package com.contacts.sheet.configration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryUtils {

    private static final Logger logger = LoggerFactory.getLogger(RetryUtils.class);

    public static <T> T retry(int maxAttempts, long delayMillis, RetryableOperation<T> operation) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                attempt++;
                logger.info("🔁 Attempt {}...", attempt);
                return operation.execute();
            } catch (Exception e) {
                logger.error("❌ Attempt {} failed: {}", attempt, e.getMessage(), e);
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                }
            }
        }
        throw new RuntimeException("🚫 Operation failed after " + maxAttempts + " attempts.");
    }

    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}
