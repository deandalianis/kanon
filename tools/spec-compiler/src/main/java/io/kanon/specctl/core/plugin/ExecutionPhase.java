package io.kanon.specctl.core.plugin;

public enum ExecutionPhase {
    MODEL(10),
    CONTRACT(20),
    RUNTIME(30),
    SUPPORT(40);

    private final int order;

    ExecutionPhase(int order) {
        this.order = order;
    }

    public int order() {
        return order;
    }
}
