package org.skillsmart.hardwork.task008.mixin;

public class WarriorDwarf extends Dwarf implements FightingMixin, LoggingMixin {
    public WarriorDwarf(String name) {
        super(name);
    }
}
