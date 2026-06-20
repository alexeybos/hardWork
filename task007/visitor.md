Как ни странно, для данного задания оказалось достаточно сложно найти код для задания. Поэтому возможно найденный пример не самый подходящий, но в какой-то момент уже отчаялся найти лучше.
Данные классы представляют собой базовый класс валидатора перемещения сим-карт и классов-наследников конкретных валидаторов (между дилерами и между складами):
Классы здесь даны в укороченном варианте:
```java
/**
 * Проверка на возможность перемещения
 */

public class SIMTransAtValidator implements TransAtValidator {
    private final String caller = getClass().getSimpleName();
    private static final Logger LOGGER = LoggerFactory.getLogger(SIMTransAtValidator.class);
    protected final Configuration configuration;
    private final SimGroupsValidator simGroupsValidator;
    private final FeatureToggleService featureToggleService;

    public SIMCardsTransAtValidator(
            Configuration configuration,
            SimGroupsValidator simGroupsValidator,
            FeatureToggleService featureToggleService
    ) {
        this.configuration = configuration;
        this.simGroupsValidator = simGroupsValidator;
        this.featureToggleService = featureToggleService;
    }

    /**
     * Проверка возможности перемещения
     *
     */
    @Override
    public void validate (Task task, List<TaskItem> items, ICommonPartner partner, Map<String, Object> headers, Exchange exchange, Integer languageId) throws ErrorDescriptionException {
        String dbType = "tspt";
        Long warehouseId = null;
        Map<Long, SimState> simCardStates = tsptHelper.getSimStates(Arrays.asList(0L, task.getMacroRegion().getMacroRegionId()));
        Boolean simMoveRestrictionsEnabled = isSimMoveRestrictionEnable(task.getTaskId());
        Set<Long> allowedKitTemplateIds = simMoveRestrictionsEnabled ? getAllowedKitTemplatesForPartner(partner.getId()) : null;

        boolean isTestPartner = isTestPartner(partner.getId(), task);
        Boolean isDynamicSim = null;

        for ( TaskItem item: items) {
            // переведем симку в состояние готовности
            validateSimWarehouse(task, item, warehouseId, partner.getId(), languageId, dbType, exchange);
            checkIdempotency(task, item, partner, dbType, languageId);
            ...
        }

        // Проверим, что в задании присутствуют все SIM-карты групп
        if (featureToggleService.simCardGroupsEnable()) {
            simGroupsValidator.validate(task, items, languageId);
        }

        DynamicSimPartnerHierarchyCheckParams dynamicSimPartnerHierarchyCheckParams = featureToggleService.getDynamicSimPartnerHierarchyCheckParams();
        if (!isTestPartner && Boolean.TRUE.equals(isDynamicSim) && dynamicSimPartnerHierarchyCheckParams.isEnabled()) {
            validateDynamicSimPartnerHierarchy(task, items, partner, dynamicSimPartnerHierarchyCheckParams, languageId);
        }
    }

    String getSimStateName(Long simStateId, Map<Long, SimState> simCardStates) {
        SimState conflictSimState = simCardStates.get(simStateId);
        return conflictSimState != null ? conflictSimState.getName() : null;
    }

    /**
     * Исключение детали операции из дальнейшей обработки вследствии наличия ошибки в результате проверки
     *
     * @param task операция
     * @param item деталь операции
     * @param conflict конфликт
     * @param errorMessage текст ошибки
     * @param languageId язык локализации
     */
    void skipItem (Task task, TaskItem item, SimMoveConflict conflict, String errorMessage, Integer languageId) throws SetTaskItemStateException {
        skipItem(task,item, conflict.getCode(), errorMessage, languageId);
    }

    void skipItem (Task task, TaskItem item, String conflictCode, String errorMessage, Integer languageId) throws SetTaskItemStateException {
        item.addError(new TaskError(task.getTaskId(), item.getTaskItemId(), conflictCode, errorMessage));
        TaskHelper.setItemState(caller, task, item, SKIPPED, languageId);
    }

    void validateSimWarehouse(Task task, TaskItem item, Long warehouseId, Long partnerId, Integer languageId, String dbType, Exchange exchange) throws SetTaskItemStateException {
        Long currentSimWarehouseId = item.getSimCard().getAgent().getAgentId();
        if (!warehouseId.equals(currentSimWarehouseId) && !partnerId.equals(currentSimWarehouseId)) {
            skipItem(task, item, NOT_IN_SAME_WAREHOUSE, MessageFactory.getNotInSameWarehouseError(dbType, languageId), languageId);
        }
    }

    LogisticOperationTypeEnum getLogisticOperationType() {
        return null;
    }

    void validateSimState(Task task, TaskItem item, ICommonPartner partner, String dbType, Integer languageId, Map<Long, SimState> simCardStatesMap) throws SetTaskItemStateException {
        if (item.getState().equalsTo(READY)) {
            if (!ALLOWED_SIM_STATES.contains(item.getSimCard().getState().getId())) {
                skipItem(task, item, SIM_WRONG_STATUS, MessageFactory.getSIMWrongStatusMessage(dbType, getSimStateName(item.getSimCard().getState().getId(), simCardStatesMap), languageId), languageId);
            }
        }
    }

    void validateRestrictions(Task task, TaskItem item, String dbType, Integer languageId, Set<Long> allowedKitTemplateIds) throws SetTaskItemStateException {
        if (ALLOWED_KIT_SIM_STATES.contains(item.getSimCard().getState().getId())) {
            TariffTemplateShort kitTemplate = item.getSimCard().getTariffTemplate();
            if (kitTemplate == null || kitTemplate.getId() == null) {
                skipItem(task, item, SIM_ATTRIBUTE_IS_EMPTY, MessageFactory.getSimAttributeEmptyError(dbType, item.getSimCard().getId(), "tariffTemplate", languageId), languageId);
            } else if (!allowedKitTemplateIds.contains(kitTemplate.getId())) {
                skipItem(task, item, SIM_WRONG_KIT_TEMPLATE, MessageFactory.getSIMForbiddenKitTemplateMessage(dbType, kitTemplate.getName(), languageId), languageId);
            }
        }
    }

    Boolean isSimMoveRestrictionEnable(Integer taskId) {
        Boolean simMoveRestrictionsEnabled = Converter.booleanValueOf(configuration.getProperty(SIM_MOVE_RESTRICTIONS_ENABLED_PATH));
        LOGGER.debug("Task {}. Parameter {} is {}", taskId, SIM_MOVE_RESTRICTIONS_ENABLED_PATH, simMoveRestrictionsEnabled);
        return simMoveRestrictionsEnabled;
    }

    Set<Long> getAllowedKitTemplatesForPartner(Long partnerId) {
        List<RestrictionPartnerKitTemplate> restrictions = simMoveService.getRestrictionsPartnerKitTemplate(new HashMap<String, Object>(){{put(PARTNER_ID, partnerId);}});
        return CollectionUtils.isNotEmpty(restrictions) ? restrictions.stream().map(RestrictionPartnerKitTemplate::getKitTemplateId).collect(Collectors.toSet()) : Collections.emptySet();
    }

    protected boolean isTestPartner (Long partnerId, Task task) {
        List<Long> predicateTestPartnerIds = Collections.emptyList();
        boolean isTestAgentsFilterByPredicateEnable = configuration.isTestAgentsFilterByPredicateEnable();
        if (isTestAgentsFilterByPredicateEnable) {
            String predicatesProperty = task.getOptions().getProperty("predicates");
            if (StringUtils.isNotEmpty(predicatesProperty)) {
                try {
                    Map<String, List<Long>> predicates = Converter.jsonToObject(predicatesProperty, new TypeReference<Map<String, List<Long>>>() {
                    });
                    if (predicates.containsKey("OAPItsptTestAgents")) {
                        predicateTestPartnerIds = predicates.get("OAPItsptTestAgents");
                    }
                } catch (IOException e) {
                    LOGGER.error(Converter.getStringFromException(e));
                } finally {
                    predicateTestPartnerIds = predicateTestPartnerIds == null ? Collections.emptyList() : predicateTestPartnerIds;
                }
            }
        } else {
            String listOfTestDealersString = configuration.getProperty(LIST_OF_TEST_DEALERS_PATH);
            if (StringUtils.isNotBlank(listOfTestDealersString)) {
                predicateTestPartnerIds = Arrays.stream(listOfTestDealersString.split(COMMA_DELIMITER)).map(v -> Long.valueOf(v.trim())).collect(Collectors.toList());
            }
        }

        return predicateTestPartnerIds.contains(partnerId);
    }

}

public class TransToPartnerValidator extends SIMTransAtValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SIMCardsMoveToPartnerValidator.class);
    private final static Set<Long> ALLOWED_SIM_STATES = new HashSet<>(
            Arrays.asList(RECEIVED.getSimCardStateId(),
                    NOT_ASSOCIATED.getSimCardStateId(),
                    ASSOCIATED_WITH_NUMBER.getSimCardStateId(),
                    TRANSFERRED_TO_DEALER.getSimCardStateId())
    );
    private final static Set<Long> ALLOWED_KIT_SIM_STATES = new HashSet<>(
            Arrays.asList(ASSOCIATED_WITH_NUMBER.getSimCardStateId(), TRANSFERRED_TO_DEALER.getSimCardStateId()));
    private final SimMoveService simMoveService;

    public SIMCardsMoveToPartnerValidator(
            SimMoveService simMoveService,
            Configuration configuration,
            SimGroupsValidator simGroupsValidator,
            FeatureToggleService featureToggleService
    ) {
        super(configuration, simGroupsValidator, featureToggleService);
        this.simMoveService = simMoveService;
    }

    @Override
    void validateSimState(Task task, TaskItem item, ICommonPartner partner, String dbType, Integer languageId, Map<Long, SimState> simCardStatesMap) throws SetTaskItemStateException {
        if (item.getState().equalsTo(READY)) {
            if (!ALLOWED_SIM_STATES.contains(item.getSimCard().getState().getId())) {
                skipItem(task, item, SIM_WRONG_STATUS, MessageFactory.getSIMWrongStatusMessage(dbType, getSimStateName(item.getSimCard().getState().getId(), simCardStatesMap), languageId), languageId);
            }
        }
    }

    @Override
    LogisticOperationTypeEnum getLogisticOperationType() {
        return SIM_MOVE;
    }
}

public class TransPartner2PartnerValidator extends SIMTransAtValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SIMCardsMovePartner2PartnerValidator.class);
    private final String caller = getClass().getSimpleName();
    private final static Set<Long> ALLOWED_SIM_STATES = new HashSet<>(
            Arrays.asList(NOT_ASSOCIATED.getSimCardStateId(), TRANSFERRED_TO_DEALER.getSimCardStateId())
    );
    private final static Set<Long> ALLOWED_KIT_SIM_STATES = new HashSet<>(Collections.singletonList(TRANSFERRED_TO_DEALER.getSimCardStateId()));
    private final SimMoveService simMoveService;

    public SIMCardsMovePartner2PartnerValidator(
            SimMoveService simMoveService,
            Configuration configuration,
            SimGroupsValidator simGroupsValidator,
            FeatureToggleService featureToggleService
    ) {
        super(configuration, simGroupsValidator, featureToggleService);
        this.simMoveService = simMoveService;
    }

    @Override
    void validateSimState(Task task, TaskItem item, ICommonPartner partner, String dbType, Integer languageId, Map<Long, SimState> simCardStatesMap) throws SetTaskItemStateException {
        if (item.getState().equalsTo(READY)) {
            if (!ALLOWED_SIM_STATES.contains(item.getSimCard().getState().getId())) {
                skipItem(task, item, SIM_WRONG_STATUS, MessageFactory.getSIMWrongStatusMessage(dbType, getSimStateName(item.getSimCard().getState().getId(), simCardStatesMap), languageId), languageId);
            }
        }
    }

    void checkIdempotency(Task task, TaskItem item, ICommonPartner partner, String dbType, Integer languageId) throws SetTaskItemStateException {
        if (getLogisticOperationType() != null &&
                getLogisticOperationType().getStateMoveRoadMap().values().stream()
                        .anyMatch(s -> s.equalBy(item.getSimCard().getState())) &&
                partner.getId().equals(item.getSimCard().getAgent().getAgentId())) {
            TaskHelper.setItemState(caller, task, item, SCENARIO, languageId);
            TaskHelper.setItemState(caller, task, item, DONE, languageId);
        }
    }

    @Override
    void validateSimWarehouse(Task task, TaskItem item, Long warehouseId, Long partnerId, Integer languageId, String dbType, Exchange exchange) throws SetTaskItemStateException {
        if (!Boolean.parseBoolean(task.getOptions().getProperty("isAllowMoveFromDiffAgents"))) {
            Long currentSimWarehouseId = item.getSimCard().getAgent().getAgentId();
            if (!warehouseId.equals(currentSimWarehouseId) && !partnerId.equals(currentSimWarehouseId)) {
                skipItem(task, item, NOT_IN_SAME_WAREHOUSE, MessageFactory.getNotInSameWarehouseError(dbType, languageId), languageId);
            }
        }
    }

    @Override
    LogisticOperationTypeEnum getLogisticOperationType() {
        return SIM_MOVE_PARTNER_2_PARTNER;
    }

}
```
В итоге имеем базовый класс с реализацией основного поведения и дочерние классы, которые переопределяют ряд методов базового класса. 
Классов наследников несколько, я остановился на двух.
В рамках замены данной конструкции на применения паттерна Visitor внес такие изменения:
#### Интерфейс посетителя
```java
public interface SIMTransValidatorVisitor {
    void validateSimState(Task task, TaskItem item, ICommonPartner partner, String dbType, Integer languageId,
                          Map<Long, SimState> simCardStatesMap) throws SetTaskItemStateException;
    
    void validateSimWarehouse(Task task, TaskItem item, Long warehouseId, Long partnerId, Integer languageId,
                               String dbType, Exchange exchange) throws SetTaskItemStateException;
    
    LogisticOperationTypeEnum getLogisticOperationType();
}
```
#### Измененный базовый класс
```java
public class SIMTransAtValidator implements MoveValidator {
    
	private final SIMTransValidatorVisitor visitor;
	
    public SIMTransAtValidator(Configuration configuration, SimGroupsValidator simGroupsValidator, 
                               FeatureToggleService featureToggleService,SIMTransValidatorVisitor visitor
    ) {
        this.configuration = configuration;
        this.simGroupsValidator = simGroupsValidator;
        this.featureToggleService = featureToggleService;
        this.visitor = visitor;
    }
    @Override
    public void validate(Task task, List<TaskItem> items, ICommonPartner partner, Map<String, Object> headers, 
                         Exchange exchange, Integer languageId) throws ErrorDescriptionException {
        // тут я так понимаю без изменений
		//...
        for (TaskItem item : items) {
            // а вот тут уже visitor
            visitor.validateSimState(task, item, partner, dbType, languageId, simCardStates);
            visitor.validateSimWarehouse(task, item, warehouseId, partner.getId(), languageId, dbType, exchange);
        }
        //...
    }
    
    String getSimStateName(Long simStateId, Map<Long, SimState> simCardStates) {
        SimState conflictSimState = simCardStates.get(simStateId);
        return conflictSimState != null ? conflictSimState.getName() : null;
    }
	
	void skipItem (Task task, TaskItem item, SimMoveConflict conflict, String errorMessage, Integer languageId) throws SetTaskItemStateException {
        skipItem(task,item, conflict.getCode(), errorMessage, languageId);
    }

    void skipItem (Task task, TaskItem item, String conflictCode, String errorMessage, Integer languageId) throws SetTaskItemStateException {
        item.addError(new TaskError(task.getTaskId(), item.getTaskItemId(), conflictCode, errorMessage));
        TaskHelper.setItemState(caller, task, item, SKIPPED, languageId);
    }
	
	// ну и остальные общие методы
}
```
#### Конкретные посетители:
```java
public class TransToPartnerSimVisitor implements SIMTransValidatorVisitor {
    
    private static final List<Long> ALLOW_STATUSES = Arrays.asList(
        TRANSFERRED_TO_OO.getSimCardStateId(),
        NOT_ASSOCIATED.getSimCardStateId()
    );
    
    private final SIMTransAtValidator validator;
    
    public TransToPartnerSimVisitor(SIMTransAtValidator validator) {
        this.validator = validator;
    }
    
    @Override
    public void validateSimState(Task task, TaskItem item, ICommonPartner partner, String dbType, Integer languageId, 
                                 Map<Long, SimState> simCardStatesMap) throws SetTaskItemStateException {
        if (!ALLOW_STATUSES.contains(item.getSimCard().getState().getId())) {
            validator.skipItem(task, item, SIM_WRONG_STATUS, 
                    MessageFactory.getSIMWrongStatusMessage(dbType, validator.getSimStateName(item.getSimCard().getState().getId(), simCardStatesMap), languageId), 
                    languageId);
        }
    }
    
    @Override
    public void validateSimWarehouse(Task task, TaskItem item, Long warehouseId, Long partnerId, Integer languageId, 
                                     String dbType, Exchange exchange) throws SetTaskItemStateException {
        Long currentSimWarehouseId = item.getSimCard().getAgent().getAgentId();
        if (!warehouseId.equals(currentSimWarehouseId) && !partnerId.equals(currentSimWarehouseId)) {
            skipItem(task, item, NOT_IN_SAME_WAREHOUSE, 
                    MessageFactory.getNotInSameWarehouseError(dbType, languageId), languageId);
        }
    }
    
    @Override
    public LogisticOperationTypeEnum getLogisticOperationType() {
        return SIM_MOVE_FROM_SERVICE_DEP;
    }
}

public class Partner2PartnerSimVisitor implements SIMTransValidatorVisitor {
    private static final Set<Long> ALLOWED_SIM_STATES = new HashSet<>(
            Arrays.asList(NOT_ASSOCIATED.getSimCardStateId(), TRANSFERRED_TO_DEALER.getSimCardStateId())
    );
    private final SIMTransAtValidator validator;
    public Partner2PartnerSimVisitor(SIMTransAtValidator validator) {
        this.validator = validator;
    }
    @Override
    public void validateSimState(Task task, TaskItem item, ICommonPartner partner, String dbType, Integer languageId, Map<Long, SimState> simCardStatesMap) throws SetTaskItemStateException {
        if (item.getState().equalsTo(READY)) {
            if (!ALLOWED_SIM_STATES.contains(item.getSimCard().getState().getId())) {
                validator.skipItem(task, item, SIM_WRONG_STATUS, MessageFactory.getSIMWrongStatusMessage(dbType, validator.getSimStateName(item.getSimCard().getState().getId(), simCardStatesMap), languageId), languageId);
            }
        }
    }
    @Override
    public void validateSimWarehouse(Task task, TaskItem item, Long warehouseId, Long partnerId, Integer languageId, String dbType, Exchange exchange) throws SetTaskItemStateException {
        if (!Boolean.parseBoolean(task.getOptions().getProperty("isAllowMoveFromDiffAgents"))) {
            Long currentSimWarehouseId = item.getSimCard().getAgent().getAgentId();
            if (!warehouseId.equals(currentSimWarehouseId) && !partnerId.equals(currentSimWarehouseId)) {
                skipItem(task, item, NOT_IN_SAME_WAREHOUSE, MessageFactory.getNotInSameWarehouseError(dbType, languageId), languageId);
            }
        }
    }
    @Override
    public LogisticOperationTypeEnum getLogisticOperationType() {
        return SIM_MOVE_PARTNER_2_PARTNER;
    }
}
```
В данном примере у меня получился следующий вариант изменений: в изначальном коде мы имели базовый класс, который содержал несколько общих методов, 
но кроме них были еще несколько методов, которые переопределялись почти в каждом классе-наследнике. Единственное, что меня очень смущает, я не совсем уверен, что для данной структуры Visitor применим.
Согласно той же приложенной в задании статье, Visitor по дереву выбора паттерна необходимо применять в случае "Operation on object structure". Здесь же нет какого-то обхода структуры и зависимости действий то, например, типа сим-карт.
В общем, я тут скорее "механистически" подошел к реализации переделки. Согласно описанию паттерна я создал интерфейс посетителя, в который вынес заголовки методов, переопределяемых в дочерних классах.
В базовом классе сделал соответствующие изменения, убрав выносимые в посетители методы. Дочерние классы теперь стали конкретными посетителями с реализацией методов с соответствующей спецификой для каждого.
От "неистинного" наследование я конечно избавился. Но по сути мне некуда вставить accept, описанный в паттерне, т.е. тип посетителя я выбираю сразу при создании экземпляра базового класса.
Ну и по итогу, меня все же не покидает ощущение, что получилось что-то не то...
Собственно именно поэтому мне сложно сделать правильное резюме получившегося результата - стало лучше или хуже. Тут я скорее психологически с учетом потраченных усилий готов сказать, что стало лучше. 
Хотя если честно, то получившийся вариант выглядит более запутанно по сравнению с первоначальным.  

