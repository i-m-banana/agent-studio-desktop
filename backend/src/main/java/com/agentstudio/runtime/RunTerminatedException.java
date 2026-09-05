package com.agentstudio.runtime;

public class RunTerminatedException extends RuntimeException {
    private final RunTermination termination;

    public RunTerminatedException(RunTermination termination) {
        super(termination.reason());
        this.termination = termination;
    }

    public RunTermination termination() {
        return termination;
    }
}
