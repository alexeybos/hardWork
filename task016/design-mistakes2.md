1. 
Начинаю традиционно с "пристрелки". ООП-шная реализация три в ряд, где код попроще.
Здесь глаз зацепился за реализацию команды хода. В классе смешаны реализация проверки ходи и непосредственно его выполнения.
При этом проверка валидности разделена на две части: сначала корректность соседства клеток, потом выполняется ход и проверяется наличие вновь созданной комбинации(-й). При отсутствии ход откатывается.
Кроме этого не реализован метод проверки наличия хода (GameField.isTurnPossible), что является условием продолжения игры.
```java
public UserTurnCommand(GameEngine game, FieldCoordinate from, FieldCoordinate to) {
        this.game = game;
        this.field = game.getGameField();
        this.from = from;
        this.to = to;
    }

    @Override
    public void execute() {
        if (game.getGameStatus() != GameStatuses.USER_TURN_WAIT) return;
        if (!checkTurnValid()) {
            System.out.println("Некорректный ход. Повторите ввод.");
            return;
        }
        elementExchange();
        System.out.println("Ход сделан.");
        field.show();
        Combinations combinations = new Combinations();
        if (!combinations.hasMatches(field)) {
            System.out.println("Некорректный ход. Комбинаций не найдено.");
            undo();
            field.show();
            return;
        }
        Set<FieldCoordinate> matches = combinations.getCombinations(field);
        long points = combinations.getScore(field, matches);
        combinations.deleteCombination(field, matches);
        StatObj stats = game.getStats();
        stats.setScore(stats.getScore() + points);
        System.out.println("Удаляю совпадения.");
        field.show();
        field.fallElements();
        System.out.println("Элементы падают.");
        field.show();
        field.fill();
        System.out.println("Заполняю пустоту.");
        field.settleField();
        field.show();
    }

    @Override
    public void undo() {
        elementExchange();
    }

    private void elementExchange() {
        GameFieldElement forChange = field.getFieldElement(from);
        field.setFieldElement(field.getFieldElement(to), from);
        field.setFieldElement(forChange, to);
    }

    private boolean checkTurnValid() {
        int offsetX = Math.abs(from.getX()-to.getX());
        int offsetY = Math.abs(from.getY()-to.getY());
        if ((offsetX == 1 && offsetY == 0) || (offsetX == 0 && offsetY == 1)) return true;

        return false;
    }
```
Кажется правильным вынести логику проверки в отдельный класс и принимать решение о валидности в одном шаге, не "размазывая" по методу.
И во-вторых, наконец реализуется отсутствующий метод определения окончания игры с помощью того же класса, а иначе пришлось бы дублировать. 
```java
/**
 * Проверка валидности хода.
 */
public class TurnValidator {

    public boolean isValid(GameField field, FieldCoordinate from, FieldCoordinate to) {
        if (!isNeighbors(from, to)) {
            return false;
        }
        if (!isNotEmptyElements(field.getFieldElement(from), field.getFieldElement(to))) {
            return false;
        }
        exchange(field, from, to);
        Combinations combinations = new Combinations();
        boolean hasCombinations = combinations.hasMatches(field);
        exchange(field, from, to);
        return hasCombinations;
    }

    private boolean isNeighbors(FieldCoordinate from, FieldCoordinate to) {
        int offsetX = Math.abs(from.getX()-to.getX());
        int offsetY = Math.abs(from.getY()-to.getY());
        return (offsetX == 1 && offsetY == 0) || (offsetX == 0 && offsetY == 1);
    }

    private boolean isNotEmptyElements(GameFieldElement fromElement, GameFieldElement toElement) {
        return fromElement.getElementType() != GameElementType.EMPTY && toElement.getElementType() != GameElementType.EMPTY;
    }

    private static void exchange(GameField field, FieldCoordinate from, FieldCoordinate to) {
        GameFieldElement origin = field.getFieldElement(from);
        field.setFieldElement(field.getFieldElement(to), from);
        field.setFieldElement(origin, to);
    }
}

public void execute() {
    if (game.getGameStatus() != GameStatuses.USER_TURN_WAIT) return;
    TurnValidator turnValidator = new TurnValidator();
    if (!turnValidator.isValid(field, from, to)) {
        System.out.println("Некорректный ход. Повторите ввод.");
        return;
    }
    elementExchange();
    System.out.println("Ход сделан.");
    field.show();
    Combinations combinations = new Combinations();
    Set<FieldCoordinate> matches = combinations.getCombinations(field);
    long points = combinations.getScore(field, matches);
    combinations.deleteCombination(field, matches);
    StatObj stats = game.getStats();
    stats.setScore(stats.getScore() + points);
    System.out.println("Удаляю совпадения.");
    field.show();
    field.fallElements();
    System.out.println("Элементы падают.");
    field.show();
    field.fill();
    System.out.println("Заполняю пустоту.");
    field.settleField();
    field.show();
}

public boolean isTurnPossible() {
    TurnValidator validator = new TurnValidator();
    for (int y = 0; y < fieldSize; y++) {
        for (int x = 0; x < fieldSize; x++) {
            FieldCoordinate from = new FieldCoordinate(x, y);
            if (x + 1 < fieldSize && validator.isValid(this, from, new FieldCoordinate(x + 1, y))) {
                return true;
            }
            if (y + 1 < fieldSize && validator.isValid(this, from, new FieldCoordinate(x, y + 1))) {
                return true;
            }
        }
    }
    return false;
}
```
Теперь вопрос валидации переехал целиком в класс TurnValidator. Конечно если подходить строго, то написание метода GameField.isTurnPossible это уже не рефакторинг, но по сути этот метода не был 
написан раньше как раз из-за необходимости дублирования логики проверки, так что думаю его включение в пример законно.

Границы, как я понимаю, здесь явные. Правда не через интерфейсы (в случае TurnValidator), а через классы (методы).

Влияние на систему: команда обработки хода теперь не загружена не свойственной ей логикой. Она делает непосредственно ход (меняется поле, начисляются очки).
Единая обработка валидации хода как для принятия решения о ходе в команде UserTurnCommand.execute() так и в определении конца партии в GameEngine.isGameOver().  

Хотя, честно говоря, не оставляет чувство, что здесь какой-то корявый рефакторинг получился....

2. Второй пример из основного проекта. Но, правда кажется, не очень глубокий.

Здесь имеется следующая проблема, которую хочется починить в ходе рефакторинга: есть два процесса, получающие данные по объектам (клиенты/абоненты) - это этап проверки и этап выполнения.
Итог складывается в разные классы (поля у них частично разные) но с одним смыслом и именем check.akka.actors.find.RunDataFindResult и execute.akka.actors.find.RunDataFindResult.
Сообщение от check (CheckFindResponseMessage) читает CheckFindResponseMessageHandler, сообщение от этапа выполнения (FindObjectResponseMessage) читает ManagerActor.onFindObjectResponse.
В общем, разные пакеты и разные поля при одинаковых именах. Компилятор не мешает перепутать сообщения, если в импорте не тот пакет. Но при этом основное содержание у обех классов одинаковое: List<ObjectDescription>.
ObjectDescription хранит loadRequestId в дополнении к полю в классе из execute.akka.actors.find и используется в FindActor до того как заявка запущена. В execute.akka.actors.find ObjectDescription хранит уже runId, т.к. используется после запуска заявки в ManagerActor.   
В общем как-то все в кучу.   
```java
//check.akka.actors.find.
public class RunDataFindResult {
    private Long loadDataId;
    private Long runId;
    private Long bulkRequestId;
    private List<ObjectDescription> result;

    public RunDataFindResult() {
    }

    public RunDataFindResult(Long loadDataId, Long runId, Long bulkRequestId, List<ObjectDescription> result) {
        this.loadDataId = loadDataId;
        this.runId = runId;
        this.bulkRequestId = bulkRequestId;
        this.result = result;
    }
    //геттеры-сеттеры
}

//execute.akka.actors.find
public class RunDataFindResult {
    private Long loadDataId;
    private Long loadRequestId;
    private List<ObjectDescription> result;

    public RunDataFindResult() {
    }

    public RunDataFindResult(Long loadDataId, Long loadRequestId, List<ObjectDescription> result) {
        this.loadDataId = loadDataId;
        this.loadRequestId = loadRequestId;
        this.result = result;
    }
    //геттеры-сеттеры
}
//в check.akka.actors.find.
public class ObjectDescription {
    public final Long loadRequestId;

    public final Operation operation;

    public final Long loadDataId;

    public final Long subscriberId;

    public final Long customerId;

    public final String params;

    public final String account;

    public final String msisdn;

    public final String error;

    public final long tryNum;


    public ObjectDescription(Long loadRequestId, Operation operation, Long loadDataId, Long subscriberId, String msisdn,
                             Long customerId, String account, String params, String error, long tryNum) {
        //...
    }
}
//в execute.akka.actors.find
public class ObjectDescription {
    public final Long runId;

    public final Operation operation;

    public final Long loadDataId;

    public final Long subscriberId;

    public final Long customerId;

    public final String params;

    public final String account;

    public final String msisdn;

    public final String error;

    public final long tryNum;


    public ObjectDescription(Long runId, Operation operation, Long loadDataId, Long subscriberId, String msisdn,
                             Long customerId, String account, String params, String error, long tryNum) {
        //...
    }
}
```
Соответственно основная идея вроде такой: результат поиска клиента/абоннента разделён по этапам. Найденный объект теперь общий тип ObjectDescription, 
а все остальное в полях CheckFinder для этапа проверки и ExecuteFinder для этапа выполнения.
Сообщение от check больше не может передать свой результат в запуск (run), что было возможным ранее. CheckFindResponseMessage содержит CheckFinder с идентификатором заявки загрузки. FindObjectResponseMessage несёт ExecuteFinder с идентификатором запуска и заявки.
```java
//теперь в package ...service.find.
public class ObjectDescription {
    public final Operation operation;

    public final Long loadDataId;

    public final Long subscriberId;

    public final Long customerId;

    public final String params;

    public final String account;

    public final String msisdn;

    public final String error;

    public final long tryNum;

    public ObjectDescription(Operation operation, Long loadDataId, Long subscriberId, String msisdn,
                        Long customerId, String account, String params, String error, long tryNum) {
        this.operation = operation;
        this.subscriberId = subscriberId;
        this.customerId = customerId;
        this.params = params;
        this.loadDataId = loadDataId;
        this.account = account;
        this.msisdn = msisdn;
        this.error = error;
        this.tryNum = tryNum;
    }
    //...
}

public class CheckFinder {
    private final Long loadDataId;
    private final Long loadRequestId;
    private final List<ObjectDescription> objects;

    public CheckFinder(Long loadDataId, Long loadRequestId, List<ObjectDescription> objects) {
        this.loadDataId = loadDataId;
        this.loadRequestId = loadRequestId;
        this.objects = objects;
    }
}

public class ExecuteFinder {
    private final Long loadDataId;
    private final Long runId;
    private final Long bulkRequestId;
    private final List<ObjectDescription> objects;

    public ExecuteFinder(Long loadDataId, Long runId, Long bulkRequestId, List<ObjectDescription> objects) {
        this.loadDataId = loadDataId;
        this.runId = runId;
        this.bulkRequestId = bulkRequestId;
        this.objects = objects;
    }
}

//использование в FindActor (небольшая часть реализации). Для ManagerActor (второго места использования) плюс-минус аналогично, только с runId

/* Было
List<ObjectDescription> resultList = new ArrayList<>();
....
ObjectDescription result =
			new ObjectDescription(msg.loadRequestId, msg.operation, msg.loadDataId,
					subscriberId, null, customerId, null, buildParamString(msg.params,
					msg.operation.getDefaultParams(), msg.operation.getStepNumber(),
					msg.operation.getOperationType().getOperCode()), error, msg.tryNum);
                    resultList.add(result);
	...				
if (!resultList.isEmpty()) {
	// Список найденных клиентов не пуст (в зависимости от операции требуется может не
	// потребоваться поиск абонентов)
	getSender().tell(new CheckFindResponseMessage(0, null,
			CheckFindResponseMessage.aggregateFindResult(resultList)), getSelf());
}*/

Map<Long, CheckDiscovery> aggregated = new HashMap<>();
CheckFindResponseMessage.add(aggregated, msg.loadRequestId, new ObjectDescription(msg.operation, msg.loadDataId,
                             subscriberId, null, customerId, null, buildParamString(msg.params,
                             msg.operation.getDefaultParams(), msg.operation.getStepNumber(),
                            msg.operation.getOperationType().getOperCode()), error, msg.tryNum));
        }
        ...
if (!aggregated.isEmpty()) {
    // Список найденных клиентов не пуст (в зависимости от операции требуется может не
    // потребоваться поиск абонентов)
    getSender().tell(new CheckFindResponseMessage(0, null, aggregated), getSelf());
}
```
Границы здесь определяются разными типами сообщений для разных акторов.

Влияние на систему: Акторы CheckManagerActor и ManagerActor принимают каждый своё сообщение. Найденные объекты унифицированы, а идентификатор заявки на этапе проверке нельзя как-то случайно присвоить идентификатору запуска.
ManagerActor и CheckFindResponseMessageHandler перестают читать findResult.runId с каждой строки и берут его с "заголовка".

И, честно говоря, я опять думал, что рефакторинг данного кода будет простым и понятным, но такое впечатление, что путь получился такой... тернистый.  

3. Тут еще одно двусмысленное и немного запутанное место.

Краткое описание процесса: заявка(задача) может содержать несколько групповых операций. Политика выполнения второй (последующей) операции для клиента/абонента определяется параметром runTypeId в задаче:
```java
    /**
     * Политика поведения в случае ошибок: запускать следующую операцию только по успешным элементам
     */
    public static final Long RUN_TYPE_CONTINUE_SUCCESS = 1L;

    /**
     * Политика поведения в случае ошибок: запускать следующую по всем элементам (успешным и неуспешным).
     */
    public static final Long RUN_TYPE_CONTINUE_FULLSET = 3L;
    /**
     * Политика поведения в случае ошибок: отменять следующие операции
     */
    // эта политика не может быть реализована при переходе на сквозные поабонентские выполнения операций.
    public static final Long RUN_TYPE_STOP = 2L;
```
Учет и решение по статусам самих запусков (какие "строки" пройдут дальше) разбросано по нескольким местам:
```java
// здесь runTypeId преобразуется в строку статусов запусков ("3" или "3,4,5"). Неизвестная политика по-умолчанию 
// приравнивается к RUN_TYPE_CONTINUE_SUCCESS 
public class FindDoneByOperationCriteria {
    private static final String RUNDATA_STATUSES_SUCCESS = RunData.STATUS_DONE.toString();
    private static final String RUNDATA_STATUSES_DONE = String.join(",",
            RunData.STATUS_DONE.toString(), RunData.STATUS_ERR.toString(), RunData.STATUS_PARTLY_DONE.toString());
    //....

    /**
     * Обеспечивает отображение входного runTypeId (политика поведения) в готовый список статусов.
     * Пусть маппер myBatis получает параметры уже разжеванными. Список в IN все равно в виде bind не залезет.
     *
     */
    private void setupAuxData() {
        if (LoadRequest.RUN_TYPE_CONTINUE_SUCCESS.equals(this.runTypeId)) {
            this.statusesList = RUNDATA_STATUSES_SUCCESS;
        } else if (LoadRequest.RUN_TYPE_CONTINUE_FULLSET.equals(this.runTypeId)) {
            this.statusesList = RUNDATA_STATUSES_DONE;
        } else {
            // нельзя пустым оставлять.
            this.statusesList = RUNDATA_STATUSES_SUCCESS;
        }
    }
}

// в ReadActor происходит непосредственно запрос с этим критерием
public class ReadActor extends TemplateActor {
    private void onReadNewRunDataRequest(ReadNewRunDataRequestMessage msg) {
        try {
            // чтение новых данных выполнения (из предыдущей операции)
            List<RunData> data = runService.findDoneRunDataByOperation(
                    msg.sourceOperationId, msg.workOperation.getId(), msg.runTypeId,
                    configService.getLoadDataReadSize(), Locale.getDefault());
            //...
        } catch (...) {
            //...
        }
    }
}

// В ManagerActor на основании statusId подсчитывается статистика 
private void onUpdateRunDataResultResponse(UpdateRunDataResultResponseMessage msg) {

    // Счет статистики
    //...

    runInfo.getCurrentDetail().setProgressCount(runInfo.getCurrentDetail().getProgressCount() + 1);
    if (RunData.STATUS_DONE.equals(msg.runData.getStatusId())) {
        runInfo.getCurrentDetail().setSuccessCount(runInfo.getCurrentDetail().getSuccessCount() + 1);
    }
    if (RunData.STATUS_PARTIAL_PROCESSED.equals(msg.runData.getStatusId())) {
        runInfo.getCurrentDetail().setPartialSuccessCount(
                runInfo.getCurrentDetail().getPartialSuccessCount() + 1);
    }
    if (RunData.STATUS_ERR.equals(msg.runData.getStatusId())) {
        runInfo.getCurrentDetail().setErrorCount(runInfo.getCurrentDetail().getErrorCount() + 1);
    }

    requestRunDetailUpdateInfo(runInfo);

    processRun(runInfo);
}

// Кроме этого в RunData два описания для одного статуса. В ManagerActor используется первый, в критерии второй
public class RunData extends BulkRequestReferenced {
    //...
    /**
     * Ошибка
     */
    public static final Long STATUS_PARTIAL_PROCESSED = 5L;

    /**
     * Частично успешно
     */
    public static final Long STATUS_PARTLY_DONE = 5L;
    //...
}
```
Стало после рефакторинга (создаем два явно видимых enum):
```java
public enum RunDataStatus {
    CREATED(1L), IN_PROGRESS(2L), DONE(3L), ERR(4L), PARTLY_DONE(5L), OM_SET_ORDER_DONE(6L);
    public final Long id;
    
    RunDataStatus(Long id) { this.id = id; }
    
    public static RunDataStatus of(Long id) {
        for (RunDataStatus s : values()) if (s.id.equals(id)) return s;
        throw new IllegalArgumentException("Unknown run data status id: " + id);
    }
}

import static com.peterservice.oapi.subsloader.bulkrunner.domain.run.RunDataStatus.*;
public enum ContinuedPolicy {

    //успешные
    CONTINUE_SUCCESS(1L, EnumSet.of(DONE), true),
    //все
    CONTINUE_FULLSET(3L, EnumSet.of(DONE, ERR, PARTLY_DONE), false),
    //вообще не реализовано - это еще надо будет уточнить (по имеющейся логике работает как CONTINUE_SUCCESS)
    STOP(2L, EnumSet.of(DONE), true);

    public final Long runTypeId;
    private final Set<RunDataStatus> accepted;
    private final boolean intermediateFailureIsFinal;

    ContinuedPolicy(Long runTypeId, Set<RunDataStatus> accepted, boolean intermediateFailureIsFinal) {
        this.runTypeId = runTypeId;
        this.accepted = accepted;
        this.intermediateFailureIsFinal = intermediateFailureIsFinal;
    }
    public static ContinuedPolicy of(Long runTypeId) {
        for (ContinuedPolicy p : values()) if (p.runTypeId.equals(runTypeId)) return p;
        return CONTINUE_SUCCESS;
    }

    public boolean acceptedToContinue(RunDataStatus status) { return accepted.contains(status); }

    // Для учета ошибки на промежуточной операции в итогах запуска
    public boolean intermediateFailureIsFinal() { return intermediateFailureIsFinal; }

    //для SQL-запроса
    public String idsForSql() {
        return accepted.stream().map(s -> s.id.toString()).collect(Collectors.joining(","));
    }
}

// В FindDoneByOperationCriteria
private void setupAuxData() {
    this.statusesList = ContinuedPolicy.of(this.runTypeId).idsForSql();
}

//счетчики для ManagerActor
public class RunDetailCounter {

    private RunDetailCounter() {}
    public static void register(RunDetail detail, RunDataStatus status) {
        detail.setProgressCount(detail.getProgressCount() + 1);
        if (status == DONE) detail.setSuccessCount(detail.getSuccessCount() + 1);
        if (status == PARTLY_DONE) detail.setPartialSuccessCount(detail.getSuccessCount() + 1);
        if (status == ERR) detail.setErrorCount(detail.getSuccessCount() + 1);
    }
}

//теперь в ManagerActor
RunDetailCounter.register(runInfo.getCurrentDetail(), RunDataStatus.of(msg.runData.getStatusId()));

// а в RequestTracker вместо
// if (Objects.equals(rti.getRunInfo().getRun().getRequest().getRunTypeId(), LoadRequest.RUN_TYPE_CONTINUE_SUCCESS)) {
//        errorInstancesCount += msg.runDetailCombinedCounts.stream()
//                    .map(RunDetailCombinedCounts::getFailedIntermediateCount)
//                    .reduce(0L, Long::sum);
//}
if (ContinuedPolicy.of(rti.getRunInfo().getRun().getRequest().getRunTypeId()).intermediateFailureIsFinal()) {
        errorInstancesCount += msg.runDetailCombinedCounts.stream()
                    .map(RunDetailCombinedCounts::getFailedIntermediateCount)
                    .reduce(0L, Long::sum);
}
```
Ну и статус STATUS_PARTIAL_PROCESSED убрал из использования (можно удалить или пометить как депрекейтед).

Граница явная - два enum, и политика, зависящая от статуса. Ограничение для SQL-запроса (IN (${statusesList})) также идет теперь идет из enum.

Влияние на систему: ReadActor (через SQL-критерий), счётчики в ManagerActor, итоги в RequestTracker используют одно определение 
строки, идущих дальше. Добавление новой политики или статуса будет в одном месте (не нужен поиск по строкам типа "3,4,5")

По тестам для последнего случая - тесты для класса ContinuedPolicy. Проверяем соответствие наполнения политики тем действиям, которые ожидаются от нее для определения попадания записи вследующую операцию и в итоги запуска. 
```java
class ContinuedPolicyTest {

    @Test
    public void fullsetContinuedPolicy() {
        ContinuedPolicy policy = ContinuedPolicy.of(LoadRequest.RUN_TYPE_CONTINUE_FULLSET);
        RunDetail stage = new RunDetail();
        RunDetailCounter.register(stage, PARTLY_DONE);
        assertTrue(policy.acceptedToContinue(PARTLY_DONE), "PARTLY_DONE идет дальше");
        assertEquals(1L, stage.getPartialSuccessCount());
        assertEquals(0L, stage.getSuccessCount());
        assertEquals(0L, stage.getErrorCount());
        assertFalse(policy.intermediateFailureIsFinal(), "ошибка промежуточной операции не фиксируется");
    }

    @Test
    public void successOnlyContinuedPolicy() {
        ContinuedPolicy policy = ContinuedPolicy.of(LoadRequest.RUN_TYPE_CONTINUE_SUCCESS);
        RunDetail stage = new RunDetail();
        RunDetailCounter.register(stage, ERR);
        assertFalse(policy.acceptedToContinue(ERR), "ошибочная запись в следующую операцию не попадает");
        assertEquals(1L, stage.getErrorCount());
        assertTrue(policy.intermediateFailureIsFinal(), "ошибка промежуточной операции фиксируется");
    }

    @Test
    public void notFinalStatusesNotAcceptedToContinue() {
        for (ContinuedPolicy policy : ContinuedPolicy.values()) {
            assertFalse(policy.acceptedToContinue(CREATED), policy + " / CREATED");
            assertFalse(policy.acceptedToContinue(IN_PROGRESS), policy + " / IN_PROGRESS");
        }
    }

    @Test
    public void unknownRunTypeIdIsEqualToSuccessOnlyPolicy() {
        assertEquals(ContinuedPolicy.CONTINUE_SUCCESS, ContinuedPolicy.of(99L));
        assertEquals(ContinuedPolicy.CONTINUE_SUCCESS, ContinuedPolicy.of(null));
    }

    // ограничение для IN (${statusesList}) это суть сериализация той же политики,
    // которой пользуется ReadActor (теперь это не строка типа "3,4,5")
    @Test
    public void sqlCriteriaIsASerializationOfTheSamePolicy() {
        for (ContinuedPolicy policy : ContinuedPolicy.values()) {
            FindDoneByOperationCriteria criteria =
                    new FindDoneByOperationCriteria(3L, 1L, policy.runTypeId, 1000);
            Set<String> inList = new HashSet<>(Arrays.asList(criteria.getStatusesList().split(",")));
            for (RunDataStatus status : RunDataStatus.values()) {
                assertEquals(policy.acceptedToContinue(status),
                        inList.contains(status.id.toString()), policy + " / " + status);
            }
        }
    }

    @Test
    public void statusIdsMatchLegacyConstantsStoredInDatabase() {
        assertEquals(RunData.STATUS_DONE, DONE.id);
        assertEquals(RunData.STATUS_ERR, ERR.id);
        assertEquals(RunData.STATUS_PARTLY_DONE, PARTLY_DONE.id);
        assertEquals(RunData.STATUS_PARTIAL_PROCESSED, PARTLY_DONE.id); // может уже и не нужно проверять
    }
}
```