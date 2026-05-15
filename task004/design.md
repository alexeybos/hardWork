Итерация 1
Код сервиса работы с сущностями типа "групповая операция":
```java
@Override
    public ListResult getOperationAllowedActions(Object body, Object headers) throws Exception {
        logger.debug("getOperationAllowedActions start");
        InputFromCamel input = new InputFromCamel(headers, body, null, logger);
        Optional<Long> operationId = getIdFromMap(input.body, "bulkOperationId", logger);
        Optional<Long> bulkRequestId = getIdFromMap(input.body, "bulkRequestId", logger);
        if (!operationId.isPresent() || !bulkRequestId.isPresent()) {
            logger.debug("mandatory parameters missing, operationId={}, requestId={}", operationId, bulkRequestId);
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "BulkUtils.checkRequiredParameters.error.parameterIsMissing",
                    "[bulkRequestId, bulkOperationId]"), "InvalidParameter", 400);
        }
        List<BulkOperationInfoDto> operationInfo = operationDao.findLightOrFullByOperOrReqId(operationId.get(),
                bulkRequestId.get(), true);
        if (operationInfo.isEmpty()) {
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "OperationService.error.operationOfRequestNotFound", operationId.get(), bulkRequestId.get()),
                    "ObjectNotFound", 404);
        }
        String currentUser = input.headers.get(Constants.EXECUTE_USER_CAMEL_HEADER).toString();
        BulkOperationInfoDto operation = operationInfo.get(0);
        if (!operation.getBulkRequestCreateUser().equals(currentUser)
                && !accessControlUtil.isSupervisor(input.identity)) {
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "OperationService.error.operationOfRequestNotFound", operationId.get(), bulkRequestId.get()),
                    "ObjectNotFound", 404);
        }

        Map<String, Object> actionsParameters = new HashMap<>();
        actionsParameters.put("bulkRequestStatus", operation.getBulkRequestStatus());
        actionsParameters.put("allowRequestCreate", input.body.get("allowRequestCreate"));
        actionsParameters.put("allowRequestDelete", input.body.get("allowRequestDelete"));
        actionsParameters.put("allowAtLeastOneOfOperationTypes",
                accessControlUtil.isAnyOperationTypeGranted(input.identity));
        actionsParameters.put("typeCodeIsAllowed",
                accessControlUtil.isOperationTypeGranted(operation.getOperationType().getOperationCode(),
                        input.identity));
        actionsParameters.put("editable", Objects.equals(operation.getOperationType().getIsTypeEditable(), "Y"));

        BulkOperationActionEnum[] actions = prepareOperationActionsArray((BulkOperationActionEnum[]) input.body.get("actions"));
        ArrayList<BulkOperationAccessInfo> listForResult = fillOperationsAllowedActions(actions, actionsParameters,
                true, input.languageId);
        ListResult result = new ListResult();
        result.setItems(listForResult);
        return result;
    }

    @Override
    public ListResult getOperationsAllowedActions(Object body, Object headers) throws Exception {
        logger.debug("getOperationsAllowedActions start");
        InputFromCamel input = new InputFromCamel(headers, body, null, logger);
        Long bulkRequestId = getIdFromMap(input.body, "bulkRequestId", logger)
                .orElseThrow(() -> new WebCamelException(messageResolver.getMessage(input.languageId,
                        "BulkUtils.checkRequiredParameters.error.parameterIsMissing", "bulkRequestId"),
                        "InvalidParameter", 400));
        BulkRequest bulkRequest = bulkRequestDao.findById(bulkRequestId)
                .orElseThrow(() -> new WebCamelException(messageResolver.getMessage(input.languageId,
                        "RequestService.checkRequest.error.objectNotFound", bulkRequestId),
                        "ObjectNotFound", 404));
        //права по супервизору
        String currentUser = input.headers.get(Constants.EXECUTE_USER_CAMEL_HEADER).toString();
        if (!bulkRequest.getNaviUser().equals(currentUser)
                && !accessControlUtil.isSupervisor(input.identity)) {
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "RequestService.checkRequest.error.objectNotFound", bulkRequestId),
                    "ObjectNotFound", 404);
        }

        Map<String, Object> actionsParameters = new HashMap<>();
        actionsParameters.put("bulkRequestStatus", bulkRequest.getStatusId());
        actionsParameters.put("allowRequestCreate", input.body.get("allowRequestCreate"));
        actionsParameters.put("allowRequestDelete", input.body.get("allowRequestDelete"));
        actionsParameters.put("allowAtLeastOneOfOperationTypes",
                accessControlUtil.isAnyOperationTypeGranted(input.identity));

        BulkOperationActionEnum[] actions = prepareOperationsActionsArray((BulkOperationActionEnum[]) input.body.get("actions"));
        ArrayList<BulkOperationAccessInfo> listForResult = fillOperationsAllowedActions(actions, actionsParameters,
                false, input.languageId);
        ListResult result = new ListResult();
        result.setItems(listForResult);
        return result;
    }

    private BulkOperationActionEnum[] prepareOperationActionsArray(BulkOperationActionEnum[] actions) {
        if (actions == null) {
            actions = new BulkOperationActionEnum[]{
                    BulkOperationActionEnum.DELETE,
                    BulkOperationActionEnum.UPDATE
            };
        }
        return actions;
    }

    private BulkOperationActionEnum[] prepareOperationsActionsArray(BulkOperationActionEnum[] actions) {
        if (actions == null) {
            actions = new BulkOperationActionEnum[]{
                    BulkOperationActionEnum.ADD,
                    BulkOperationActionEnum.DELETE,
                    BulkOperationActionEnum.UPDATE
            };
        }
        return actions;
    }

    private ArrayList<BulkOperationAccessInfo> fillOperationsAllowedActions(BulkOperationActionEnum[] actions,
                                                                           Map<String, Object> actionsParameters,
                                                                           boolean isForOperation,
                                                                           Long languageId) {
        String reason = null;
        ActionAccessEnum actionAccessEnum = null;
        Integer allowed = 1;
        boolean actionAllowed;
        Boolean anyOperationTypeAllowed = (Boolean) actionsParameters.get("allowAtLeastOneOfOperationTypes");
        Long bulkRequestStatus = (Long) actionsParameters.get("bulkRequestStatus");
        ArrayList<BulkOperationAccessInfo> listForResult = new ArrayList<>();
        for (BulkOperationActionEnum action: actions) {
            actionAllowed = true;
            switch (action) {
                case ADD:
                    if (!(allowed.equals(actionsParameters.get("allowRequestCreate")) && anyOperationTypeAllowed)) {
                        actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                        reason = messageResolver.getMessage(languageId,
                                "OperationService.allowedActions.create.forbidden");
                        actionAllowed = false;
                    } else if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
                        actionAccessEnum = ActionAccessEnum.RESTRICTED;
                        reason = messageResolver.getMessage(languageId,
                                "BulkRequest.allowedActions.change.restricted");
                        actionAllowed = false;
                    }
                    break;
                case UPDATE:
                    if (!(allowed.equals(actionsParameters.get("allowRequestCreate")) && anyOperationTypeAllowed)) {
                        actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                        reason = messageResolver.getMessage(languageId,
                                "OperationService.allowedActions.update.forbidden");
                        actionAllowed = false;
                    } else if (isForOperation && !(Boolean) actionsParameters.get("editable")) {
                        actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                        reason = messageResolver.getMessage(languageId,
                                "OperationService.allowedActions.update.byOperationType.forbidden");
                        actionAllowed = false;
                    } else if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
                        actionAccessEnum = ActionAccessEnum.RESTRICTED;
                        reason = messageResolver.getMessage(languageId,
                                "BulkRequest.allowedActions.update.restricted");
                        actionAllowed = false;
                    } else if (isForOperation && !(Boolean) actionsParameters.get("typeCodeIsAllowed")) {
                        actionAccessEnum = ActionAccessEnum.RESTRICTED;
                        reason = messageResolver.getMessage(languageId,
                                "OperationService.allowedActions.update.restricted");
                        actionAllowed = false;
                    }
                    break;
                case DELETE:
                    if (!allowed.equals(actionsParameters.get("allowRequestDelete"))) {
                        actionAccessEnum = ActionAccessEnum.FORBIDDEN;
                        reason = messageResolver.getMessage(languageId,
                                "OperationService.allowedActions.delete.forbidden");
                        actionAllowed = false;
                    } else if (!STATUS_PLANNING.equals(bulkRequestStatus) && !STATUS_DRAFT.equals(bulkRequestStatus)) {
                        actionAccessEnum = ActionAccessEnum.RESTRICTED;
                        reason = messageResolver.getMessage(languageId,
                                "OperationService.allowedActions.delete.restricted");
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
            BulkOperationAccessInfo accessInfo = new BulkOperationAccessInfo();
            accessInfo.setAction(action);
            accessInfo.setAccess(actionAccessEnum);
            accessInfo.setReason(reason);
            listForResult.add(accessInfo);
        }
        return listForResult;
    }
```
Логический дизайн кода:

- Есть сущности "набор групповых операций в рамках одной задачи (реквеста)" и отдельные "групповые операции".
- Для списка действий (добавить, изменить, удалить) определяется их доступность для указанных сущностей.
Списки действий различаются для набора групповых операций (изменить, удалить) и для отдельных операций ((добавить, изменить, удалить)).
- Признак доступности может быть "разрешен", "запрещен" и "ограничен". Для "запретительных" статусов указывается причина.
- Признак доступности и причина определяются на основе правил.
- Правила для каждого действия строго определены и освнованы как на характеристиках самих сущностей (статус, тип, разрешенность редактирования), так и на контексте (роли пользователя, принадлежности операции пользователю)

Краткое описание проблемы данного кода с точки зрения "дизайн-реализация":

Код дизайну соответствует плохо. Правила реализованы с помощью большого количества if (в том числе вложенных) внутри оператора switch. 
Сложно управлять набором условий в правилах, чтобы понять при каких условиях какой статус получит действие, нужно очень глубоко погрузиться в запутанный код.
Часть проверок для правил делается до вызова основного метода (fillOperationsAllowedActions), другая часть непосредственно уже в этом методе, что еще больше усложняет понимание кода.
Много дублирование кода. Флоу основного метода управляется булевым параметром (кстати, недавно получил это замечание на код ревью на задании на перепроходе АСД2).
В общем, резюме: код императивен, запутан. В нем легко сделать ошибку аналогичную разобранной в материале "Три уровня рассуждений о программной системе - 3" - например при добавлении нового действия забыть добавить его в prepareOperationsActionsArray или prepareOperationActionsArray (вот кстати еще одна проблема данного кода), можно перепутать флоу.

Попытка переписать данный код в максимально декларативном стиле:
```java
public class AccessInfo {
    public static BulkOperationAccessInfo allowed(BulkOperationActionEnum action) {
        BulkOperationAccessInfo info = new BulkOperationAccessInfo();
        info.setAction(action);
        info.setAccess(ActionAccessEnum.ALLOWED);
        info.setReason(null);
        return info;
    }
    public static BulkOperationAccessInfo forbidden(BulkOperationActionEnum action, String reason) {
        BulkOperationAccessInfo info = new BulkOperationAccessInfo();
        info.setAction(action);
        info.setAccess(ActionAccessEnum.FORBIDDEN);
        info.setReason(reason);
        return info;
    }
    public static BulkOperationAccessInfo restricted(BulkOperationActionEnum action, String reason) {
        BulkOperationAccessInfo info = new BulkOperationAccessInfo();
        info.setAction(action);
        info.setAccess(ActionAccessEnum.RESTRICTED);
        info.setReason(reason);
        return info;
    }
}

public interface ActionRule {
    BulkOperationAccessInfo eval(OperationsAccessActionContext context);
}

public class AddRule implements ActionRule {
    private final MessageResolverByLanguageId messageResolver;

    public AddRule(MessageResolverByLanguageId messageResolver) {
        this.messageResolver = messageResolver;
    }

    @Override
    public BulkOperationAccessInfo eval(OperationsAccessActionContext context) {
        if (!context.isRequestCreateAllowed() || !context.isAnyOperationTypeAllowed()) {
            return AccessInfo.forbidden(BulkOperationActionEnum.ADD,
                    messageResolver.getMessage(context.getLanguageId(), "OperationService.allowedActions.create.forbidden"));
        }
        if (!context.isRequestInEditableStatus()) {
            return AccessInfo.restricted(BulkOperationActionEnum.ADD,
                    messageResolver.getMessage(context.getLanguageId(), "BulkRequest.allowedActions.change.restricted"));
        }
        return AccessInfo.allowed(BulkOperationActionEnum.ADD);
    }
}

public class UpdateRule implements ActionRule {
    private final MessageResolverByLanguageId messageResolver;

    public UpdateRule(MessageResolverByLanguageId messageResolver) {
        this.messageResolver = messageResolver;
    }

    @Override
    public BulkOperationAccessInfo eval(OperationsAccessActionContext context) {
        if (!context.isRequestCreateAllowed() || !context.isAnyOperationTypeAllowed()) {
            return AccessInfo.forbidden(BulkOperationActionEnum.UPDATE,
                    messageResolver.getMessage(context.getLanguageId(), "OperationService.allowedActions.update.forbidden"));
        }
        if (context.isOperationContext() && !context.isEditable()) {
            return AccessInfo.forbidden(BulkOperationActionEnum.UPDATE,
                    messageResolver.getMessage(context.getLanguageId(), "OperationService.allowedActions.update.byOperationType.forbidden"));
        }
        if (!context.isRequestInEditableStatus() || (context.isOperationContext() && !context.isTypeCodeAllowed())) {
            return AccessInfo.restricted(BulkOperationActionEnum.UPDATE,
                    messageResolver.getMessage(context.getLanguageId(), "BulkRequest.allowedActions.update.restricted"));
        }
        return AccessInfo.allowed(BulkOperationActionEnum.UPDATE);
    }
}

public class DeleteRule implements ActionRule {
    private final MessageResolverByLanguageId messageResolver;

    public DeleteRule(MessageResolverByLanguageId messageResolver) {
        this.messageResolver = messageResolver;
    }

    @Override
    public BulkOperationAccessInfo eval(OperationsAccessActionContext context) {
        if (!context.isRequestDeleteAllowed()) {
            return AccessInfo.forbidden(BulkOperationActionEnum.DELETE,
                    messageResolver.getMessage(context.getLanguageId(), "OperationService.allowedActions.delete.forbidden"));
        }
        if (!context.isRequestInEditableStatus()) {
            return AccessInfo.restricted(BulkOperationActionEnum.DELETE,
                    messageResolver.getMessage(context.getLanguageId(), "OperationService.allowedActions.delete.restricted"));
        }
        return AccessInfo.allowed(BulkOperationActionEnum.DELETE);
    }
}

public class AllowedActionsEvaluator {
    public static List<BulkOperationAccessInfo> evaluate(List<ActionRule> rules,
                                                         OperationsAccessActionContext context) {
        List<BulkOperationAccessInfo> result = new ArrayList<>();
        for (ActionRule rule : rules) {
            result.add(rule.eval(context));
        }
        return result;
    }
}

public enum OperationsAccessSubjectType {
    REQUEST,
    OPERATION
}

public class OperationsAccessActionContext {
    private final OperationsAccessSubjectType accessSubjType;
    private final Long languageId;
    private final Long bulkRequestStatus;
    private final boolean requestCreateAllowed;
    private final boolean requestDeleteAllowed;
    private final boolean anyOperationTypeAllowed;
    private final boolean typeCodeAllowed;
    private final boolean editable;

    private OperationsAccessActionContext(Builder builder) {
        this.accessSubjType = builder.accessSubjType;
        this.languageId = builder.languageId;
        this.bulkRequestStatus = builder.bulkRequestStatus;
        this.requestCreateAllowed = builder.allowRequestCreate;
        this.requestDeleteAllowed = builder.allowRequestDelete;
        this.anyOperationTypeAllowed = builder.anyOperationTypeAllowed;
        this.typeCodeAllowed = builder.typeCodeIsAllowed;
        ;
        this.editable = builder.editable;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OperationsAccessSubjectType getAccessSubjType() {
        return accessSubjType;
    }

    public Long getLanguageId() {
        return languageId;
    }

    public Long getBulkRequestStatus() {
        return bulkRequestStatus;
    }

    public boolean isRequestCreateAllowed() {
        return requestCreateAllowed;
    }

    public boolean isRequestDeleteAllowed() {
        return requestDeleteAllowed;
    }

    public boolean isAnyOperationTypeAllowed() {
        return anyOperationTypeAllowed;
    }

    public boolean isTypeCodeAllowed() {
        return typeCodeAllowed;
    }

    public boolean isEditable() {
        return editable;
    }

    public boolean isOperationContext() {
        return accessSubjType == OperationsAccessSubjectType.OPERATION;
    }

    public boolean isRequestContext() {
        return accessSubjType == OperationsAccessSubjectType.REQUEST;
    }

    public boolean isRequestInEditableStatus() {
        return STATUS_PLANNING.equals(bulkRequestStatus)
                || STATUS_DRAFT.equals(bulkRequestStatus);
    }

    public static class Builder {
        private OperationsAccessSubjectType accessSubjType;
        private Long languageId;
        private Long bulkRequestStatus;
        private boolean allowRequestCreate;
        private boolean allowRequestDelete;
        private boolean anyOperationTypeAllowed;
        private boolean typeCodeIsAllowed;
        private boolean editable;

        public Builder accessSubjType(OperationsAccessSubjectType accessSubjType) {
            this.accessSubjType = accessSubjType;
            return this;
        }

        public Builder languageId(Long languageId) {
            this.languageId = languageId;
            return this;
        }

        public Builder bulkRequestStatus(Long bulkRequestStatus) {
            this.bulkRequestStatus = bulkRequestStatus;
            return this;
        }

        public Builder allowRequestCreate(boolean allowRequestCreate) {
            this.allowRequestCreate = allowRequestCreate;
            return this;
        }

        public Builder allowRequestDelete(boolean allowRequestDelete) {
            this.allowRequestDelete = allowRequestDelete;
            return this;
        }

        public Builder anyOperationTypeAllowed(boolean anyOperationTypeAllowed) {
            this.anyOperationTypeAllowed = anyOperationTypeAllowed;
            return this;
        }

        public Builder typeCodeIsAllowed(boolean typeCodeIsAllowed) {
            this.typeCodeIsAllowed = typeCodeIsAllowed;
            return this;
        }

        public Builder editable(boolean editable) {
            this.editable = editable;
            return this;
        }

        public OperationsAccessActionContext build() {

            return new OperationsAccessActionContext(this);
        }
    }
}
// и изменения в основном классе
@Override
    public ListResult getOperationAllowedActions(Object body, Object headers) throws Exception {
        logger.debug("getOperationAllowedActions start");
        InputFromCamel input = new InputFromCamel(headers, body, null, logger);
        Optional<Long> operationId = getIdFromMap(input.body, "bulkOperationId", logger);
        Optional<Long> bulkRequestId = getIdFromMap(input.body, "bulkRequestId", logger);
        if (!operationId.isPresent() || !bulkRequestId.isPresent()) {
            logger.debug("mandatory parameters missing, operationId={}, requestId={}", operationId, bulkRequestId);
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "BulkUtils.checkRequiredParameters.error.parameterIsMissing",
                    "[bulkRequestId, bulkOperationId]"), "InvalidParameter", 400);
        }
        List<BulkOperationInfoDto> operationInfo = operationDao.findLightOrFullByOperOrReqId(operationId.get(),
                bulkRequestId.get(), true);
        if (operationInfo.isEmpty()) {
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "OperationService.error.operationOfRequestNotFound", operationId.get(), bulkRequestId.get()),
                    "ObjectNotFound", 404);
        }
        String currentUser = input.headers.get(Constants.EXECUTE_USER_CAMEL_HEADER).toString();
        BulkOperationInfoDto operation = operationInfo.get(0);
        if (!operation.getBulkRequestCreateUser().equals(currentUser)
                && !accessControlUtil.isSupervisor(input.identity)) {
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "OperationService.error.operationOfRequestNotFound", operationId.get(), bulkRequestId.get()),
                    "ObjectNotFound", 404);
        }

        Integer allowed = 1;
        OperationsAccessActionContext context = OperationsAccessActionContext.builder()
                .accessSubjType(OperationsAccessSubjectType.REQUEST)
                .languageId(input.languageId)
                .allowRequestCreate(allowed.equals(input.body.get("allowRequestCreate")))
                .allowRequestDelete(allowed.equals(input.body.get("allowRequestDelete")))
                .bulkRequestStatus(operation.getBulkRequestStatus())
                .anyOperationTypeAllowed(accessControlUtil.isAnyOperationTypeGranted(input.identity))
                .typeCodeIsAllowed(accessControlUtil.isOperationTypeGranted(operation.getOperationType().getOperationCode(),
                        input.identity))
                .editable(Objects.equals(operation.getOperationType().getIsTypeEditable(), "Y"))
                .build();

        List<BulkOperationAccessInfo> actionsAvailability = AllowedActionsEvaluator.evaluate(List.of(
                new UpdateRule(messageResolver),
                new DeleteRule(messageResolver)
        ), context);
        
        ListResult result = new ListResult();
        result.setItems(actionsAvailability);
        return result;
    }

    @Override
    public ListResult getOperationsAllowedActions(Object body, Object headers) throws Exception {
        logger.debug("getOperationsAllowedActions start");
        InputFromCamel input = new InputFromCamel(headers, body, null, logger);
        Long bulkRequestId = getIdFromMap(input.body, "bulkRequestId", logger)
                .orElseThrow(() -> new WebCamelException(messageResolver.getMessage(input.languageId,
                        "BulkUtils.checkRequiredParameters.error.parameterIsMissing", "bulkRequestId"),
                        "InvalidParameter", 400));
        BulkRequest bulkRequest = bulkRequestDao.findById(bulkRequestId)
                .orElseThrow(() -> new WebCamelException(messageResolver.getMessage(input.languageId,
                        "RequestService.checkRequest.error.objectNotFound", bulkRequestId),
                        "ObjectNotFound", 404));
        //права по супервизору
        String currentUser = input.headers.get(Constants.EXECUTE_USER_CAMEL_HEADER).toString();
        if (!bulkRequest.getNaviUser().equals(currentUser)
                && !accessControlUtil.isSupervisor(input.identity)) {
            throw new WebCamelException(messageResolver.getMessage(input.languageId,
                    "RequestService.checkRequest.error.objectNotFound", bulkRequestId),
                    "ObjectNotFound", 404);
        }

        Integer allowed = 1;
        OperationsAccessActionContext context = OperationsAccessActionContext.builder()
                .accessSubjType(OperationsAccessSubjectType.REQUEST)
                .languageId(input.languageId)
                .allowRequestCreate(allowed.equals(input.body.get("allowRequestCreate")))
                .allowRequestDelete(allowed.equals(input.body.get("allowRequestDelete")))
                .bulkRequestStatus(bulkRequest.getStatusId())
                .build();

        List<BulkOperationAccessInfo> actionsAvailability = AllowedActionsEvaluator.evaluate(List.of(
                new AddRule(messageResolver),
                new UpdateRule(messageResolver),
                new DeleteRule(messageResolver)
        ), context);
        
        ListResult result = new ListResult();
        result.setItems(actionsAvailability);
        return result;
    }
```
По итогу имеем следующие "декларации" (упрощенно): 
1. создай контекст для реквеста;
2. определи доступность действий добавить, изменить, удалить.
3. создай контекст для операции; 
4. определи доступность действий изменить, удалить.

С данным фрагментом кода провозился неожиданно долго - несколько раз запутывался и откатывался с изменениями. По итогу где-то наверное часов 6 потратил. В общем, наверное, не совсем доволен результатом. Хотя думаю, что некоторые проблемы, озвученные выше в рефлексии по коду, устранены.
Из основного: главное, наверное, что контекст заполняется вне связи с правилами (т.е. все еще можно получать данные, которые не будут использованы при определении доступности действия). Я честно попробовал это объединить, но получался монстр. 
Кроме этого остались несколько мест с дублированием, часть кода надо довынести в отдельные методы (хотя бы для улучшения читаемости). И вообще, чувствую, недокрутил - в мозгу картинка результата немного другая была, красивее. Но при этом в ходе работы получил прям хорошее такое удовольствие...

Вторая итерация: метод проверки данных выполнения и загрузки
```java
@Override
    public void checkLoadRequestData(final Long requestId, final Locale locale) throws AppException {
        List<Operation> requestOperations = operationDao.findByRequest(requestId);
        LOG.debug("checkLoadRequestData got request active operations: {}", requestOperations);
        boolean isFirstOperNoNeedCheck = requestOperations.stream()
                .anyMatch(op -> op.getStepNumber() == 1 && "N".equals(op.getOperationType().getIsNeedCheck()));
        Optional<Operation> signalOperation = requestOperations.stream()
                .filter(op -> op.getOperationType() != null
                        && "N".equals(op.getOperationType().getIsDuplicateAllowed()))
                .findFirst();
        if (!isFirstOperNoNeedCheck && signalOperation.isPresent()) {
            LOG.debug("checkLoadRequestData found duplicates-restricted operation. Duplicates clearing needed.");
            String categoryCode = signalOperation.get().getOperationType().getObjectCategoryCode();
            // от категории зависит не так уж много. partition-clause и условия удаления load_data.
            if (!"CUSTOMER".equals(categoryCode) && !"SUBSCRIBER".equals(categoryCode)) {
                LOG.warn("unexpected category of operation: {}", signalOperation.get());
                LOG.info("SUBSCRIBER category will be used as default");
                categoryCode = "SUBSCRIBER";
            }
            String errorMessageForDB;
            String errorCodeForDB;
            if ("SUBSCRIBER".equals(categoryCode)) {
                errorMessageForDB = lbl.msg("StdRunService.checkRunData.subscriberDuplicated");
                errorCodeForDB = "SubscriberDublicate";
            } else {
                errorMessageForDB = lbl.msg("StdRunService.checkRunData.customerDuplicated");
                errorCodeForDB = "CustomerDublicate";
            }
            List<RunDataCheck> duplicatedData;
            List<Long> runDataToDelete;
            List<Long> loadDataToDelete;

            LOG.debug("start process of duplicates removing");
            try {
                while (true) {
                    duplicatedData = runDataDao.getDuplicated(requestId, categoryCode, cfg.getRequestCheckingBatchSize());
                    if (duplicatedData.isEmpty()) {
                        break;
                    }
                    runDataToDelete = new ArrayList<>();
                    loadDataToDelete = new ArrayList<>();
                    for (RunDataCheck dupItem : duplicatedData) {
                        if (dupItem.getRowNumber() < dupItem.getCount()){
                            //дублирующие runData удаляются всегда
                            runDataToDelete.add(dupItem.getRunDataId());
                            //с loadData посложнее. Нельзя удалять при возможной ситуации 1-к-N
                            if ("CUSTOMER".equals(categoryCode) ||
                                    dupItem.getLoadDataSubscriberId() != null ||
                                    dupItem.getLoadDataMsisdn() != null) {
                                loadDataToDelete.add(dupItem.getLoadDataId());
                            }
                        }
                    }
                    runDataDao.markError(requestId, runDataToDelete, RunData.STATUS_ERR,
                            loadDataToDelete, LoadData.STATUS_CHECK_ERR,
                            errorMessageForDB, errorCodeForDB);
                }
                //единый апдейт load_data. Для режима CUSTOMER никогда не нужен
                if (!"CUSTOMER".equals(categoryCode)) {
                    LOG.debug("checkLoadRequestData is mass updating loadData by corresponding runData...");
                    loadDataDao.markErrorsByRunData(requestId, RunData.STATUS_ERR, LoadData.STATUS_CHECK_ERR,
                            errorMessageForDB, errorCodeForDB);
                    LOG.debug("checkLoadRequestData mass updatе loadData completed.");
                }
            } catch (SQLException e) {
                if (ErrorUtil.isResourceException(e)) {
                    throw new ResourceUnavailableException("Error performing checkLoadRequestData", e);
                } else {
                    throw new AppException("Error performing checkLoadRequestData", e);
                }
            }
            LOG.debug("end process of duplicates removing in request id={}",requestId);
        }

        //3. Всем неошибочным BULK_LOAD_DATA проставляется статус успешных.
        LOG.debug("start mass mark non-error load_data of request={}", requestId);
        try {
            loadDataDao.finalMarkCheckSuccess(requestId, cfg.getRequestCheckingBatchSize());
        } catch (SQLException e) {
            if (ErrorUtil.isResourceException(e)) {
                throw new ResourceUnavailableException("Error performing finalMarkCheckSuccess", e);
            } else {
                throw new AppException("Error performing finalMarkCheckSuccess", e);
            }
        }
        LOG.debug("all non-error load_data of request={} marked as success", requestId);

        //4. По наличию любых еррорных BULK_LOAD_DATA определяется общий статус проверки реквеста.
        Boolean errorsInRequest = loadDataDao.isErrorsByRequest(requestId);
        LOG.debug("checkLoadRequestData isErrorsByRequest check for request={} returned: {}",
                requestId, errorsInRequest);
        //В dao нет обычного селекта, вариант с for-update вполне подходит, удачная блокировка просто тут же
        // отпустится. По неудачной будет один из mybatis exception, они все из рантаймовых, к DataAccessException
        // никак не относятся. В общем, как-то оно до этого работало, и так же оставляю.
        LoadRequest requestDB = loadRequestDao.lockById(requestId);
        requestDB.setCheckTypeId(errorsInRequest ? LoadRequest.CHECK_TYPE_CHECK_ERR :
                LoadRequest.CHECK_TYPE_CHECK_DONE);
        requestDB.setCheckDate(DateFactory.getCurrentDate());
        requestDB.setUpdateDate(DateFactory.getCurrentDate());

        this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                loadRequestDao.updateCheckState(requestDB);
            }
        });
        LOG.debug("checkLoadRequestData for request id={} successfully completed", requestId);
    }
```
Логический дизайн кода:
- Метод осуществляет проверку данных загрузки для задачи (request) на выполнение групповых операций.
- Проверяется дубликаты по сущностям "SUBSCRIBER" и "CUSTOMER" в зависимости от типа операций.
- Дубликаты помечаются как ошибочные
- Оставшиеся записи помечаются как успешно прошедшие проверку
- Определение и запись общего статуса проверки

Соответствие кода дизайну: низкое. 
Императивный код, дублирование логики блокировок и обработки ошибок, фактические 4 этапа работы метода не имеют явного "выделения" в коде (собственно только комментариями разделены логически блоки. Комментариев много - часть я даже убрал. Это лишний раз указывает на проблему с кодом). 
Код сложно читать, поддерживать и развивать - добавление новой категории потребует серьезной переработки метода.

Соответственно как минимум данный код надо разделить на несколько небольших методов, каждый со своей ответственностью. И по итогу декларативно перечислить пункты логического дизайна:
```java
public interface DuplicateCheckingStrategy {

    String errorMessageKey();
    String errorCode();
    boolean requiresFinalLoadDataUpdate();
    boolean shouldDeleteLoadDataImmediately(RunDataCheck item);
}

public enum DuplicateCategory {
    CUSTOMER(new CustomerDuplicateProcessingStrategy()),
    SUBSCRIBER(new SubscriberDuplicateProcessingStrategy());

    private final DuplicateCheckingStrategy strategy;
    DuplicateCategory(DuplicateCheckingStrategy strategy) {
        this.strategy = strategy;
    }

    public DuplicateCheckingStrategy getStrategy() {
        return strategy;
    }

    public static Optional<DuplicateCategory> fromString(String value) {
        if (value == null) {
            return Optional.of(SUBSCRIBER);
        }
        return Arrays.stream(values())
                .filter(category -> category.name().equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}

public class CustomerDuplicateProcessingStrategy implements DuplicateCheckingStrategy {

    @Override
    public String errorMessageKey() {
        return "StdRunService.checkRunData.customerDuplicated";
    }

    @Override
    public String errorCode() {
        return "CustomerDublicate";
    }

    @Override
    public boolean requiresFinalLoadDataUpdate() {
        return false;
    }

    @Override
    public boolean shouldDeleteLoadDataImmediately(RunDataCheck item) {
        return true;
    }
}

public class SubscriberDuplicateProcessingStrategy implements DuplicateCheckingStrategy {

    @Override
    public String errorMessageKey() {
        return "StdRunService.checkRunData.subscriberDuplicated";
    }

    @Override
    public String errorCode() {
        return "SubscriberDublicate";
    }

    @Override
    public boolean requiresFinalLoadDataUpdate() {
        return true;
    }

    @Override
    public boolean shouldDeleteLoadDataImmediately(RunDataCheck item) {
        return item.getLoadDataSubscriberId() != null || item.getLoadDataMsisdn() != null;
    }
}

public class DuplicateCheck {

    public boolean isNeedDuplicateCheck(List<Operation> requestOperations) {
        boolean isFirstOperNoNeedCheck = requestOperations.stream()
                .anyMatch(op -> op.getStepNumber() == 1 && "N".equals(op.getOperationType().getIsNeedCheck()));
        Optional<Operation> signalOperation = requestOperations.stream()
                .filter(op -> op.getOperationType() != null
                        && "N".equals(op.getOperationType().getIsDuplicateAllowed()))
                .findFirst();
        return !isFirstOperNoNeedCheck && signalOperation.isPresent();
    }

    public DuplicateCategory getCategory(List<Operation> requestOperations) {
        Optional<Operation> signalOperation = requestOperations.stream()
                .filter(op -> op.getOperationType() != null
                        && "N".equals(op.getOperationType().getIsDuplicateAllowed()))
                .findFirst();
        String categoryCode = signalOperation.get().getOperationType().getObjectCategoryCode();
        return DuplicateCategory.fromString(categoryCode).orElse(DuplicateCategory.SUBSCRIBER);
    }

    public void removeDuplicates(Long requestId, RunDataDao runDataDao, LoadDataDao loadDataDao,
                                 DuplicateCheckingStrategy strategy, String categoryCode, int batchSize,
                                 LabelService lbl, Logger LOG) throws AppException {
        List<RunDataCheck> duplicatedData;
        List<Long> runDataToDelete;
        List<Long> loadDataToDelete;

        LOG.debug("start process of duplicates removing");
        try {
            while (true) {
                duplicatedData = runDataDao.getDuplicated(requestId, categoryCode, batchSize);
                if (duplicatedData.isEmpty()) {
                    break;
                }
                runDataToDelete = new ArrayList<>();
                loadDataToDelete = new ArrayList<>();
                for (RunDataCheck dupItem : duplicatedData) {
                    if (dupItem.getRowNumber() < dupItem.getCount()){
                        //дублирующие runData удаляются всегда
                        runDataToDelete.add(dupItem.getRunDataId());
                        //с loadData посложнее. Нельзя удалять при возможной ситуации 1-к-N
                        if (strategy.shouldDeleteLoadDataImmediately(dupItem)) {
                            loadDataToDelete.add(dupItem.getLoadDataId());
                        }
                    }
                }
                runDataDao.markError(requestId, runDataToDelete, RunData.STATUS_ERR,
                        loadDataToDelete, LoadData.STATUS_CHECK_ERR,
                        lbl.msg(strategy.errorMessageKey()), strategy.errorCode());
            }
            //единый апдейт load_data. Для режима CUSTOMER никогда не нужен
            if (strategy.requiresFinalLoadDataUpdate()) {
                LOG.debug("checkLoadRequestData is mass updating loadData by corresponding runData...");
                loadDataDao.markErrorsByRunData(requestId, RunData.STATUS_ERR, LoadData.STATUS_CHECK_ERR,
                        lbl.msg(strategy.errorMessageKey()), strategy.errorCode());
                LOG.debug("checkLoadRequestData mass update loadData completed.");
            }
        } catch (SQLException e) {
            if (ErrorUtil.isResourceException(e)) {
                throw new ResourceUnavailableException("Error performing checkLoadRequestData", e);
            } else {
                throw new AppException("Error performing checkLoadRequestData", e);
            }
        }
    }
}

//изменения в самом классе:
public void checkLoadRequestDataNew(final Long requestId, final Locale locale) throws AppException {
    List<Operation> requestOperations = operationDao.findByRequest(requestId);
    LOG.debug("checkLoadRequestData got request active operations: {}", requestOperations);

    DuplicateCheck duplicateCheck = new DuplicateCheck();
    if (duplicateCheck.isNeedDuplicateCheck(requestOperations)) {
        DuplicateCategory category = duplicateCheck.getCategory(requestOperations);
        DuplicateCheckingStrategy strategy = category.getStrategy();
        duplicateCheck.removeDuplicates(requestId, runDataDao, loadDataDao, strategy,
                category.name(), cfg.getRequestCheckingBatchSize(), lbl, LOG);
    }

    markNonErrorRowsAfterCheck(requestId);
    updateRequestCheckStatus(requestId);
}

private void markNonErrorRowsAfterCheck(Long requestId) throws AppException {
    //3. Всем неошибочным BULK_LOAD_DATA проставляется статус успешных.
    LOG.debug("start mass mark non-error load_data of request={}", requestId);
    try {
        loadDataDao.finalMarkCheckSuccess(requestId, cfg.getRequestCheckingBatchSize());
    } catch (SQLException e) {
        if (ErrorUtil.isResourceException(e)) {
            throw new ResourceUnavailableException("Error performing finalMarkCheckSuccess", e);
        } else {
            throw new AppException("Error performing finalMarkCheckSuccess", e);
        }
    }
    LOG.debug("all non-error load_data of request={} marked as success", requestId);
}

private void updateRequestCheckStatus(Long requestId) {
    //4. По наличию любых еррорных BULK_LOAD_DATA определяется общий статус проверки реквеста.
    Boolean errorsInRequest = loadDataDao.isErrorsByRequest(requestId);
    LOG.debug("checkLoadRequestData isErrorsByRequest check for request={} returned: {}",
            requestId, errorsInRequest);
    //В dao нет обычного селекта, вариант с for-update вполне подходит, удачная блокировка просто тут же
    // отпустится. По неудачной будет один из mybatis exception, они все из рантаймовых, к DataAccessException
    // никак не относятся. В общем, как-то оно до этого работало, и так же оставляю.
    LoadRequest requestDB = loadRequestDao.lockById(requestId);
    requestDB.setCheckTypeId(errorsInRequest ? LoadRequest.CHECK_TYPE_CHECK_ERR :
            LoadRequest.CHECK_TYPE_CHECK_DONE);
    requestDB.setCheckDate(DateFactory.getCurrentDate());
    requestDB.setUpdateDate(DateFactory.getCurrentDate());

    this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
        @Override
        protected void doInTransactionWithoutResult(TransactionStatus status) {
            loadRequestDao.updateCheckState(requestDB);
        }
    });
    LOG.debug("checkLoadRequestData for request id={} successfully completed", requestId);
}
```

С этим кодом справился быстрее, но все равно  - суммарно где-то около трех часов (может 3.5).
По итогу получился достаточно компактный основной метод, насколько я могу судить, соответствующий логическому дизайну.
Метод checkLoadRequestDataNew стал декларативным – он перечисляет этапы, которые нужно выполнить. А каждый этап – это отдельный класс с однозначной ответственностью.
Теперь более понятно (ну по крайней мере мне сейчас) что делает данный метод (который основной). 
Явно прописаны стратегии - теперь добавить новую или изменить существующие гораздо легче. Т.е. в целом задание выполнено.

Третий фрагмент из того же класса, несколько методов работы с сущностью runData (данные запуска):
```java
@Override
    public void updateRunDataState(final List<RunData> runDataList, final Locale locale) throws AppException {
        final ExceptionHolder<AppException> exceptionHolder = new ExceptionHolder<>();
        try {
            this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    try {
                        RunData locked = null;
                        try {
                            for (RunData rd : runDataList) {
                                locked = runDataDao.lockById(rd.getId(), rd.getRequestId());
                                if (locked == null) {
                                    // Данные не найдены
                                    throw new AppException(
                                            lbl.msg("StdRunService.updateRunDataState.notFound", rd.getId()));
                                }
                            }
                        } catch (DataAccessException e) {
                            //переповторы во внешней логике акторов
                            throw new LockAcquireException(e);
                        }

                        // Непосредственное изменение состояния данных
                        for (RunData rd : runDataList) {
                            rd.setRunnerId(zooMasterService.currentRunnerInstanceId());
                            // в дао пока оставлены штучные вызовы. Пока нужна в первую очередь транзакция, а не оптимизации.
                            runDataDao.updateState(rd);
                        }
                    } catch (DataAccessException e) {
                        status.setRollbackOnly();
                        if (ErrorUtil.isResourceException(e)) {
                            exceptionHolder.setException(new ResourceUnavailableException(lbl.msg(locale,
                                    "StdRunService.updateRunDataState.error.resource", runDataList.get(0).getId(), e)));
                        } else {
                            exceptionHolder.setException(new AppException(lbl.msg(locale,
                                    "StdRunService.updateRunDataState.error", runDataList.get(0).getId(), e)));

                        }
                    } catch (AppException e) {
                        status.setRollbackOnly();
                        exceptionHolder.setException(e);
                    }
                }
            });
        } catch (TransactionException e) {
            if (ErrorUtil.isResourceException(e)) {
                exceptionHolder.setException(new ResourceUnavailableException(lbl.msg(locale,
                        "StdRunService.updateRunDataState.transactionError.resource", e)));
            } else {
                exceptionHolder.setException(new AppException(lbl.msg(locale,
                        "StdRunService.updateRunDataState.transactionError", e)));
            }

        }

        if (exceptionHolder.isNotEmpty()) {
            throw exceptionHolder.getException();
        }
    }

    @Override
    public List<RunData> findExecutingRunData(final Long operationId, final Long requestId, final Locale locale) throws AppException {
        final ExceptionHolder<AppException> exceptionHolder = new ExceptionHolder<>();
        List<RunData> res = null;
        try {
            res = this.transactionTemplate.execute(new TransactionCallback<List<RunData>>() {
                @Override
                public List<RunData> doInTransaction(TransactionStatus status) {
                    try {
                        return runDataDao.findExecuting(operationId, requestId);
                    } catch (DataAccessException e) {
                        status.setRollbackOnly();
                        if (ErrorUtil.isResourceException(e)) {
                            exceptionHolder.setException(new ResourceUnavailableException(
                                    lbl.msg(locale, "StdRunService.readExecuting.error.resource", operationId, e), e));
                        } else {
                            exceptionHolder.setException(new AppException(
                                    lbl.msg(locale, "StdRunService.readExecuting.error", operationId, e), e));
                        }
                        return null;
                    }
                }
            });
        } catch (TransactionException e) {
            if (ErrorUtil.isResourceException(e)) {
                throw new ResourceUnavailableException(lbl.msg(locale,
                        "StdRunService.readExecuting.transactionError.resource", e), e);
            } else {
                throw new AppException(lbl.msg(locale,
                        "StdRunService.readExecuting.transactionError", e), e);
            }
        }

        if (exceptionHolder.isNotEmpty()) {
            throw exceptionHolder.getException();
        }

        return res;
    }

@Override
public List<RunData> readNextRunDataToExecute(final Long operationId, final Long requestId, final Long minId, final int maxSize,
                                              Locale locale) throws AppException {
    final ExceptionHolder<AppException> exceptionHolder = new ExceptionHolder();
    List<RunData> res = null;
    try {
        res = this.transactionTemplate.execute(new TransactionCallback<List<RunData>>() {
            @Override
            public List<RunData> doInTransaction(TransactionStatus status) {
                try {
                    return runDataDao.findNextToExecute(operationId, requestId, minId, maxSize);
                } catch (DataAccessException e) {
                    if (ErrorUtil.isResourceException(e)) {
                        exceptionHolder.setException(new ResourceUnavailableException(
                                lbl.msg("StdRunService.readNextRunDataToExecute.error.resource", e)));
                    } else {
                        status.setRollbackOnly();
                        exceptionHolder.setException(new AppException(
                                lbl.msg("StdRunService.readNextRunDataToExecute.error", e)));
                    }
                    return null;
                }
            }
        });
    } catch (TransactionException e) {
        if (ErrorUtil.isResourceException(e)) {
            exceptionHolder.setException(new ResourceUnavailableException(lbl.msg(locale,
                    "StdRunService.readNextRunDataToExecute.error.transaction", e), e));
        } else {
            exceptionHolder.setException(new AppException(lbl.msg(locale,
                    "StdRunService.readNextRunDataToExecute.error.transaction", e), e));
        }
    }

    if (exceptionHolder.isNotEmpty()) {
        throw exceptionHolder.getException();
    }

    return res;
}    
```
Логический дизайн кода:
Для управления данными запуска (получение, редактирование) используются транзакции.
Если операция требует изменения данных, она сначала блокирует необходимые строки.
При возникновении ошибок, связанных с недоступностью ресурсов (LockAcquireException, DataAccessException с признаком isResourceException) операция повторяется определённое количество раз с задержкой.
Все остальные ошибки (бизнес-ошибки, отсутствие данных) сразу преобразуются в соответствующие AppException или ResourceUnavailableException
Данная последовательность шагов характерна для всех методов работы с сущностью runData у данного класса.

Имеющийся код запутан, в методе смешаны уровни непосредственно бизнес-логики, управления транзакциями, блокировки, обработка исключений. Дизайна за всем этим не видно. 
При добавлении нового метода с новым действием над runData легко потерять какой-либо из шагов (например, блокировку), ошибиться в обработке exceptions или напутать с ожиданием.
В похожем стиле в классе еще несколько методов. Хочется оформить данные методы с помощью единого стандартизированного подхода. Мне показалось это подходящим для данного задания.

Итоговые изменения по третьей итерации:
```java
package com.peterservice.oapi.subsloader.bulkrunner.service.ctrl.std.transactional;

import com.peterservice.oapi.groupops.backend.services.config.LabelService;
import com.peterservice.oapi.subsloader.bulkrunner.util.error.AppException;
import com.peterservice.oapi.subsloader.bulkrunner.util.error.ErrorUtil;
import com.peterservice.oapi.subsloader.bulkrunner.util.error.LockAcquireException;
import com.peterservice.oapi.subsloader.bulkrunner.util.error.ResourceUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

public abstract class TransactionalOperation<T> {
    private final TransactionTemplate transactionTemplate;
    protected final LabelService lbl;
    protected final Locale locale;

    public TransactionalOperation(TransactionTemplate template, LabelService lbl, Locale locale) {
        this.transactionTemplate = template;
        this.lbl = lbl;
        this.locale = locale;
    }

    protected abstract T doInTransaction(TransactionStatus status) throws AppException;

    protected void handleDataAccessException(Throwable e, String operationName, String errorType) throws AppException {
        if (ErrorUtil.isResourceException(e)) {
            throw new ResourceUnavailableException(lbl.msg(locale, operationName + "." + errorType + "resource", e), e);
        } else {
            throw new AppException(lbl.msg(locale, operationName + "." + errorType, e), e);
        }
    }

    public T execute(String operationName) throws AppException {
        try {
            return transactionTemplate.execute(status -> {
                try {
                    return doInTransaction(status);
                } catch (AppException | DataAccessException e) {
                    status.setRollbackOnly();
                    throw new RuntimeException(e);
                }
            });
        } catch (TransactionException e) {
            handleDataAccessException(e, operationName, "transactionError");
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LockAcquireException) {
                // переповторы во внешней логике акторов
                throw (LockAcquireException) cause;
            } else if (cause instanceof AppException) {
                throw (AppException) cause;
            } else if (cause instanceof DataAccessException) {
                handleDataAccessException(cause, operationName, "error");
            } else {
                throw new AppException(lbl.msg(locale, operationName + ".unknownError", cause), cause);
            }
        }
        return null;
    }
}

public class FindExecutingRunDataOperation extends TransactionalOperation<List<RunData>> {
    private final Long operationId;
    private final Long requestId;
    private final RunDataDao runDataDao;

    public FindExecutingRunDataOperation(TransactionTemplate template, LabelService lbl, Locale locale,
                                         Long operationId, Long requestId, RunDataDao runDataDao) {
        super(template, lbl, locale);
        this.operationId = operationId;
        this.requestId = requestId;
        this.runDataDao = runDataDao;
    }

    @Override
    protected List<RunData> doInTransaction(TransactionStatus status) throws AppException {
        return runDataDao.findExecuting(operationId, requestId);
    }
}

public class UpdateRunDataStateOperation extends TransactionalOperation<Void> {
    private final List<RunData> runDataList;
    private final RunDataDao runDataDao;
    private final ZooMasterService zooMasterService;

    public UpdateRunDataStateOperation(TransactionTemplate template, LabelService lbl, Locale locale,
                                       List<RunData> runDataList, RunDataDao runDataDao,
                                       ZooMasterService zooMasterService) {
        super(template, lbl, locale);
        this.runDataList = runDataList;
        this.runDataDao = runDataDao;
        this.zooMasterService = zooMasterService;
    }

    @Override
    protected Void doInTransaction(TransactionStatus status) throws AppException {
        for (RunData rd : runDataList) {
            RunData locked = runDataDao.lockById(rd.getId(), rd.getRequestId());
            if (locked == null) {
                throw new AppException(lbl.msg(locale, "StdRunService.updateRunDataState.notFound", rd.getId()));
            }
        }
        for (RunData rd : runDataList) {
            rd.setRunnerId(zooMasterService.currentRunnerInstanceId());
            runDataDao.updateState(rd);
        }
        return null;
    }
}

public class ReadNextRunDataOperation extends TransactionalOperation<List<RunData>> {
    private final Long operationId;
    private final Long requestId;
    private final Long minId;
    private final int maxSize;
    private final RunDataDao runDataDao;

    public ReadNextRunDataOperation(TransactionTemplate template, LabelService lbl, Locale locale,
                                    Long operationId, Long requestId, Long minId, int maxSize,
                                    RunDataDao runDataDao) {
        super(template, lbl, locale);
        this.operationId = operationId;
        this.requestId = requestId;
        this.minId = minId;
        this.maxSize = maxSize;
        this.runDataDao = runDataDao;
    }

    @Override
    protected List<RunData> doInTransaction(TransactionStatus status) throws AppException {
        return runDataDao.findNextToExecute(operationId, requestId, minId, maxSize);
    }
}

// изменения в основном классе:
public void updateRunDataStateNew(final List<RunData> runDataList, final Locale locale) throws AppException {
    new UpdateRunDataStateOperation(transactionTemplate, lbl, locale, runDataList,
            runDataDao, zooMasterService)
            .execute("StdRunService.updateRunDataState");
}

public List<RunData> readNextRunDataToExecuteNew(final Long operationId, final Long requestId,
                                                 final Long minId, final int maxSize, Locale locale) throws AppException {
    return new ReadNextRunDataOperation(transactionTemplate, lbl, locale,
            operationId, requestId, minId, maxSize,
            runDataDao)
            .execute("StdRunService.readNextRunDataToExecute");
}

public List<RunData> findExecutingRunDataNew(final Long operationId, final Long requestId, Locale locale) throws AppException {
    return new FindExecutingRunDataOperation(transactionTemplate, lbl, locale,
            operationId, requestId, runDataDao)
            .execute("StdRunService.findExecutingRunData");
}
```
Последняя итерация заняла чуть больше, где-то наверное ближе к 4 часам - почему-то долго провозился с TransactionalOperation. На самом деле думал быстрее справлюсь. Вообще думал, что с каждой итерацией будет хороший прирост скорости, но пока не случилось. 

Теперь любая операция, которая должна выполняться в транзакции с поддержкой блокировок, должна будет использовать либо готовые классы операций, либо новый класс, наследующий TransactionalOperation. 
Нельзя написать другую реализацию, не нарушая этот шаблон, не дублируя логику обработки ошибок. Т.е., по сути, теперь дизайн заставляет писать код именно так.
Вся логика повторов вынесена в execute() и не смешана с бизнес-логикой. Обработка ошибок, связанных с ресурсами ушла в handleDataAccessException.

В целом, если подытожить все задание целиком - кажется я теперь гораздо лучше разобрался в трех материалах по логическим уровням рассуждения о программе, чем это было раньше (хотя я читал эти материалы несколько раз). 
Все таки практика - это сила! (правда я не полностью уверен в том, что последняя итерация хорошо подходит для данного задания, ну значит еще есть куда расти).