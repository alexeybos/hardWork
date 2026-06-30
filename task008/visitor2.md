В Java нет честного множественного наследования, но в качестве некоего аналога можно использовать реализацию нескольких интерфейсов. Поэтому в Java миксины можно реализовать с помощью интерфейсов с дефолтной реализацией методов. Далее достаточно добавить к классу данный интерфейс и он получает нужную функциональность.
В качестве примера решил сильно не умничать и переделал на Java пример с гномами. Ну и добавил один дополнительный миксин с логированием.
```java
public class Dwarf {
    private final String name;

    public Dwarf(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

public interface MiningMixin {

    String getName();
    default String mine() {
        return getName() + " копает руду";
    }
}

public interface FightingMixin {

    String getName();

    default String fight() {
        return getName() + " бьёт врагов";
    }
}

public interface LoggingMixin {
    default void log(String msg) {
        System.out.println("[INFO] " + msg);
    }
}

public class MinerDwarf extends Dwarf implements MiningMixin, LoggingMixin{
    public MinerDwarf(String name) {
        super(name);
    }
}

public class WarriorDwarf extends Dwarf implements FightingMixin, LoggingMixin {
    public WarriorDwarf(String name) {
        super(name);
    }
}

public class BattleDwarf extends Dwarf implements MiningMixin, FightingMixin, LoggingMixin{
    public BattleDwarf(String name) {
        super(name);
    }
}

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
```
Ну и в Java, для того, чтобы иметь доступ к значениям полей классов из функций миксинов необходимо в интерфейсах предусматривать обычные абстрактные методы получения оных.  

Немного грустный факт в том, что про данный прием я, честно говоря, давно и прочно забыл - поверхностно читал про это много лет назад и никогда не использовал. 
Применений в рабочем проекте не нашел (правда и мест для его возможного использования тоже не нашел). Хотя выглядит этот метод (для меня) очень элегантно и прямо таки тянет его где-нибудь заиспользовать. 