package org.skillsmart.hardwork.task008.mixin;

public interface FightingMixin {

    String getName();

    default String fight() {
        return getName() + " бьёт врагов";
    }
}
