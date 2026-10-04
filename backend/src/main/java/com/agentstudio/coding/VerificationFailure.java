package com.agentstudio.coding;

/** Report environment preparation separately without turning a failed task into success. */
final class VerificationFailure {
    static boolean missingOfflineDependency(String output) {
        return output != null && output.contains("in offline mode")
                && (output.contains("has not been downloaded") || output.contains("could not be resolved"));
    }
    private VerificationFailure() {}
}
