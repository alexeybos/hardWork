1. Первой попалась на глаза такая строка:
```java
 rd.setProgressCount(rdCountsOpt.get().getOpSuccessCount() + rdCountsOpt.get().getOpErrorCount());
```
Но на самом деле она является частью блока подобных строк:
```java
if (rdCountsOpt.isPresent()) {
        rd.setCount(rdCountsOpt.get().getOpCount());
        rd.setSuccessCount(rdCountsOpt.get().getOpSuccessCount());
        rd.setProgressCount(rdCountsOpt.get().getOpSuccessCount() + rdCountsOpt.get().getOpErrorCount());
        rd.setErrorCount(rdCountsOpt.get().getOpErrorCount());
        rd.setPartialSuccessCount(rdCountsOpt.get().getOpPartialSuccessCount());
}
```
Я привел весь блок, т.к. мне кажется, что в данном случае рефакторинг будет наглядней - кол-во успешных/неуспешных записей используется в нескольких строках.
Результат (стало):
```java
if (rdCountsOpt.isPresent()) {
    RunDetailCombinedCounts optCounts = rdCountsOpt.get();
    long successCnt = optCounts.getOpSuccessCount();
    long errorCnt = optCounts.getOpErrorCount();
    rd.setCount(optCounts.getOpCount());
    rd.setSuccessCount(successCnt);
    rd.setProgressCount(successCnt + errorCnt);
    rd.setErrorCount(errorCnt);
    rd.setPartialSuccessCount(optCounts.getOpPartialSuccessCount());
}
```

2. Здесь получаем id последней записи в списке загруженных строк. 
```java
checkContext.setMinLoadDataId(msg.loadDataList.get(msg.loadDataList.size() - 1).getId());
```
Данная строка используется в двух местах, так что имеет смысл выделить получение в отдельный метод (вдруг логика формирования списка loadData поменяется и минимальный id будет искаться по-другому):
```java
private long getMinIdFromLoadDataList(List<LoadData> loadDataList) {
    int lastIndex = loadDataList.size() - 1;
    LoadData lastLoadData = loadDataList.get(lastIndex);
    // хотя наверное это уже перебор и достаточно сразу lastLoadData = loadDataList.get(loadDataList.size() - 1);
    return lastLoadData.getId();
}
```

3. Здесь вот такая проверка политики обработки запуска:
```java
if (Objects.equals(rti.getRunInfo().getRun().getRequest().getRunTypeId(), LoadRequest.RUN_TYPE_CONTINUE_SUCCESS)) {
        //...
}
```
Вообще по хорошему цепочку вызовов надо изменить как в предыдущем задании, но и в дополнении к этому саму проверку вынести в отдельный метод с говорящим названием:
стало:
```java
private boolean shouldContinueOnlyForSuccess(RequestTrackInfo rti) {
    // я предполагаю, что во всех классах цепочки getRunInfo().getRun().getRequest().getRunTypeId()
    // сделал как в предыдущем задании HW - с делегированием методов, чтобы каждый класс знал только о соседе. 
    // тогда для класса RequestTrackInfo будет один метод, покрывающий всю цепочку:
    return Objects.equals(rti.getRunTypeIdForRequest(), LoadRequest.RUN_TYPE_CONTINUE_SUCCESS);
}
// теперь использование:
if (shouldContinueOnlyForSuccess(rti)) {
        //...
        }
```

4. Еще одно длинное и запутанное условие (на этот раз условие продолжения цикла)
```java
} while (!expiredRows.isEmpty() && !Objects.equals(pointerCorrelationId, expiredRows.get(0).getCorrelationId()));
```
Тут мне кажется, имеет смысл просто вынести данную проверку в отдельный метод с нормальным названием.
Стало:
```java
private boolean haveNextExpiredRow(List<ScenarioAsyncProcState> expiredRows, String pointerCorrelationId) {
    if (expiredRows.isEmpty()) return false;
    long firstExpiredRowCorrelationId = expiredRows.get(0).getCorrelationId();
    return !Objects.equals(pointerCorrelationId, firstExpiredRowCorrelationId);
}
// теперь использование: 
} while (haveNextExpiredRow(expiredRows, pointerCorrelationId));
```
5. Здесь при формировании имени потока создается formatter, форматируется дата, добавляет префикс (который не вынесен в константу!)
```java
return "master-service-thread " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS"));
```
Вынес форматтер и префикс в константы и форматирование даты вынес в отдельную переменную 
Стало:
```java
private static final String THREAD_NAME_PREFIX = "master-service-thread ";

private static final DateTimeFormatter THREAD_NAME_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS");
//....
    String formattedCurrentTime = LocalDateTime.now().format(THREAD_NAME_TIME_FORMATTER);
    return THREAD_NAME_PREFIX + formattedCurrentTime;
```

PS а вообще, не покидает ощущение, что параллельно занимаюсь противоположным - здесь уменьшаю "длину строки кода", а в "ФП для начинающих" наоборот, накручиваю все в одну строку :)

