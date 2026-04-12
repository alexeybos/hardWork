Первая проба в режиме Hard Work.
Так что для разминки решил начать со своего, неожиданно подходящего, метода из телеграм-бота (который для диплома делал).
Изначально был простой тип события VehicleEvent и в классе VehicleEventHandler метод с ЦС=8:
```java
public class VehicleEvent {
    public enum EventType {
        VEHICLE_CREATED,
        VEHICLE_UPDATED,
        VEHICLE_DELETED,
        DRIVER_ASSIGN
    }

    private EventType eventType;
    private Long vehicleId;
    private Long enterpriseId;
    //private Long managerId;
    private String username;
    private String vehicleName;
    private String driverName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime eventTime;

    private Map<String, Object> changes;
}

private String formatNotificationMessageOld(VehicleEvent event) {
    StringBuilder builder = new StringBuilder();

    switch (event.getEventType()) {
        case VEHICLE_CREATED:
            builder.append("<b>Новая машина создана</b>\n\n");
            builder.append("<b>Номер:</b> ").append(event.getVehicleName()).append("\n");
            builder.append("<b>ID машины:</b> ").append(event.getVehicleId()).append("\n");
            builder.append("<b>ID предприятия:</b> ").append(event.getEnterpriseId()).append("\n");
            break;

        case VEHICLE_UPDATED:
            builder.append("<b>Машина обновлена</b>\n\n");
            builder.append("<b>Номер:</b> ").append(event.getVehicleName()).append("\n");
            builder.append("<b>ID машины:</b> ").append(event.getVehicleId()).append("\n");
            builder.append("<b>ID предприятия:</b> ").append(event.getEnterpriseId()).append("\n");

            if (event.getChanges() != null && !event.getChanges().isEmpty()) {
                builder.append("\n<b>Изменения:</b>\n");
                event.getChanges().forEach((field, change) -> {
                    builder.append(String.format("• %s: %s\n", field, change));
                });
            }
            break;
        case VEHICLE_DELETED:
            builder.append("<b>Машина удалена</b>\n\n");
            builder.append("<b>Номер:</b> ").append(event.getVehicleName()).append("\n");
            builder.append("<b>ID машины:</b> ").append(event.getVehicleId()).append("\n");
            builder.append("<b>ID предприятия:</b> ").append(event.getEnterpriseId()).append("\n");
            break;
        case DRIVER_ASSIGN:
            builder.append("<b>На машину назначен новый водитель</b>\n\n");
            builder.append("<b>Номер:</b> ").append(event.getVehicleName()).append("\n");
            builder.append("<b>ID машины:</b> ").append(event.getVehicleId()).append("\n");
            builder.append("<b>ID предприятия:</b> ").append(event.getEnterpriseId()).append("\n");
            builder.append("<b>Имя водителя:</b> ").append(event.getDriverName()).append("\n");
            break;
    }

    if (event.getEventTime() != null) {
        builder.append("<b>Время события:</b> ")
                .append(event.getEventTime().format(
                        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                .append("\n");
    }

    return builder.toString();
}
```
Сначала сделал промежуточный, "быстрый" вариант, просто перенеся логику выбора в дополнительный метод, но т.к. там все равно оставались ветвления (хоть и без else), решил уж сразу сделать максимально красиво и в результате получился вот следующий вариант решения:
по сути, в данном случае использую паттерн Стратегия и, т.к. я имею типы события не в виде класса, а в виде наименования, то тут из материала подходит вариант динамического добавление функциональности (надеюсь, я тут правильно термин использовал). Правда решил использовать не словарь, а раз уже есть enum, то расширить функциональностью его.
Хотя, если использовать словарь, то не будет лишних пустых функций для событий, у которых нет доп полей.
Второй прием: избавление от null при аппенде времени события.
В функции для события VEHICLE_UPDATED все же оставил один if, но убрал проверку на null, гарантировав, что метод event.getChanges() всегда вернет Map (emptyMap при отсутствии изменений)  
Результат: ЦС основного метода formatNotificationMessage (было) = 8; (стало) = 1:
```java
public class VehicleEvent {
    public enum EventType implements NotificationAppenderStrategy {
        VEHICLE_CREATED("Новая машина создана") {
            @Override
            public void appendInformation(StringBuilder sb, VehicleEvent event) {
                //без доп полей
            }
        },
        VEHICLE_UPDATED("Машина обновлена") {
            @Override
            public void appendInformation(StringBuilder sb, VehicleEvent event) {
                if (!event.getChanges().isEmpty()) {
                    sb.append("\n<b>Изменения:</b>\n");
                    event.getChanges().forEach((field, change) -> {
                        sb.append(String.format("• %s: %s\n", field, change));
                    });
                }
            }
        },
        VEHICLE_DELETED("Машина удалена") {
            @Override
            public void appendInformation(StringBuilder sb, VehicleEvent event) {
                //без доп полей
            }
        },
        DRIVER_ASSIGN("На машину назначен новый водитель") {
            @Override
            public void appendInformation(StringBuilder sb, VehicleEvent event) {
                sb.append("<b>Имя водителя:</b> ").append(event.getDriverName()).append("\n");
            }
        };

        private final String desc;
        EventType(String description) {
            this.desc = description;
        }
        public String getDesc() {
            return desc;
        }
    }

    private EventType eventType;
    private Long vehicleId;
    private Long enterpriseId;
    //private Long managerId;
    private String username;
    private String vehicleName;
    private String driverName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime eventTime;

    private Map<String, Object> changes = Collections.emptyMap();

}

private String formatNotificationMessage(VehicleEvent event) {
    StringBuilder builder = new StringBuilder();
    VehicleEvent.EventType eventType = event.getEventType();

    builder.append("<b>").append(eventType.getDesc()).append("</b>\n\n");

    builder.append("<b>Номер:</b> ").append(event.getVehicleName()).append("\n");
    builder.append("<b>ID машины:</b> ").append(event.getVehicleId()).append("\n");
    builder.append("<b>ID предприятия:</b> ").append(event.getEnterpriseId()).append("\n");

    eventType.appendInformation(builder, event);

    appendEventTime(builder, event.getEventTime());

    return builder.toString();
}

private void appendEventTime(StringBuilder sb, LocalDateTime time) {
    Optional.ofNullable(time).ifPresent(eventTime -> {
        sb.append("<b>Время события:</b> ")
                .append(eventTime.format(
                        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                .append("\n");
    });
}
```
Второй фрагмент кода (из рабочего проекта). ЦС в районе 24 на ~40 инструкциях:
```java
public void cancelLoadRequest(final Long loadRequestId, final Locale locale) throws AppException {
    final ExceptionHolder<AppException> exceptionHolder = new ExceptionHolder<>();
    int tryNum = 1;
    do {
        try {
            this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    try {
                        LoadRequest lockedLoadRequest = null;
                        try {
                            lockedLoadRequest = loadRequestDao.lockById(loadRequestId);
                        } catch (DataAccessException e) {
                            throw new LockAcquireException(e);
                        }

                        if (lockedLoadRequest == null) {
                            throw new NotFoundException(lbl.msg(locale, "StdRunService.cancelLoadRequest.notFound",
                                    loadRequestId), AppErr.UNEXISTING_ENTITY);
                        }

                        if (!(LoadRequest.STATUS_PLANNING.equals(lockedLoadRequest.getStatusId()) ||
                                LoadRequest.STATUS_IN_PROGRESS.equals(lockedLoadRequest.getStatusId()) ||
                                LoadRequest.STATUS_RUN_WAITING.equals(lockedLoadRequest.getStatusId()))) {
                            throw new BusinessException(lbl.msg(locale,
                                    "StdRunService.cancelLoadRequest.invalidStatus", loadRequestId,
                                    lockedLoadRequest.getStatusId()),
                                    AppErr.DATA_ACCESS);
                        }

                        List<Run> runList = runDao.findExecutingByLoadRequest(loadRequestId);

                        if (runList.isEmpty()) {
                            lockedLoadRequest.setStatusId(LoadRequest.STATUS_CANCELED);
                            lockedLoadRequest.setEndDate(DateFactory.getCurrentDate());
                            loadRequestDao.updateState(lockedLoadRequest);

                            List<Operation> operations = operationDao.findByRequest(loadRequestId);
                            for (Operation operation : operations) {
                                Operation lockedOperation = null;
                                try {
                                    lockedOperation = operationDao.lockById(operation.getId(), operation.getRequestId());
                                } catch (DataAccessException e) {
                                    throw new LockAcquireException(e);
                                }

                                if (lockedOperation == null) continue;
                                if (Operation.STATUS_DONE.equals(lockedOperation.getStatusId())) continue;
                                if (Operation.STATUS_ERR.equals(lockedOperation.getStatusId())) continue;

                                lockedOperation.setStatusId(Operation.STATUS_CANCELLED);
                                lockedOperation.setEndDate(DateFactory.getCurrentDate());
                                operationDao.updateEndDateIfNull(lockedOperation);
                            }
                        } else {
                            for (Run run : runList) {
                                controlService.unRegisterRun(run.getId(), locale);
                            }
                        }
                        exceptionHolder.setException(null);
                    } catch (DataAccessException e) {
                        status.setRollbackOnly();
                        exceptionHolder.setException(new AppException(lbl.msg(locale,
                                "StdRunService.cancelLoadRequest.error.update", loadRequestId, e,
                                AppErr.DATA_ACCESS), e));
                    } catch (AppException e) {
                        status.setRollbackOnly();
                        exceptionHolder.setException(e);
                    }
                }
            });
        } catch (TransactionException e) {
            if (ErrorUtil.isResourceException(e)) {
                exceptionHolder.setException(new ResourceUnavailableException("Error cancelling load request", e));
            }
        }
        if (exceptionHolder.getException() instanceof LockAcquireException) {
            LOG.info("Error of attempt={} to lock request or its operation (req id={})", tryNum, loadRequestId);
            LOG.info("", exceptionHolder.getException());
            try {
                Thread.sleep(TimeUnit.MILLISECONDS.toMillis(300));
            } catch (InterruptedException ie) {
                LOG.info(ie.getMessage(), ie);
            }
            if (tryNum == maxLockTry*2) {
                LOG.warn("Error locking request id={} or its operations (all attempts failed)", loadRequestId);
            }
            tryNum++;
        }
    } while (!exceptionHolder.isEmpty()
            && exceptionHolder.getException() instanceof LockAcquireException
            && tryNum <= maxLockTry);

    if (exceptionHolder.isNotEmpty()) {
        throw exceptionHolder.getException();
    }
}
```
С ним сильно помучился из-за необходимости не поломать логику исключений (вообще, кажется начинаю понимать, почему исключений желательно избегать).
Для уменьшения ЦС использовал отказ от else (и цепочек else if), вынос сложной логики в отдельные методы, сократил условия по проверке статусов путем выноса проверяемых статусов в список и проверке по contains.
В итоге основной метод (cancelLoadRequestNew) ЦС(было) = 24; ЦС(стало) = 7
```java
public void cancelLoadRequest(final Long loadRequestId, final Locale locale) throws AppException {
    final ExceptionHolder<AppException> exceptionHolder = new ExceptionHolder<>();
    int tryNum = 1;
    do {
        try {
            this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    try {
                        tryToCancelLoadRequest(loadRequestId, locale);
                        exceptionHolder.setException(null);
                    } catch (DataAccessException e) {
                        status.setRollbackOnly();
                        exceptionHolder.setException(new AppException(lbl.msg(locale,
                                "StdRunService.cancelLoadRequest.error.update", loadRequestId, e,
                                AppErr.DATA_ACCESS), e));
                    } catch (AppException e) {
                        status.setRollbackOnly();
                        exceptionHolder.setException(e);
                    }
                }
            });
        } catch (TransactionException e) {
            if (ErrorUtil.isResourceException(e)) {
                exceptionHolder.setException(new ResourceUnavailableException("Error cancelling load request", e));
            }
        }
        if (exceptionHolder.getException() instanceof LockAcquireException) {
            lockRetry(exceptionHolder, tryNum, loadRequestId);
            tryNum++;
        }
    } while (!exceptionHolder.isEmpty()
            && exceptionHolder.getException() instanceof LockAcquireException
            && tryNum <= maxLockTry);

    if (exceptionHolder.isNotEmpty()) {
        throw exceptionHolder.getException();
    }
}

private void tryToCancelLoadRequest(final Long loadRequestId, final Locale locale) throws AppException {
        LoadRequest lockedLoadRequest = null;
        try {
            lockedLoadRequest = loadRequestDao.lockById(loadRequestId);
        } catch (DataAccessException e) {
            throw new LockAcquireException(e);
        }

        if (lockedLoadRequest == null) {
            throw new NotFoundException(lbl.msg(locale, "StdRunService.cancelLoadRequest.notFound",
                    loadRequestId), AppErr.UNEXISTING_ENTITY);
        }

        List<Long> suitableStatuses = List.of(
                LoadRequest.STATUS_PLANNING,
                LoadRequest.STATUS_IN_PROGRESS,
                LoadRequest.STATUS_RUN_WAITING
        );
        if (!suitableStatuses.contains(lockedLoadRequest.getStatusId())) {
            throw new BusinessException(lbl.msg(locale,
                    "StdRunService.cancelLoadRequest.invalidStatus", loadRequestId,
                    lockedLoadRequest.getStatusId()),
                    AppErr.DATA_ACCESS);
        }

        List<Run> runList = runDao.findExecutingByLoadRequest(loadRequestId);

        if (runList.isEmpty()) {
            lockedLoadRequest.setStatusId(LoadRequest.STATUS_CANCELED);
            lockedLoadRequest.setEndDate(DateFactory.getCurrentDate());
            loadRequestDao.updateState(lockedLoadRequest);
            cancelOperations(loadRequestId);
            return;
        }
        for (Run run : runList) {
            controlService.unRegisterRun(run.getId(), locale);
        }
    }

    private void cancelOperations(Long loadRequestId) throws LockAcquireException {
        List<Operation> operations = operationDao.findByRequest(loadRequestId);

        List<Long> finalStatuses = List.of(
                Operation.STATUS_DONE,
                Operation.STATUS_ERR
        );
        for (Operation operation : operations) {
            Operation lockedOperation;
            try {
                lockedOperation = operationDao.lockById(
                        operation.getId(), operation.getRequestId());
            } catch (DataAccessException e) {
                throw new LockAcquireException(e);
            }
            if (lockedOperation == null) continue;
            if (finalStatuses.contains(lockedOperation.getStatusId())) continue;
            lockedOperation.setStatusId(Operation.STATUS_CANCELLED);
            lockedOperation.setEndDate(DateFactory.getCurrentDate());
            operationDao.updateEndDateIfNull(lockedOperation);
        }
    }

    private void lockRetry(ExceptionHolder<AppException> exceptionHolder, int tryNum, Long loadRequestId) {
        LOG.info("Error of attempt={} to lock request or its operation (req id={})", tryNum, loadRequestId);
        LOG.info("", exceptionHolder.getException());
        try {
            Thread.sleep(TimeUnit.MILLISECONDS.toMillis(300));
        } catch (InterruptedException ie) {
            LOG.info(ie.getMessage(), ie);
        }
        if (tryNum == maxLockTry*2) {
            LOG.warn("Error locking request id={} or its operations (all attempts failed)", loadRequestId);
        }
    }
```
Еще один некрасивый метод с ЦС = 22 (также, из рабочего проекта)
```java
private ArrayList<BulkRequestInputItemsAccessInfo> fillAllowedActions(BulkRequestInputItemsActionEnum[] actions,
                                                                      Map<String, Object> actionsParameters,
                                                                      Long languageId) {
    String reason = null;
    ActionAccessEnum actionAccessEnum = null;
    Integer allowed = 1;
    boolean actionAllowed;
    Boolean anyOperationTypeAllowed = (Boolean) actionsParameters.get("allowAtLeastOneOfOperationTypes");
    Long bulkRequestStatus = (Long) actionsParameters.get("bulkRequestStatus");
    ArrayList<BulkRequestInputItemsAccessInfo> listForResult = new ArrayList<>();
    for (BulkRequestInputItemsActionEnum action: actions) {
        actionAllowed = true;
        switch (action) {
            case ADD_ITEM:
            case IMPORT_FROM_FILE:
                if (!(allowed.equals(actionsParameters.get("allowRequestCreate")) && anyOperationTypeAllowed)) {
                    actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                    reason = messageResolver.getMessage(languageId,
                            "BulkRequest.allowedActions.change.forbidden");
                    actionAllowed = false;
                } else if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "BulkRequest.allowedActions.change.restricted");
                    actionAllowed = false;
                } else if (BulkRequest.INPUT_ITEM_STATUS_CHECK_IN_PROGRESS.equals(
                        actionsParameters.get("itemsCheckStatusId"))) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "RequestItemsService.allowedActions.add.checkInProgress.restricted");
                    actionAllowed = false;
                }
                break;
            case IMPORT_FROM_REQUEST:
                if (!(allowed.equals(actionsParameters.get("allowRequestCreate")) && anyOperationTypeAllowed)) {
                    actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                    reason = messageResolver.getMessage(languageId,
                            "BulkRequest.allowedActions.change.forbidden");
                    actionAllowed = false;
                }
                break;
            case CHECK:
                if (!(allowed.equals(actionsParameters.get("allowRequestCreate")) && anyOperationTypeAllowed)) {
                    actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                    reason = messageResolver.getMessage(languageId,
                            "BulkRequest.allowedActions.change.forbidden");
                    actionAllowed = false;
                } else if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "RequestItemsService.allowedActions.check.restricted");
                    actionAllowed = false;
                } else if (BulkRequest.INPUT_ITEM_STATUS_CHECK_IN_PROGRESS.equals(
                        actionsParameters.get("itemsCheckStatusId"))) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "RequestItemsService.allowedActions.check.inProgress.restricted");
                    actionAllowed = false;
                } else if (BulkRequest.INPUT_ITEM_STATUS_WITHOUT_CHECK.equals(
                        actionsParameters.get("itemsCheckStatusId"))) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "RequestItemsService.allowedActions.check.noCheck.restricted");
                    actionAllowed = false;
                } else if ((Integer) actionsParameters.get("operationsCount") == 0) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "RequestItemsService.allowedActions.check.noOperations.restricted");
                    actionAllowed = false;
                }
                break;
            case DELETE_ITEM:
                if (!allowed.equals(actionsParameters.get("allowRequestDelete"))) {
                    actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                    reason = messageResolver.getMessage(languageId,
                            "BulkRequest.allowedActions.change.forbidden");
                    actionAllowed = false;
                } else if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "BulkRequest.allowedActions.change.restricted");
                    actionAllowed = false;
                } else if (BulkRequest.INPUT_ITEM_STATUS_CHECK_IN_PROGRESS.equals(
                        actionsParameters.get("itemsCheckStatusId"))) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "RequestItemsService.allowedActions.add.checkInProgress.restricted");
                    actionAllowed = false;
                }
                break;
            case EXPORT_TO_FILE:
                if ((Long) actionsParameters.get("anyInputItemId") == 0L) {
                    actionAccessEnum = ActionAccessEnum.RESTRICTED;
                    reason = messageResolver.getMessage(languageId,
                            "RequestItemsService.allowedActions.export.restricted");
                    actionAllowed = false;
                }
                break;
            default:
                reason = "Untyped action";
                actionAllowed = false;
        }
        if (actionAllowed) {
            actionAccessEnum = ActionAccessEnum.ALLOWED;
            reason = null;
        }
        BulkRequestInputItemsAccessInfo accessInfo = new BulkRequestInputItemsAccessInfo();
        accessInfo.setAction(action);
        accessInfo.setAccess(actionAccessEnum);
        accessInfo.setReason(reason);
        listForResult.add(accessInfo);
    }
    return listForResult;
}
```
Здесь, как и в первом случае, инкапсулировал "тело" условий в отдельные функции и вынес их в словарь, с доступом по action.
Вторым методом - избавился от else и цепочек else if (с помощью раннего выхода из функций). Проверки на null (которая могла бы появиться для бывшего default-случая в case при выборе метода из Map) я избежал, использовав лямбду в getOrDefault.
Хотя вообще, там конечно есть куда еще рефакторить и рефакторить (в том числе и по большому количеству дублированного кода)...
Но в итоге ЦС основного метода (fillAllowedActions) снизилась с начальной ЦС = 22 до ЦС = 2:
```java
private final Map<BulkRequestInputItemsActionEnum,
            BiFunction<Map<String, Object>, Long, ActionAccessCheckResult>> allowedActionCheckers = Map.of(
            BulkRequestInputItemsActionEnum.ADD_ITEM,           this::checkAddItem,
            BulkRequestInputItemsActionEnum.IMPORT_FROM_FILE,   this::checkImportFromFile,
            BulkRequestInputItemsActionEnum.IMPORT_FROM_REQUEST, this::checkImportFromRequest,
            BulkRequestInputItemsActionEnum.CHECK,              this::checkCheckAction,
            BulkRequestInputItemsActionEnum.DELETE_ITEM,        this::checkDeleteItem,
            BulkRequestInputItemsActionEnum.EXPORT_TO_FILE,     this::checkExportToFile
    );

private ArrayList<BulkRequestInputItemsAccessInfo> fillAllowedActions(BulkRequestInputItemsActionEnum[] actions,
                                                                      Map<String, Object> actionsParameters,
                                                                      Long languageId) {
    ArrayList<BulkRequestInputItemsAccessInfo> listForResult = new ArrayList<>();
    for (BulkRequestInputItemsActionEnum action: actions) {
        ActionAccessCheckResult checkResult = allowedActionCheckers
                .getOrDefault(action, (actionsParams, langId) ->
                        new ActionAccessCheckResult(ActionAccessEnum.FORBIDDEN, "Untyped action"))
                .apply(actionsParameters, languageId);

        BulkRequestInputItemsAccessInfo accessInfo = new BulkRequestInputItemsAccessInfo();
        accessInfo.setAction(action);
        accessInfo.setAccess(checkResult.getAccess());
        accessInfo.setReason(checkResult.getReason());
        listForResult.add(accessInfo);
    }
    return listForResult;
}

private ActionAccessCheckResult checkAddItem(Map<String, Object> actionsParameters, Long languageId) {
    Integer allowed = 1;
    Boolean anyOperationTypeAllowed = (Boolean) actionsParameters.get("allowAtLeastOneOfOperationTypes");
    Integer canCreate = (Integer) actionsParameters.get("allowRequestCreate");
    if (!(allowed.equals(canCreate) && anyOperationTypeAllowed)) {
        return new ActionAccessCheckResult(ActionAccessEnum.FORBIDDEN, messageResolver.getMessage(languageId,
                "BulkRequest.allowedActions.change.forbidden"));
    }
    Long bulkRequestStatus = (Long) actionsParameters.get("bulkRequestStatus");
    if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "BulkRequest.allowedActions.change.restricted"));
    }
    Long itemsCheckStatusId = (Long) actionsParameters.get("itemsCheckStatusId");
    if (BulkRequest.INPUT_ITEM_STATUS_CHECK_IN_PROGRESS.equals(itemsCheckStatusId)) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "RequestItemsService.allowedActions.add.checkInProgress.restricted"));
    }
    return new ActionAccessCheckResult(ActionAccessEnum.ALLOWED, null);
}

private ActionAccessCheckResult checkImportFromFile(Map<String, Object> actionsParameters, Long languageId) {
    return checkAddItem(actionsParameters, languageId);
}

private ActionAccessCheckResult checkImportFromRequest(Map<String, Object> actionsParameters, Long languageId) {
    Integer allowed = 1;
    Boolean anyOperationTypeAllowed = (Boolean) actionsParameters.get("allowAtLeastOneOfOperationTypes");
    Integer allowRequestCreate = (Integer) actionsParameters.get("allowRequestCreate");
    if (!(allowed.equals(allowRequestCreate) && anyOperationTypeAllowed)) {
        return new ActionAccessCheckResult(ActionAccessEnum.FORBIDDEN, messageResolver.getMessage(languageId,
                "BulkRequest.allowedActions.change.forbidden"));
    }
    return new ActionAccessCheckResult(ActionAccessEnum.ALLOWED, null);
}

private ActionAccessCheckResult checkCheckAction(Map<String, Object> actionsParameters, Long languageId) {
    Integer allowed = 1;
    Boolean anyOperationTypeAllowed = (Boolean) actionsParameters.get("allowAtLeastOneOfOperationTypes");
    Integer allowRequestCreate = (Integer) actionsParameters.get("allowRequestCreate");

    if (!(allowed.equals(allowRequestCreate) && anyOperationTypeAllowed)) {
        return new ActionAccessCheckResult(ActionAccessEnum.FORBIDDEN, messageResolver.getMessage(languageId,
                "BulkRequest.allowedActions.change.forbidden"));
    }
    Long bulkRequestStatus = (Long) actionsParameters.get("bulkRequestStatus");
    if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "RequestItemsService.allowedActions.check.restricted"));
    }
    Long itemsCheckStatusId = (Long) actionsParameters.get("itemsCheckStatusId");
    if (BulkRequest.INPUT_ITEM_STATUS_CHECK_IN_PROGRESS.equals(itemsCheckStatusId)) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "RequestItemsService.allowedActions.check.inProgress.restricted"));
    }
    if (BulkRequest.INPUT_ITEM_STATUS_WITHOUT_CHECK.equals(itemsCheckStatusId)) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "RequestItemsService.allowedActions.check.noCheck.restricted"));
    }
    Integer operationsCount = (Integer) actionsParameters.get("operationsCount");
    if (operationsCount == 0) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "RequestItemsService.allowedActions.check.noOperations.restricted"));
    }
    return new ActionAccessCheckResult(ActionAccessEnum.ALLOWED, null);
}

private ActionAccessCheckResult checkDeleteItem(Map<String, Object> actionsParameters, Long languageId) {
    Integer allowed = 1;
    Integer allowRequestDelete = (Integer) actionsParameters.get("allowRequestDelete");
    if (!allowed.equals(allowRequestDelete)) {
        return new ActionAccessCheckResult(ActionAccessEnum.FORBIDDEN, messageResolver.getMessage(languageId,
                "BulkRequest.allowedActions.change.forbidden"));
    }
    Long bulkRequestStatus = (Long) actionsParameters.get("bulkRequestStatus");
    if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "BulkRequest.allowedActions.change.restricted"));
    }
    Long itemsCheckStatusId = (Long) actionsParameters.get("itemsCheckStatusId");
    if (BulkRequest.INPUT_ITEM_STATUS_CHECK_IN_PROGRESS.equals(itemsCheckStatusId)) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "RequestItemsService.allowedActions.add.checkInProgress.restricted"));
    }
    return new ActionAccessCheckResult(ActionAccessEnum.ALLOWED, null);
}

private ActionAccessCheckResult checkExportToFile(Map<String, Object> actionsParameters, Long languageId) {
    if ((Long) actionsParameters.get("anyInputItemId") == 0L) {
        return new ActionAccessCheckResult(ActionAccessEnum.RESTRICTED, messageResolver.getMessage(languageId,
                "RequestItemsService.allowedActions.export.restricted"));
    }
    return new ActionAccessCheckResult(ActionAccessEnum.ALLOWED, null);
}
```
Собственно по ходу выполнения первого задания при анализе как своего кода, так и вообще кода нашего проекта отметил, что многие методы перегружены условными операторами и вообще цепочками else if, а так же многоуровневыми условиями.
При этом читаемость многих подобных методов очень, скажем так, посредственная. А читаемость методов вроде того, который я рефакторил во втором случае, вообще ужасная. 
Честно говоря, при попытке его сделать удобоваримым я несколько раз подумывал вообще его бросить и подыскать другой, попроще. Но уж столько времени убил, что было обидно бросать, да и как-то "не спортивно" это (ну и плюс помню один из недавних Ваших материалов как раз про "глупо бросать на полпути то, на что было потрачено время").
А вот после изменений понять логику и последовательность/варианты действий уже гораздо легче (надеюсь, конечно, это не результат того, что в процессе рефакторинга пришлось хорошо и долго вникать в смысл изменяемого кода).
Каюсь, конечно, что как-то не нашел кода для исправления ЦС путем использования полиморфизма - постоянно в поиске мелькал код по типу использованного в задании, закопался в нем.

А вот по итогу задания могу сказать следующее: достаточно много кода (я сейчас про рабочий проект), как унаследованного, так и мной написанного имеют слишком высокую ЦС. При этом мы не используем анализаторы для этой метрики и только изредка, при код ревью обращаем внимание на ЦС (вот прямо реально редко).
Мы не задумывались о рефакторинге методов типа второго в этом задании - а только таких похожих больше десятка. Думаю предложить запланировать такие работы при ближайшем изменении этих методов, т.к. периодически мы их трогаем (кстати, всегда страшно), ну и как раз показать начальнику результат моего изменения.
Плюс к этому хочу предложить включить в команде в Idea хотя бы встроенную инспекцию на ЦС - т.е. у нас даже она не включена (у себя уже нашел и включил).