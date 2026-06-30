package org.skillsmart.hardwork.task008.mixin;

public interface LoggingMixin {
    default void log(String msg) {
        System.out.println("[INFO] " + msg);
    }
}
