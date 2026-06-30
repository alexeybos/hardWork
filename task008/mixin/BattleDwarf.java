package org.skillsmart.hardwork.task008.mixin;

public class BattleDwarf extends Dwarf implements MiningMixin, FightingMixin, LoggingMixin{
    public BattleDwarf(String name) {
        super(name);
    }
}
