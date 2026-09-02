### Избавление от точек генерации исключений
Вообще в который раз уже попадаю в "ловушку" типа: "а, все понятно, это легко сделать".
На этот раз решил с лету улучшить код создания объекта GameField из игры "три-в-ряд", которую делал в рамках практического курса по ООАП.
Было вот так:
```java
//класс GameField
public GameField() {
    this(CommonConstants.MAX_SIZE);
}

public GameField(int size) throws IllegalArgumentException {
        elements = new FieldElementCollection(size);
        this.fieldSize = size;
    }

//класс FieldElementCollection
public FieldElementCollection(int size) {
    if (size < MIN_SIZE || size > MAX_SIZE) {
        throw new IllegalArgumentException(
                "Размер поля должен быть от " + MIN_SIZE + " до " + MAX_SIZE + ", запрошено: " + size
        );
    }
    this.size = size;
    elements = new GameFieldElement[size][size];
    for (int i = 0; i < size; i++) {
        for (int j = 0; j < size; j++) {
            elements[i][j] = GameFieldElement.createEmptyElement();
        }
    }
}
```

Я решил - зачем исключение, сейчас все легко порешаем. Создание поля происходит исключительно программно, пользователь на это не влияет и будет странно, если в рантайме во время игры он получит такое сообщение.
И сразу столкнулся с вопросами: а как лучше реализовать - совсем без исключения разработчик не получит обратную связь о том, что что-то пошло не так. С исключением - пользователь игры в лучшем случае получить странную ошибку при попытке начать очередной уровень игры, а в худшем случае вообще не сможет ее продолжить.
Т.е. в данном случае пришлось выбирать из двух зол (победил конечный пользователь). В итоге результат формально подпадает вообще сразу под все три варианта - и точка исключения убирается, и конструктор без параметров убран, и тип специальный создан.
Вообще, при первой реализации только волк был сыт, а чтоб хотя бы немножко и некоторые овцы были целы, можно в рамках "информирования" отправлять в лог информацию о попытке создать поле некорректного размера.
Хотя может я для данного кода и перестарался. Фактически я сейчас ведь не знаю, откуда я могу получить некорректный размер size. 
Разработчик (ну, я) могу создавать следующий уровень со случайным размером - в этом случае будь добр при генерации ограничиться диапазоном CommonConstants.MIN_SIZE-MAX_SIZE. Если же я планирую создать какой-то конкретный уровень с определенным размером, то мне как разработчику по идее сразу надо будет узнать, что я прям сильно не прав.  
И вообще, не получается ли здесь "преждевременной оптимизации", вот в чем вопрос.
Итоговый результат:
```java
public enum FieldSizeStatuses {
    SUCCESS_NORMAL, WRONG_UP_TO_MIN, WRONG_DOWN_TO_MAX
}

public record FieldSize(int value, FieldSizeStatuses status) {
    public static FieldSize of(int value) {
        if (value < CommonConstants.MIN_SIZE) {
            System.out.println("WARNING: увеличено до минимального значения");
            return new FieldSize(CommonConstants.MIN_SIZE, FieldSizeStatuses.WRONG_UP_TO_MIN);
        }
        if (value > CommonConstants.MAX_SIZE) {
            System.out.println("WARNING: уменьшено до максимального значения");
            return new FieldSize(CommonConstants.MAX_SIZE, FieldSizeStatuses.WRONG_DOWN_TO_MAX);
        }
        return new FieldSize(value, FieldSizeStatuses.SUCCESS_NORMAL);
    }
}

//конструктор без параметров удален (хотя, честно говоря, наверное в данном случае можно было не удалять, т.к. он вызывал параметризованный конструктор и указывал значение size)
public GameField(FieldSize fieldSize) {
        elements = new FieldElementCollection(fieldSize);
        this.fieldSize = fieldSize.value();
    }

//теперь исключение не нужно - размер гарантированно корректный
public FieldElementCollection(FieldSize size) {
    this.size = size.value();
    elements = new GameFieldElement[this.size][this.size];
    for (int i = 0; i < this.size; i++) {
        for (int j = 0; j < this.size; j++) {
            elements[i][j] = GameFieldElement.createEmptyElement();
        }
    }
}

//вызов теперь может быть, например таким
public void start() {
    if (getGameStatus() != GameStatuses.NOT_STARTED) return;
    FieldSize fieldSize = FieldSize.of(CommonConstants.MAX_SIZE); //тут можно и генерацию случайного размера, в принципе можно даже у пользователя запросить размер. 
    field = new GameField(fieldSize);
    GameCommand command = new StartGameCommand(this);
    command.execute();
    gameStatus = GameStatuses.USER_TURN_WAIT;
}
```
По формальным признакам отнес этот пример к первому типу улучшений (избавление от точек генерации исключений).

#### Второй пример:
Здесь мы имеем 
```java
public class AuthRequest {
    public final static String TOKEN_STUB_BUILDER = "TOKEN_STUB_BUILDER";
    public final static String DIRECT_TOKEN_BUILDER = "DIRECT_TOKEN_BUILDER";
    public final static String CLIENT_CREDENTIALS_BUILDER = "CLIENT_CREDENTIALS_BUILDER";
    public final static String EXCHANGE_TOKEN_BUILDER = "EXCHANGE_TOKEN_BUILDER";

    //...
    
    /**
     * Creates builder regarding to auth strategy.
     *
     */
    public static AuthRequestBuilder builder(String builderType) {
        switch (builderType) {
            case TOKEN_STUB_BUILDER -> {
                return new TokenStubAuthRequestBuilder();
            }
            case DIRECT_TOKEN_BUILDER -> {
                return new ApiTokenAuthRequestBuilder();
            }
            case CLIENT_CREDENTIALS_BUILDER -> {
                return new ClientCredentialsRequestBuilder();
            }
            case EXCHANGE_TOKEN_BUILDER -> {
                return new TokenExchangeRequestBuilder();
            }
            default -> {
                throw new IllegalArgumentException("unsupported builder type=" + builderType);
            }
        }
    }
    //...
}
// один из вызовов:
AuthRequest.builder(useTokenStubForAuth ? AuthRequest.TOKEN_STUB_BUILDER :
        AuthRequest.DIRECT_TOKEN_BUILDER)
                    .protocol(protocol)
                    .oapiHost(addressStr.toString())
                    //...
```
Здесь просто набор констант в классе и сейчас вызов билдера в коде идет с помощью этих констант, но никто не мешает вызвать данный метод с любым "мусором" и получить уже в рантайме ошибку.
Идея здесь - сделать enum и switch expression:
```java
public enum AuthBuilderType {
    TOKEN_STUB_BUILDER,
    DIRECT_TOKEN_BUILDER,
    CLIENT_CREDENTIALS_BUILDER,
    EXCHANGE_TOKEN_BUILDER
}

// константы в классе теперь не нужны
public static AuthRequestBuilder builder(AuthBuilderType builderType) {
    return switch(builderType) {
        case TOKEN_STUB_BUILDER ->  new TokenStubAuthRequestBuilder();
        case DIRECT_TOKEN_BUILDER -> new ApiTokenAuthRequestBuilder();
        case CLIENT_CREDENTIALS_BUILDER -> new ClientCredentialsRequestBuilder();
        case EXCHANGE_TOKEN_BUILDER -> new TokenExchangeRequestBuilder();
    };
}

//вызов
authRequest = AuthRequest.builder(useTokenStubForAuth ? AuthBuilderType.TOKEN_STUB_BUILDER : 
        AuthBuilderType.DIRECT_TOKEN_BUILDER)
                    .build();
```
Switch-выражение не даст ни забыть обработать новое значение в enum AuthBuilderType (при компиляции получаю ошибку выражения: "'switch' expression does not cover all possible input values"),
ни обработать несуществующее значение. Ну и вызвать теперь можно только с законными значениями из enum.

### Отказ от дефолтных конструкторов без параметров
Исправлял вот такой класс (частично убрал геттеры/сеттеры, чтобы не слишком много лишнего текста, а так там они на все поля):
```java
public class CheckContext {
    private CheckState checkState;
    private Locale locale;
    private Long loadRequestId;
    private LoadRequest loadRequest;
    private Operation firstOperation;
    private int subStep;
    private long waitingCount;
    private Long minLoadDataId;
    private Long maxRunDataLoadId;

    public Long getLoadRequestId() {
        return loadRequestId;
    }

    public void setLoadRequestId(Long loadRequestId) {
        this.loadRequestId = loadRequestId;
    }

    public int getSubStep() {
        return subStep;
    }

    public void setSubStep(int subStep) {
        this.subStep = subStep;
    }

    public void incrementSubStep() {
        this.subStep++;
    }

    //... тут еще остальные геттеры/сеттеры

    public long getWaitingCount() {
        return waitingCount;
    }

    public void setWaitingCount(long waitingCount) {
        this.waitingCount = waitingCount;
    }

    public void incrementWaitingCount() {
        this.waitingCount++;
    }

    public void decrementWaitingCount() {
        this.waitingCount--;
    }

}

//одинаковое создание объекта из трех мест:
// создание контекста проверки запроса загрузки
checkContext = new CheckContext();
checkContext.setLoadRequestId(new Long(msg.loadRequest.getId()));
checkContext.setLoadRequest(msg.loadRequest);
checkContext.setCheckState(CheckState.DEFINING_FIRST_OPERATION);
checkContext.setLocale(Locale.getDefault());
checkContext.setSubStep(0);
checkContext.setWaitingCount(0);
context.checkReadActor.tell(new FindFirstOperationRequestMessage(msg.loadRequest.getId(), null,
checkContext.getLocale()), mgr.getSelf());
```
Тут явно видны следующие проблемы, дающие потенциальные ошибки при создании объекта этого класса разработчиком:
1. По коду явно видно, что часть параметров обязательные и не изменяемые (к сожалению обеспечить то, что они есть в объекте msg мы не можем, поэтому там дальше есть проверка на null, но переложить "что есть" мы обязаны). Так что часть параметров объявляются final и попадают в параметризованный конструктор.
2. Два поля при инициализации обязаны быть с конкретным значением (subStep и waitingCount = 0), а меняются они только явным вызовом increment (+decrement для waitingCount). 
Тут кстати для waitingCount своя проблема - парность increment/decrement, чтобы разработчик не забыл вызвать increment на событие, но тут я не дорешал, слишком глубоко копать надо.

Исправление:
```java
package com.peterservice.oapi.subsloader.bulkrunner.service.check.akka.actors.state;

import com.peterservice.oapi.subsloader.bulkrunner.domain.source.LoadRequest;
import com.peterservice.oapi.subsloader.bulkrunner.domain.source.Operation;

import java.util.Locale;

/**
 * Created by Paul on 18.05.2016.
 */
public class CheckContext {
    //обязательные и неизменяемые поля оформляем final
    private final Locale locale;
    private final Long loadRequestId;
    private final LoadRequest loadRequest;
    private final Operation firstOperation;
    private CheckState checkState;
    private int subStep;
    private long waitingCount;
    private Long minLoadDataId;
    private Long maxRunDataLoadId;

    //явный параметризованный конструктор, который не даст забыть назначить объекту обязательные поля
    //а для subStep и waitingCount задаст корректные инициализационные значения
    public CheckContext(Locale locale, Long loadRequestId, LoadRequest loadRequest, Operation firstOperation) {
        this.locale = locale;
        this.loadRequestId = loadRequestId;
        this.loadRequest = loadRequest;
        this.firstOperation = firstOperation;
        this.subStep = 0;
        this.waitingCount = 0;
    }

    public CheckState getCheckState() {
        return checkState;
    }

    public void setCheckState(CheckState checkState) {
        this.checkState = checkState;
    }

    public Locale getLocale() {
        return locale;
    }
    
    //убранны не нужные теперь сеттеры для новоиспеченных final параметров
    //public void setLocale(Locale locale)
    //public void setLoadRequestId(Long loadRequestId)
    //public void setLoadRequest(LoadRequest loadRequest)
    //public void setFirstOperation(Operation firstOperation)
    
    //убраны сеттеры для полей, значения которых управляются другими методами
    //public void setSubStep(int subStep)
    //public void setWaitingCount(long waitingCount)

    public Long getLoadRequestId() {return loadRequestId;}
    public LoadRequest getLoadRequest() {return loadRequest;}
    public int getSubStep() {return subStep;}
    public long getWaitingCount() {return waitingCount;}
    public void incrementWaitingCount() {this.waitingCount++;}
    public void decrementWaitingCount() {this.waitingCount--;}
    public Operation getFirstOperation() {return firstOperation;}
    public Long getMinLoadDataId() {return minLoadDataId;}
    public void setMinLoadDataId(Long minLoadDataId) {this.minLoadDataId = minLoadDataId;}
    public Long getMaxRunDataLoadId() {return maxRunDataLoadId;}
    public void setMaxRunDataLoadId(Long maxRunDataLoadId) {this.maxRunDataLoadId = maxRunDataLoadId;}
}
```
Второй пример (с таким комментарием в коде):
```java
/**
 * пара идентификаторов для оптимизации чтения LoadData.
 * Вычисляются отдельным предварительным запросом, а далее minLoadDataId сдвигается к последнему элементу списка,
 * maxRunDataLoadId не трогается.
 */
public class ReadLoadDataPointer {
    private Long minLoadDataId;
    private Long maxRunDataLoadId;

    public Long getMinLoadDataId() {
        return minLoadDataId;
    }

    public void setMinLoadDataId(Long minLoadDataId) {
        this.minLoadDataId = minLoadDataId;
    }

    public Long getMaxRunDataLoadId() {
        return maxRunDataLoadId;
    }

    public void setMaxRunDataLoadId(Long maxRunDataLoadId) {
        this.maxRunDataLoadId = maxRunDataLoadId;
    }
}

//типичный вызов (вызывается из двух мест)
public ReadLoadDataPointer readLoadDataPointers(Long requestId, Long operationId) throws AppException {
    try {
        ReadLoadDataPointer p = new ReadLoadDataPointer();
        p.setMinLoadDataId(loadDataDao.findLoadDataPointer(requestId, operationId));
        p.setMaxRunDataLoadId(loadDataDao.findRunDataPointer(requestId, operationId));
        return p;
    } catch (SQLException e) {
        if (ErrorUtil.isResourceException(e)) {
            throw new ResourceUnavailableException("Error finding loadData pointers", e);
        } else {
            throw new AppException("Error finding loadData pointers", e);
        }
    }
}
```
Исходя из описания и дальнейшей работы с объектом данного класса можно улучшить данный код например следующим образом:
```java
public class ReadLoadDataPointer {
    private Long minLoadDataId;
    private final Long maxRunDataLoadId;

    public ReadLoadDataPointer(Long minLoadDataId, Long maxRunDataLoadId) {
        this.minLoadDataId = minLoadDataId;
        this.maxRunDataLoadId = maxRunDataLoadId;
    }

    public Long getMinLoadDataId() {
        return minLoadDataId;
    }

    public void setMinLoadDataId(Long minLoadDataId) {
        this.minLoadDataId = minLoadDataId;
    }

    public Long getMaxRunDataLoadId() {
        return maxRunDataLoadId;
    }
    
    //для неизменяемого поля сеттер более не нужен
    /*public void setMaxRunDataLoadId(Long maxRunDataLoadId) {
        this.maxRunDataLoadId = maxRunDataLoadId;
    }*/
}

//теперь вызов такой:
public ReadLoadDataPointer readLoadDataPointers(Long requestId, Long operationId) throws AppException {
    try {
        Long minLoadDataId = loadDataDao.findLoadDataPointer(requestId, operationId);
        Long maxRunDataLoadId = loadDataDao.findRunDataPointer(requestId, operationId);
        return new ReadLoadDataPointer(minLoadDataId, maxRunDataLoadId);
    } catch (SQLException e) {
        if (ErrorUtil.isResourceException(e)) {
            throw new ResourceUnavailableException("Error finding loadData pointers", e);
        } else {
            throw new AppException("Error finding loadData pointers", e);
        }
    }
}
```
Исправления:
- параметризованный конструктор с гарантированным заполнением требуемых в дальнейшем коде полей класса
- объявлено final поле класса изменение которого не предусмотрено (соответственно убран сеттер)
 
### Убираем примитивные типы данных
Нашел в нашем коде несколько таких сеттеров:
```java
public void setQueryTimeout(int queryTimeout) {
        if (queryTimeout < 0) {
            throw new IllegalArgumentException("queryTimeout must be non-negative integer. Parameter value is: " + queryTimeout);
        }
        this.queryTimeout = queryTimeout;
    }

public void setBatchSize(int batchSize) {
    if (batchSize < 0) {
        throw new IllegalArgumentException("batchSize must be positive integer. Parameter value is: " + batchSize);
    }
    this.batchSize = batchSize;
}
```
Идея в том, чтобы сделать тип PositiveInteger и использовать сразу его, чтобы не засорять проверками множество подобных сеттеров:
```java
public record PositiveInteger(int value) {
    public PositiveInteger {
        if (value < 0) {
            throw new IllegalArgumentException("Value must be positive integer. Parameter value is: " + value);
        }
    }
}

public void setQueryTimeout(PositiveInteger queryTimeout) {
    this.queryTimeout = queryTimeout.value();
}

public void setBatchSize(PositiveInteger batchSize) {
    this.batchSize = batchSize.value();
}
```
Второй пример:
```java
void markError(Long requestId, Collection<Long> runDataIds, Long runDataStatusId,
                   Collection<Long> loadDataIds, Long loadDataStatusId,
              String errorMessage, String errorCode) throws SQLException;
```
Здесь в метод маркировки ошибочных записей передаются статусы runDataStatusId и loadDataStatusId. Оба параметра Long, поэтому легко перепутать. 
FK в таблицах не помогут, т.к. значения валидны. Так что потенциально возможно тихое ошибочное проставление статусов.
В качестве решения - явные enum:
```java
public enum RunDataStatus {
    CREATED(1), IN_PROGRESS(2), DONE(3), ERR(4), PARTLY_DONE(5), OM_SET_ORDER_DONE(6);
    private final long id;

    RunDataStatus(long id) {this.id = id;}

    public long getId() {return id;}
}

public enum LoadDataStatus {
    LOADED(1), LOAD_PROGRESS(2), LOAD_ERR(3), CHECKING(4), CHECKED(5), CHECK_ERR(6);
    private final long id;

    LoadDataStatus(long id) {this.id = id;}

    public long getId() {return id;}
}

void markError(Long requestId, Collection<Long> runDataIds, RunDataStatus runDataStatus,
                   Collection<Long> loadDataIds, LoadDataStatus loadDataStatus,
                   String errorMessage, String errorCode) throws SQLException;
```
Теперь при вызове markError статусы указываются из явных enum и перепутать статусы разных таблиц уже не получится. 

Ну а вообще, кроме разобранных примеров в коде очень много потенциальных проблем нашел из этих трех видов...