package com.contacts.sheet.configration;
import com.contacts.sheet.service.TaggerService;


public class RetryUtils {

    public static  <T>  T retry(int maxAttempts, long delayMillis, RetryableOperation<T> operation) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                attempt++;
                System.out.println("🔁 Attempt " + attempt + "...");
                return operation.execute();
            } catch (Exception e) {
                System.err.println("❌ Attempt " + attempt + " failed: " + e.getMessage());
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
    // Functional interface
    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}