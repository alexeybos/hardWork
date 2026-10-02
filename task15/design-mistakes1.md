1. 
Для первого примера взял код из дипломного проекта (периодически открываю его).
Здесь имеется проверка-страховка от неполноты switch, хотя вся логика, так сказать, внутри, и ничего "пользовательского" сюда прийти не может.
Проверка исключительно для того случая, когда разработчик добавил новый тип отчета, а в фабрику его не прописал и только в рантайме получил ошибку.

Было:
```java
    public Report generateReport(ReportRequest request) throws Exception {
        switch (request.getReportType()) {
            case MILEAGE:
                return mileageReportService.generateMileageReport(
                        request.getVehicleId(),
                        request.getPeriod(),
                        request.getStartDate(),
                        request.getEndDate());
            case DRIVER_WORK_TIME:
                return driverHoursReportService.generateDriverHoursReport(
                        request.getVehicleId(),
                        request.getPeriod(),
                        request.getStartDate(),
                        request.getEndDate(),
                        request.getDriverName()
                );
            default:
                throw new IllegalArgumentException("Unknown report type");
        }
    }
```
Стало:
```java
public sealed interface NewReportRequest permits MileageReportRequest, DriverHoursReportRequest {
    ReportRequest.ReportType type();
}

public record MileageReportRequest (
        Long vehicleId,
        Period period,
        LocalDateTime startDate,
        LocalDateTime endDate
) implements NewReportRequest {
    @Override
    public ReportRequest.ReportType type() {
        return ReportRequest.ReportType.MILEAGE;
    }
}

public record DriverHoursReportRequest(
        Long vehicleId,
        Period period,
        LocalDateTime startDate,
        LocalDateTime endDate,
        String driverName
) implements NewReportRequest{

    @Override
    public ReportRequest.ReportType type() {
        return ReportRequest.ReportType.DRIVER_WORK_TIME;
    }
}

public Report generateReport(NewReportRequest request) throws Exception {
    return switch (request) {
        case MileageReportRequest req -> mileageReportService.generateMileageReport(
                req.vehicleId(),
                req.period(),
                req.startDate(),
                req.endDate()
        );
        case DriverHoursReportRequest req -> driverHoursReportService.generateDriverHoursReport(
                req.vehicleId(),
                req.period(),
                req.startDate(),
                req.endDate(),
                req.driverName()
        );
    };
}
```
Использовал pattern matching (все-таки не зря же Java 21 использую). Здесь компилятор сам полноту обработки значений swithс отслеживает. В итоге и никакие лишние значения не придут в метод, и разработчик не забудет прописать вызов нового отчета. 
Ну а отладочный, по сути, exception уже не нужен.

2. Второй пример

Если в первом примере я полностью избавился от "защитного" кода, то здесь хочу отработать вариант, скажем так, переноса проверки из глубины бизнес-логики наверх.
Код сервиса (было):
```java
@Transactional
    public void createBrandWithJdbc(BrandDto brandDto) {
        if (brandDto.name() == null) {
            throw new InvalidParameterException("Name cannot be null");
        }
        jdbcTemplate.update(
                "INSERT INTO brand (name, type, load_capacity, tank, number_of_seats) VALUES (?, ?, ?, ?, ?)",
                brandDto.name(), brandDto.type(), brandDto.loadCapacity(), brandDto.tank(), brandDto.numberOfSeats()
        );
    }

@Schema(description = "Сущность пользователя")
public record BrandDto(
        @Schema(description = "Уникальный идентификатор бренда", example = "124523", accessMode = Schema.AccessMode.READ_ONLY)
        Long id,
        @Schema(description = "Имя бренда", example = "Москвич")
        String name,
        @Schema(description = "Тип т/с", example = "легковая")
        String type,
        @Schema(description = "Грузоподъемность", example = "1500")
        int loadCapacity,
        @Schema(description = "объем бака", example = "50")
        int tank,
        @Schema(description = "Количество мест в салоне", example = "5")
        int numberOfSeats
) {}
```
Стало:
```java
@Schema(description = "Сущность пользователя")
public record BrandDto(
        @Schema(description = "Уникальный идентификатор бренда", example = "124523", accessMode = Schema.AccessMode.READ_ONLY)
        Long id,
        @Schema(description = "Имя бренда", example = "Москвич")
        String name,
        @Schema(description = "Тип т/с", example = "легковая")
        String type,
        @Schema(description = "Грузоподъемность", example = "1500")
        int loadCapacity,
        @Schema(description = "объем бака", example = "50")
        int tank,
        @Schema(description = "Количество мест в салоне", example = "5")
        int numberOfSeats
) {
        public BrandDto {
                Objects.requireNonNull(name, "name");
        }
}

@Transactional
public void createBrandWithJdbc(BrandDto brandDto) {
    jdbcTemplate.update(
            "INSERT INTO brand (name, type, load_capacity, tank, number_of_seats) VALUES (?, ?, ?, ?, ?)",
            brandDto.name(), brandDto.type(), brandDto.loadCapacity(), brandDto.tank(), brandDto.numberOfSeats()
    );
}
```
Здесь проверка перенесена "выше" в сам DTO. Вообще, конечно, можно дополнительно еще и ввести тип BrandName с защитой от пустого значения (дополнительно, например еще и на пробелы проверять). Ну а в представленной реализации это такой минимум получается.

3. 
Следующим примером как раз снятие проблемы передачи в метод некорректных данных с помощью отказа от примитивов. Здесь у меня метод с защитной проверкой на корректность переданного параметра.
```java
public void setBatchSize(int batchSize) {
    if (batchSize < 0) {
        throw new IllegalArgumentException("batchSize must be positive integer. Parameter value is: " + batchSize);
    }
    this.batchSize = batchSize;
}
```
Стало:
```java
public record PositiveInteger(int value) {
    public PositiveInteger {
        if (value < 0) {
            throw new IllegalArgumentException("Value must be positive integer. Parameter value is: " + value);
        }
    }
}

public void setBatchSize(PositiveInteger batchSize) {
    this.batchSize = batchSize.value();
}
```
Здесь создан специальный тип PositiveInteger, использование которого сразу избавляет от необходимости во всех местах, где нужно положительное значение, вносить проверку на неотрицательность.

4. 
Еще одну защитную проверку нашел в классе Pagination в методе (он еще и с ошибкой написан) nexPage. Объект допустимо создать без необходимого для его корректного функционирования pageLoader.
Было:  
```java
public class Pagination<T extends Collection> {
    private AtomicInteger pageIndex = new AtomicInteger(0);
    private PageLoader<T> pageLoader;

    public void setPageLoader(PageLoader pageLoader) {
        this.pageLoader = pageLoader;
    }

    public T nexPage() throws AppException {
        if (pageLoader == null) {
            throw new AppException("Page loader for cache is not set");
        }
        return pageLoader.getPage(pageIndex.getAndIncrement());
    }

    public void resetPageIndex() {
        pageIndex.set(0);
    }
}
```
Стало:
```java
public class Pagination<T extends Collection> {
    private AtomicInteger pageIndex = new AtomicInteger(0);
    private PageLoader<T> pageLoader;

    public Pagination(@NotNull PageLoader<T> pageLoader) {
        this.pageLoader = pageLoader;
    }

    public void setPageLoader(PageLoader pageLoader) {
        this.pageLoader = pageLoader;
    }

    public T nextPage() throws AppException {
        return pageLoader.getPage(pageIndex.getAndIncrement());
    }

    public void resetPageIndex() {
        pageIndex.set(0);
    }
}
```
Проверка вынесена в конструктор. Объект без необходимого для работы pageLoader создать теперь вообще невозможно. Дополнительная проверка не нужна. 

5. 
В этом примере у меня конечно не длинная цепочка if, но тут как раз по сути множество состояний системы, определяемое через if.
Кроме этого тут еще poolSize выступает непосредственно как размер пула, так и в качестве признака использования общего пула:
```java
//в инициализации пулов
if (!customScenarioWorkerCounts.isEmpty()) {
        this.scenarioActorPoolSize.putAll(customScenarioWorkerCounts);
}

public ActorRef scenarioActorOf(Operation operation) {
    final String operCode = operation.getOperationType().getOperCode();
    final int poolSize = scenarioActorPoolSize.computeIfAbsent(operCode, k -> -1);
    return computeActorRef(operCode, poolSize);
}

private ActorRef computeActorRef(String operCode, int poolSize) {
    if (poolSize <= 0) {
        return scenarioActorPool
                .computeIfAbsent("commonPool", k -> createScenarioActors(this.scenarioActorCount, EMPTY));
    }
    return scenarioActorPool.computeIfAbsent(makeName(operCode, poolSize),
            t -> createScenarioActors(poolSize, t));
}    
```
Стало:
```java
public sealed interface ScenarioPoolType permits CommonPool, DedicatedPool {
}

public record CommonPool() implements ScenarioPoolType {
}

public record DedicatedPool(PositiveInteger batchSize) implements ScenarioPoolType {
}
// в инициализации
customScenarioWorkerCounts.forEach((operCode, size) ->
        scenarioActorPoolType.put(operCode, new DedicatedPool(size)));

public ActorRef scenarioActorOfNew(Operation operation) {
    final String operCode = operation.getOperationType().getOperCode();
    final ScenarioPoolType poolType = scenarioActorPoolType
            .computeIfAbsent(operCode, unused -> new CommonPool());
    return computeActorRef(operCode, poolType);
}

private ActorRef computeActorRef(String operCode, ScenarioPoolType spec) {
        return switch (spec) {
            case CommonPool() -> scenarioActorPool.computeIfAbsent("commonPool",
                    k -> createScenarioActors(this.scenarioActorCount, EMPTY));
            case DedicatedPool(PositiveInteger size) -> scenarioActorPool.computeIfAbsent(
                    makeName(operCode, size.value()),
                    t -> createScenarioActors(size.value(), t));
        };
    }
```
Введены конкретные типы для обоих типов пулов. Контроль на компиляторе при использовании pattern matching. Теперь отрицательный размер пула никуда не передается, не возникнет путаницы из-за смешения роли данного параметра в логике создания (определения) пулов. 
Цепочки if больше нет. 

Вообще, как некоторое резюме задания - очень частый соблазн поставить проверку "а до сюда откуда-нибудь дойдут неконсистентные значения параметров". 
Это очень частая мысль и на самом деле я раньше думал, что это хорошая мысль - значит я хороший программист, раз вижу потенциальную опасность и ставлю защиту :)
А оказалось, что это признак плохого дизайна системы. 