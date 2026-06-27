package org.example;

// Centralises iteration counts for *LincheckTest.java so CI can request a
// reduced sweep with `-Pquick` (gradle property → `lincheck.quick` system
// property). Full sweep stays the default for local runs and pushes to main.
final class LincheckConfig {
    private LincheckConfig() {}

    private static final boolean QUICK = Boolean.getBoolean("lincheck.quick");

    static final int ITERATIONS = QUICK ? 10 : 50;
    static final int INVOCATIONS_PER_ITERATION = QUICK ? 200 : 1000;
}
