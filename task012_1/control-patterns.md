1. Первый пример 

Для первого примера, как обычно, взял попроще. Здесь имеем проход по коллекции в "в ручном режиме" в цикле for + if для поиска/фильтрации (такая же или очень похожая конструкция еще в 4-х местах нашел).
Абстракция на замену (если я правильно понял как описывать): поток + anyMatch (конкретно для этого примера):

Было:
```java
public boolean isRowNotEmpty(final List<String> cellValues) {
        if (!CollectionUtils.isEmpty(cellValues)) {
            for (String cellValue : cellValues) {
                if (StringUtils.isNotBlank(cellValue)) {
                    return true;
                }
            }
        }
        return false;
    }
```
Стало:
```java
public boolean isRowNotEmpty(List<String> cellValues) {
    return cellValues != null && cellValues.stream().anyMatch(StringUtils::isNotBlank);
}
```
Фактически только сохраняем проверку на null, на пустоту коллекция "автоматически" проверится при обработке потока.

2. Второй пример

Тут в общем похожий пример, только цикл по индексам. А внутри фильтрация по условию и конкатенация в строку.

Было:
```java
String conflictText = "";
for (int i = 0; i < conflictsResult.size(); i++) {
    LinkedHashMap<String, String> conflict = conflictsResult.get(i);
    if (conflict.get("type").equals("ERROR")) {
        conflictText += (i > 0 ? " " : "") + conflict.get("message");
    }
if (!conflictText.equals("")){
        throw new BusinessException(labelService.msg(locale, "checkCustomerUpdate.invalidResult", conflictText));
}
```
Стало:
```java
String conflictText = conflictsResult.stream()
        .filter(conflict -> "ERROR".equals(conflict.get("type")))
        .map(conflict -> conflict.get("message"))
        .collect(Collectors.joining(" "));
if (!conflictText.isEmpty()) {
        throw new BusinessException(labelService.msg(locale, "checkCustomerUpdate.invalidResult", conflictText));
}
```
Здесь получается абстракция почти такая же как в первом примере: поток + фильтрация + склейка значений.

3. Третий пример

Тут имеем две почти одинаковые функции markRun и unMarkRun, которые работают в одинаковом "каркасе" из lock (ну т.е. захват ресурса) и release узла в zookeeper.

Было:
```java
public void markRun(long id, Locale locale) throws AppException {
        if (!this.developmentMode) {
            // Рабочий режим
            initRunRegisterMutex();

            lockNode(locale, "ZooRunMarkService.markRun.lock.error", "ZooRunMarkService.markRun.lock.fail", id);

            try {

                String nodePath = RUN_REGISTER_PATH + "/" + id;
                Stat nodeStat = getNode(nodePath);

                if (nodeStat != null) {
                    // уже помечен
                    return;
                }

                try {
                    curatorWrapper.createNode(nodePath);
                } catch (KeeperException.ConnectionLossException e) {
                    LOG.error(String.format("Error creating run registration node %s", id), e);
                    throw new ResourceUnavailableException(lbl.msg("ZooRunMarkService.markRun.errorCreatePath",
                            String.valueOf(id), e), e, AppErr.ZOO_WRITE);
                } catch (Exception e) {
                    LOG.error(String.format("Error creating run registration node %s", id), e);
                    throw new AppException(lbl.msg("ZooRunMarkService.markRun.errorCreatePath",
                            String.valueOf(id), e), e, AppErr.ZOO_WRITE);
                }
            } finally {
                // снятие блокировки
                for (int i = 0; i < this.maxUnlockRetry; i++) {
                    try {
                        this.runRegisterMutex.release();
                        break;
                    } catch (KeeperException.ConnectionLossException e) {
                        // Если соединение отвалилось - мьютекс закрылся
                        break;
                    } catch (Exception e) {
                        //Error releasing the lock on modification of the runs list in \
                        //  Zookeeper because of: {0}
                        LOG.error("Error releasing runs list modification lock in Zookeeper", e);
                        try {
                            Thread.currentThread().sleep(500);
                        } catch (InterruptedException e1) {
                            LOG.error(e1.getMessage(), e1);
                        }

                        if (i >= (this.maxUnlockRetry - 1)) {
                            throw new AppException(lbl.msg("ZooRunMarkService.markRun.unlock.error", e), e, AppErr.ZOO_WRITE);
                        }
                    }
                }
            }
        } else {
            // Режим отладки
            handleEvent(id, RunStartListener.State.START);
        }
    }
```
Это как раз паттерн with. Идея соответственно вынести "каркас" в отдельную функцию и уже в ней будет обеспечена корректная работа с ресурсом.
Стало:
```java
//lock и release гарантированно вызываются "вокруг" собственно бизнес-кода.
//(в releaseWithRetry вынесена as is логика снятия блокировки из блока finally первоначального варианта)
private void withRunRegisterLock(long id, Locale locale, ThrowingRunnable body) throws Throwable {
        initRunRegisterMutex();
        lockNode(locale, "ZooRunMarkService.markRun.lock.error", "ZooRunMarkService.markRun.lock.fail", id);
        try { body.run(); } finally { releaseWithRetry(); }
}
//теперь markRun простой (логика создания вынесена as is в createRunNode)
public void markRun(long id, Locale locale) throws Throwable {
    if (developmentMode) { handleEvent(id, RunStartListener.State.START); return; }
    withRunRegisterLock(id, locale, () -> createRunNode(id));
}    
//соответственно unMarkRun упрощается точно также (только его логика уходит в deleteRunNode):
public void unMarkRun(long id, Locale locale) throws Throwable {
    if (developmentMode) { handleEvent(id, RunStartListener.State.START); return; }
    withRunRegisterLock(id, locale, () -> deleteRunNode(id));
}
```
Соответственно абстракция здесь функция высшего порядка, которая один раз описывает логику захвата-освобождения ресурса и принимает логику действия с узлом в виде лямбды.  