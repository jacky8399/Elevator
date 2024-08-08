package com.jacky8399.elevator.utils;

public record FloorScanResult() {
    public enum Type {
        SCANNER,
        NO_SCANNER,
        NO_SCANNER_DISALLOWED,
    }
}
