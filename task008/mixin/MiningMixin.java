package org.skillsmart.hardwork.task008.mixin;

public interface MiningMixin {

    String getName();
    default String mine() {
        return getName() + " копает руду";
    }
}
