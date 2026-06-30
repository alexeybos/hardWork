package org.skillsmart.hardwork.task008.mixin;

public class Main {
    public static void main(String[] args) {

        MinerDwarf thor = new MinerDwarf("Торин");
        WarriorDwarf gimli = new WarriorDwarf("Гимли");
        BattleDwarf legolas = new BattleDwarf("Леголас");

        System.out.println(thor.mine()); // Торин копает руду
        System.out.println(gimli.fight()); // Гимли бьёт врагов
        System.out.println(legolas.mine()); // Леголас копает руду
        System.out.println(legolas.fight()); // Леголас бьёт врагов

        thor.log("logging test"); // [INFO] logging test
        gimli.log("logging test"); // [INFO] logging test
        legolas.log("logging test"); // [INFO] logging test
    }
}
