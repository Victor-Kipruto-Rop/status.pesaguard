package com.pesaguard.backend.common.api;

public final class SafeExceptionDiagnostics {

    private static final int MAX_CAUSES = 12;
    private static final int MAX_LENGTH = 16_000;

    private SafeExceptionDiagnostics() {
    }

    public static String stackTrace(Throwable exception) {
        StringBuilder trace = new StringBuilder();
        Throwable current = exception;
        int causes = 0;
        while (current != null && causes < MAX_CAUSES) {
            if (causes > 0) {
                trace.append("\nCaused by: ");
            }
            trace.append(current.getClass().getName());
            for (StackTraceElement frame : current.getStackTrace()) {
                trace.append("\n\tat ").append(frame);
            }
            current = current.getCause();
            causes++;
        }
        String result = trace.toString();
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
    }
}
