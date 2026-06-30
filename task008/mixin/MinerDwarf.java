package org.skillsmart.hardwork.task008.mixin;

public class MinerDwarf extends Dwarf implements MiningMixin, LoggingMixin{
    public MinerDwarf(String name) {
        super(name);
    }
}
