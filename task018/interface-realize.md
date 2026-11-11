### Призрачные состояния
1. Для добавления записи в кэш используется дополнительный флаг this.trackFlag:

```java
synchronized(this.rundataCache) {
            logger.debug("ensure start for list count={}", asMap.size());
            this.trackFlag.set(true);
            if (!asMap.isEmpty()) {
                // поход в dao (основной повод для экономии) и осторожное вливание в кеш.
                List<RunData> res = this.runDataDao.findListByIds(asMap.values());
                if (res != null && !res.isEmpty()) {
                    logger.debug("ensure got from dao count={}", res.size());
                    for (RunData resRunData : res) {
                        try {
                            //помещается в кеш только если ее сейчас там нет, и не было с момента установки trackFlag.
                            // Таким образом не допускается заливание в кеш неактуальных данных (отловлено на НТ и на
                            // быстрых скриптах - когда после дао-шного селекта поштучный доступ успевал поднять в кеш
                            // рандату и выполнить ее, после чего данный ensure заливал в кеш уже устаревшую версию
                            // рандаты)
                            this.rundataCache.get(resRunData.getId(), () -> {
                                if (!this.removesTracked.containsKey(resRunData.getId())) {
                                    return resRunData;
                                } else {
                                    //при возврате null тоже ругнется, но для явного прочтения лучше пусть throw.
                                    // оно превратится в ExecutionException принудительно самим кешем.
                                    throw new AppException("filtered by removesTracked");
                                }
                            });
                            resRunData.setRunnerId(this.runnerInstanceId);
                        } catch (ExecutionException | UncheckedExecutionException e) {
                            logger.debug(String.format("exception (possibly synthetic) when ensure rundataId=%s",
                                            resRunData.getId()), e);
                        }
                    }
                } else {
                    logger.warn("ensure unexpectedly got empty dao results for count={}", asMap.size());
                }
            }
            this.trackFlag.set(false);
            this.removesTracked.clear();
        }
```
Проблема описывается в комментарии: в случае быстро выполненных скриптов данные в кэше без данного флага затирались старой версией рандаты. 
Т.е. имеем призрачное состояние-правило "пока флаг поднят, не записываем в кеш".

2. Здесь, если я не ошибаюсь в трактовке, призрачным состоянием является сортировка в очереди continueReadyQ - опять о проблеме говорит комментарий в коде.  
```java
// разбор задач.
    public ScenarioExecutionState pollNextContinueTask() {
        ScenarioExecutionState taskCandidate = continueReadyQ.peek();
        if (taskCandidate == null ||
                (taskCandidate.getScenarioAsyncProcState() != null
                        && taskCandidate.getScenarioAsyncProcState().getCallbackDescription() != null)) {
            taskCandidate = continueReadyQ.poll();
        } else {
            // если на очереди не-коллбэковое исполнение, то необходимо учитывать рейты.
            // !!! Если изменится сортировка в очереди continueReadyQ (не всегда колбэковые будут в голове),
            // то придется что-то выдумывать.
            if (this.resourceSlot.getRateQuotaActiveBucket() != null) {
                if (this.resourceSlot.getRateQuotaActiveBucket().spendOneToken()) {
                    taskCandidate = continueReadyQ.poll();
                } else {
                    taskCandidate = null;
                }
            } else {
                log.error("recoverable error in pollNextContinueTask - activeBucket is null in slot={}", resourceSlot);
                taskCandidate = null;
            }
        }
        return taskCandidate;
    }
```

### Погрешности/неточности

1. В методе obtainTokenDirect для HAS время жизни недоступно, поэтому вешается время жизни 900 сек. Можно подумать, что это обещание, что сессия HAS живет 900 сек. 
```java
if (smartResponse.getCode() == 200) {
                Map<String, Object> bodyContent = (Map<String, Object>) smartResponse.getContentObject();
                //без анализа заголовков, просто свитч (имена атрибутов все равно отличаются, не перепутаем)
                if (bodyContent.containsKey("access_token")) {
                    richToken = new RichToken(bodyContent.get("access_token").toString());
                    richToken.setObtainMoment(obtainMoment);
                    Long expireSeconds = ((Number) bodyContent.get("expires_in")).longValue();
                    richToken.setExpireMoment(obtainMoment.plusSeconds(expireSeconds));
                } else if (bodyContent.containsKey("SESSION_ID")) {
                    // root-элемент (SELFCARE) отбрасывается маппером, сохранить его без экзотических
                    // плясок невозможно. В принципе в случае ошибки там будет на корневом уровне OPEN_SESSION с
                    // внутренностями, и точно не будет SESSION_ID.
                    Object sessionId = bodyContent.get("SESSION_ID");
                    if (sessionId != null) {
                        richToken = new RichToken(sessionId.toString());
                        richToken.setObtainMoment(obtainMoment);
                        //в случае с HAS время жизни недоступно, берем от фонаря. Ошибка в любую сторону некритична.
                        // Либо просто чуть чаще будем токен обновлять (HAS высокопроизводительный, справится ;)), либо
                        // просто рефреш будет как и раньше, только по факту http 401.
                        richToken.setExpireMoment(obtainMoment.plusSeconds(900));
                    } else {
                        somethingWrong = true;
                    }
                } else {
                    somethingWrong = true;
                }
            } else {
                somethingWrong = true;
            }
```
Можно описать спецификацию следующим образом:
```java
/**
 * Возвращаем токен со следующим временем жизни:
 * если в ответе указано значение expires_in, то expireMoment берем оттуда
 * если данного значения нет, то берется произвольный момент в будущем из расчета, что оно не будет слишком маленьким, 
 * чтобы не делать аутентификацию на каждый вызов, но и не слишком большим.
 * Обновление определяется по expireMoment или по отказу авторизации (ошибка 401).
 * Выбранные 900 сек для HAS является компромиссным значением, а не специфицированным. 
 */
private RichToken obtainTokenDirect(...) {
    //...
}
```

2. В дао зашито ограничение конкретной СУБД (Oracle) для выбора размера пачки.
```java
public class MixedRunDataDao implements RunDataDao {
    private final int BATCH_SIZE = 100;
    //...
    public List<RunData> findListByIds(Collection<Pair<Long, Long>> runDataPairIds) throws DataAccessException {
        //split input runDataPairIds into safe sub-lists, load in cycle and return concatenated result.
        //(1000 elements - according to oracle IN clause limitations)
        int batchSize = 1000;

        //....
    }
}
```
Если вынести данное значение в конфигурацию, то можно определять размер пачки не привязываясь к ограничению конкретной БД:
```java
/**
 * Получаем RunData по парам (runDataId, requestId). Разбиваем на пачки с размером batchLimit из конфигурации.
 * По умолчанию 1000 элементов (ограничение IN clause в Oracle) 
 * 
 */
public List<RunData> findListByIds(Collection<Pair<Long, Long>> runDataPairIds) throws DataAccessException {
    int batchSize = getBatchSizeFromConfig();
    //....
}
```

3. Для определения задержки перед переповтором для ряда типов ошибок используются жестко зашитые значения. И кстати в целом ряде обработчиков используется не данный шаблон, а тупо копия кода с установкой задержки (хотя в часть из них этот шаблон импортируется).
Т.е. тут в общем фактический смысл спецификации "при небизнес ошибках сделать повтор запроса через какое-то время, которое должно быть большим при ошибках доступа к ресурсу".
Реализация фактически цементирует конкретные значения, которые можно изменить только правкой кода (нельзя подстроить под стенд или сменить на лету) 
```java
public class CheckErrorTemplate<R> {
    public void handleError(CheckContext checkContext, R response, Throwable error, ActorRef worker,
                            AbstractActor actor,
                            BusinessErrorHanldler businessErrorHanldler,
                            RetryMessageBuilder<R> retryMessageBuilder) {
        if (ErrorUtil.isBusinessException(error)) {
            if (checkContext != null) {
                checkContext.decrementWaitingCount();
            }
            businessErrorHanldler.businessError(checkContext, (BusinessException) error);
        } else {
            FiniteDuration delay = ErrorUtil.isResourceException(error) ?
                    Duration.create(30, TimeUnit.SECONDS) :
                    Duration.create(500, TimeUnit.MILLISECONDS);

            actor.getContext().system().scheduler().scheduleOnce(delay, worker,
                    retryMessageBuilder.buildRequest(response),
                    actor.getContext().dispatcher(), actor.getSelf());
        }
    }
}
```
Можно также поставить настройку данных значений через конфиг. И вообще, реализовать установку задержки через политику (по типу ошибки).
```java
/**
 * Обработка ошибки.
 * Бизнес-ошибки передаются в businessErrorHanldler as is, без повторов
 * Для прочих ошибок планируется повтор через задержку, устанавливаемую политикой retryDelayPolicy в зависимости от типа ошибки.
 * По умолчанию используется следующие задержки: ресурсная ошибка - 30 сек; другая ошибка - 500 мс
 */
public void handleError(CheckContext checkContext, R response, Throwable error, ActorRef worker,
                            AbstractActor actor,
                            BusinessErrorHanldler businessErrorHanldler,
                            RetryMessageBuilder<R> retryMessageBuilder,
                            RetryDelayPolicy retryDelayPolicy) {
    FiniteDuration delay = retryDelayPolicy.delayFor(error);
}

//для default
public void handleError(CheckContext checkContext, R response, Throwable error, ActorRef worker,
                        AbstractActor actor,
                        BusinessErrorHanldler businessErrorHanldler,
                        RetryMessageBuilder<R> retryMessageBuilder) {
    handleError(..., RetryDelayPolicy.DEFAULT);
}
```

### Интерфейс не проще реализации
1. Криптографический API. Здесь требуется явное задание криптографического алгоритма, режима работы, ключа, других настроек.
Скрывать это за значениями по-умолчанию чревато уязвимостями. Таким образом интерфейс включает в себя "знания" о криптографических протоколах и формальная спецификация будет сложнее кода.
2. API для управления транзакциями. Сложность интерфейса тут обуславливается необходимостью следовать требованиям ACID, учетом уровней изоляции, таймаутов и т.п.
Простой begin() приведет к неявному поведению и ошибкам во время выполнения, требуется что-то явно сложнее вроде begin(IsolationLevel level, Duration timeout)
3. Интерфейсы или методы со сложными правилами валидации. Например, метод принимает несколько параметров, а результат (или его валидность) зависит от их определенных значений/сочетаний. 
Условно, в реализации это может быть решено простым кодом if-else или применением формул, а описание правил, взаимозависимость параметров может быть длинным и сложным.

Да, интерфейсы можно сделать более наглядными с помомощью подходящих типов данных. Мы не упрощаем интерфейс, а перекладываем часть заботы о валидности на систему типов. Т.е. наше любимое "сделать недопустимые состояния невыразимыми".
Это избавление от примитивов (Money вместо long, PositiveInteger вместо int), четкие enum вместо String. Т.е. интерфейс получает не только говорящие имена параметров, но и говорящие типы, что сделает его и более наглядным и более безопасным. Особенно, когда он и так сложный.   