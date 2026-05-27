Первая итерация (класс изначально без комментария):
```java
/**
 * Базовый класс для создания скриптов сценариев групповых операций. Отвечает за подготовку и конфигурирование инфраструктуры исполнения 
 * сценариев на основе данных, переданных из управляющего актора (ScenarioActor).
 * Обеспечивает наличие в скриптах сценариев всего необходимого контекста выполнения, определяемого на предыдущих этапах. 
 * Операции продукта оформляются наследниками данного класса.
 */
public abstract class PreconfiguredScenario extends AbstractScenario {

    protected String pstxid;
    protected Long subscriberId;
    protected Long customerId;
    protected Map<String, Object> inParams;
    ...

    @Override
    public ScenarioStepResult execute(ScenarioRequest scenarioRequest, int stepNumber, OapiWrapper oapiWrapper,
                                      CrabWrapper crabWrapper, Locale locale) throws AppException {
        initScenarioRequest(scenarioRequest, oapiWrapper, locale);
        log.debug("Start execution scenario {}, scenarioRequestId={}, execution requests count {}",
                getClass().getSimpleName(),
                scenarioRequestId, scenarioRequest.getRequests().size());
        SimpleScenarioStepResult scenarioStepResult = new SimpleScenarioStepResult();
        pstxidLogManager = new PstxidLogManager();
        contextLogFieldsManager = new ContextLogFieldsManager();
        scenarioRequest.getRequests().forEach(request -> {
            initRunDataRequest(request);
            Map<String, Object> originalError = new HashMap<>();
            try {
                if (this instanceof ScopedPreconfiguredScenario) {
                    executeScope(request, originalError)
                            .forEach(result -> scenarioStepResult.add(prepareResponse(result)));
                } else {
                    scenarioStepResult.add(prepareResponse(execute(request, originalError)));
                }
            } catch (AppException e) {
                handleError(scenarioStepResult, request, originalError, e);
            } catch (RuntimeException re) {
                // runtime exceptions from script execution transform to BusinessException and
                // process by usual handler. To not overreact in higher levels.
                String errorDescription = labelService.msg(locale,
                        "PreconfiguredScenario.uncheckedError", re);
                AppException ae = new BusinessException(errorDescription, re, AppErr.SCENARIO);
                handleError(scenarioStepResult, request, originalError, ae);
            }
        });
        contextLogFieldsManager.removeContextFieldsFromMdc();
        pstxidLogManager.restorePstxidInMdc();
        return scenarioStepResult;
    }

    private void initScenarioRequest(ScenarioRequest scenarioRequest,
                                     OapiWrapper oapiWrapper, Locale locale) throws AppException {
        this.scenarioRequestId = scenarioRequest.getId();
        // В теории на данный момент нестрашно вызвать такой init. Но этот враппер и сервис уже связаны парой этажей
        // выше, в ScenarioExecutor - поэтому смысла нет.
        //this.oapiService.init(oapiWrapper);
        // Враппер может понадобиться для вызова утилитных классов из скрипта - поэтому сохраняем в доступе.
        this.oapiWrapper = oapiWrapper;
        this.replyTo = scenarioRequest.getReplyTo();
        this.locale = locale;
        try {
            initParams(scenarioRequest, locale);
        } catch (Exception e) {
            log.error("Error occurred during initialization of custom parameters", e);
            throw new AppException(e);
        }
    }
    ...
    /**
     * Override this method, when you need configure specific params etc.
     */
    protected void initParams(ScenarioRequest scenarioRequest, Locale locale) throws Exception {

    }

    protected void initRunDataRequest(RunDataExecutionRequest request) {
        this.scenarioContext = request.getContext() == null ?  new HashMap<>() : request.getContext();
        this.pstxid = request.getRunData().getPstxId();
        this.runForUser = request.getRunData().getNaviUser();
        this.correlationId = request.getRunData().getCorrelationId();
        ...
    }

    protected void handleError(SimpleScenarioStepResult scenarioStepResult, RunDataExecutionRequest request,
                               Map<String, Object> originalError, AppException e) {
        log.error("Failed execution request. RunDataId: {}, " +
                "customerId: {}, subsId: {}, correlationId: {}", request.getRunData().getId(),
                customerId, subscriberId, correlationId);
        if (originalError.isEmpty()) {
            originalError = null;
        } else {
            Object value = originalError.remove(ScenarioConstant.OutParameters.ORIGINAL_ERROR);
            originalError.put(ScenarioConstant.OutParameters.ORDER_RESULT_FOR_OUT_PARAMS, value);
        }
        ...
    }

    protected abstract CommonUtils.PreparedResponse execute(RunDataExecutionRequest request,
                                                         Map<String, Object> originalError) throws AppException;

    public Collection<CommonUtils.PreparedResponse> executeScope(RunDataExecutionRequest request,
                                                                 Map<String, Object> originalError) throws AppException {
        throw new AppException("use #execute");
    }

}
```
Второй пример кода:
```java
/**
 * Данный сервис отвечает за "перевод" зарегистрированных бэкендом в БД задач на групповые операции во внутренний контур их исполнения по расписанию.
 * Происходит запуск задач на обработку. Дальнейшее исполнение происходит во внутренней системе акторов.
 */
public class NewBulkRequestsLoaderService extends AbstractScheduleService implements InitializingBean, DisposableBean {
    private final static Logger LOG = LoggerFactory.getLogger(NewBulkRequestsLoaderService.class);

    private LabelService lbl;

    private ConfigService cfg;

    @Setter
    private LoadRequestService loadRequestService;

    @Setter
    private ControlService controlService;

    private ExecutorService registerRunsExecutorService = Executors.newFixedThreadPool(3);

    @Setter
    private TransactionTemplate transactionTemplate;

    @Setter
    private LoadRequestDao loadRequestDao;

    //--- Мутаторы зависимостей ----------------------------------------------------------------------------------------

    public void setLabelService(LabelService labelService) {
        this.lbl = labelService;
    }

    public void setConfigService(ConfigService configService) {
        this.cfg = configService;
    }

    //--- /Мутаторы зависимостей ---------------------------------------------------------------------------------------

    @Override
    public void scheduleHandler() {
        if (cfg.isDevelopmentMode()) {
            return;
        }
        try {
            scheduleNow(Locale.getDefault());
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
        }
    }

    @Override
    public synchronized void scheduleNow(Locale locale) throws AppException {
        LOG.info("Start check for scheduled requests");
        try {
            Long maxBulkRequestWeight = null;
            if (cfg.standHealthCheckEnabled() && !cfg.standIsOk()) {
                maxBulkRequestWeight = (long) cfg.standHealthCheckMaxBulkRequestWeight();
            }
            List<LoadRequest> scheduledNowList = loadRequestService.findScheduledNow(maxBulkRequestWeight, locale);
            if (scheduledNowList.size() < 11) {
                LOG.info("Found run requests for performing bulk operations: {}", scheduledNowList);
            } else {
                LOG.info("Found {} run requests for performing bulk operations", scheduledNowList.size());
            }

            List<String> errorMessages = new ArrayList<>();
            for (LoadRequest loadRequest : scheduledNowList) {
                try {
                    runLoadRequest(loadRequest, locale);
                } catch (AppException e) {
                    errorMessages.add(e.getMessage());
                    //в случае если пролезло исключение, тогда помечается в БД как ошибочный
                    markLoadRequestAsError(loadRequest, locale);
                }
            }

            if (!errorMessages.isEmpty()) {
                StringBuilder eb = new StringBuilder();
                for (String errm : errorMessages) {
                    if (eb.length() == 0) {
                        eb.append(errm);
                    } else {
                        eb.append("\n").append(errm);
                    }
                }
                throw new AppException("Errors occurred while starting scheduled loading requests" + eb.toString());
            }
        } catch (Throwable e) {
            LOG.error("Error of check and startup of scheduled requests, see details below");
            LOG.error("", e);
            throw new AppException("Error of check and startup of scheduled requests", e);
        }

        LOG.info("End check for scheduled requests");
    }

    private void runLoadRequest(LoadRequest loadRequest, Locale locale) throws AppException {
        LOG.info("Starting request: {}", loadRequest);
        Run run;
        try {
            run = this.loadRequestService.createRunFromScheduledLoadRequest(loadRequest, locale);
        } catch (AppException e) {
            LOG.error("Error running request {}, request will be marked as error and never proceed. See exception below", loadRequest);
            LOG.error("", e);
            throw e;
        }

        try {
            this.controlService.registerRun(run.getId(), run.getRequestId(), locale);
        } catch (AppException e) {
            LOG.error("Run startup error (id={}, request id={}), run attempts will continue " +
                    "indefinitely in separate thread...", run.getId(), run.getRequestId());
            LOG.error("", e);

            final ControlService cs = this.controlService;
            
            ...
        }
    }

    private void markLoadRequestAsError(final LoadRequest loadRequest, Locale locale) throws AppException {
        final ExceptionHolder<Throwable> eh = new ExceptionHolder<>();
        final int maxLockTry = 10;
        int tryNum = 1;
        
        ...

        if (eh.isNotEmpty()) {
            LOG.error("Error while marking request id={} as error. It may be processed again " +
                    "by scheduler or other logic.", loadRequest.getId());
            //здесь ничего не перевыбрасывается, т.к. весь метод и так используется только в обработчике исключения.
        }
    }

    @Override
    public void destroy() throws Exception {
        LOG.info("Start of run scheduler service finalization");
        try {
            this.registerRunsExecutorService.shutdown();
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
        }
        stop();
        LOG.info("End of run scheduler service finalization");
    }

    @Override
    public void afterPropertiesSet() throws Exception {
    }

    @Override
    protected long getExecuteInterval() {
        return cfg.scanInterval();
    }

    @Override
    protected String getThreadName() {
        return "master-service-thread " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS"));
    }
}
```
Третий пример:
```java
/**
 * Сервис, отвечающий за выполнение плановых/отложенных ранов (запусков) по расписанию. 
 * Представляет собой отдельный путь исполнения внутри общего контура управления запусками для запросов, которые должны выполняться по индивидуальным правилам расписания, 
 * в отличие от имевшегося ранее единственного "немедленного" пути исполнения ранов.
 */
public class ScheduleExecuteService implements DisposableBean, IScheduleExecuteService {

    @Setter
    private LabelService labelService;
    @Setter
    private ConfigService cfg;
    @Setter
    private ActorSystem actorSystem;
    ...
    private ActorRef rootMasterActor;
    private SharedRegistry sharedRegistry;

    @Override
    public void destroy() throws Exception {
        if (this.actorSystem != null) {
            if (this.rootMasterActor != null) {
                log.info("stopping service...");
                long stopTimeout = TimeUnit.SECONDS.toMillis(cfg.getActorSystemStopTimeout());
                try {
                    // Variants for controllable stop:
                    //   1) gracefulStop akka pattern (guarantees results).
                    //   2) manual interaction protocol with control of Terminated messages (DeathWatch.
                    // Proper chain of graceful signals (who stops who) has to be implemented by developer.
                    scala.concurrent.Future<Object> sf = ask(this.rootMasterActor, RootMaster.CMD_GRACEFUL_STOP, stopTimeout);
                    Object si = Await.result(sf, Duration.create(stopTimeout, TimeUnit.MILLISECONDS));
  //                }
                } catch (Exception e) {
                    throw new AppException("destroy failed", e);
                }
                log.info("stopped successfully");
            }
        }
    }

    public void init() throws Exception {
        log.debug("Scheduled tasks execution service init start");
        sharedRegistry = new SharedRegistry();
        sharedRegistry.setServiceFlowStatus(ServiceFlowStatus.STARTING);

        // run other sevices, akka actors etc.
        rootMasterActor = this.actorSystem.actorOf(RootMaster.props(labelService, cfg, scheduledTaskDao,
                        runDataDao, runDao, operationDao, runDetailDao, transactionTemplate, sharedRegistry,
                        zooMasterService.currentRunnerInstanceId(), scenarioService, scenarioProcessingStateService,
                        oapiService, crabWrapperBuilder)
                .withDispatcher(MAIN_MGR_DISPATCHER_NAME), "scheduler-root");
        // await for actor to be responsible
        log.debug("root actor created, ping start...");
        ...
        while (true) {
            try {
                Object sfRes = Await.result(sf, waitAnswerDuration);
                if (Objects.equals(RootMaster.RESP_BOOTSTRAP_OK, sfRes)) {
                    log.debug("rootMaster bootstrap response is ok: {}", sfRes);
                } else {
                    log.error("rootMaster bootstrap response is abnormal: {}", sfRes);
                    throw new IllegalStateException("failed to bootstrap the rootMaster actor");
                }
                break;
            } catch (InterruptedException | TimeoutException e) {
                log.debug("sfRes not received via given timeout, keep waiting...");
            }
        }
        sharedRegistry.setServiceFlowStatus(ServiceFlowStatus.STARTED);
        log.debug("Scheduled tasks execution service init completed");
    }

    @Override
    public void delegateNewRun(RunInfo runInfo, ActorRef managerActorRef) {
        // все возможные реквесты подняты при инициализации сервиса, так что на опережающие сообщения от мастера
        // закладываться не нужно. Либо это восстановление паузнутого реквеста, либо вправду новый реквест, либо инфо-мусор.
        RequestTrackInfo rti = sharedRegistry.getAliveRequestsMap().get(runInfo.getRun().getRequestId());
        if (rti == null) {
            sharedRegistry.getRequestTracker().tell(new NewRequestDelegatingMessage(runInfo, managerActorRef),
                    ActorRef.noSender());
        } else {
            if (!rti.getSwitching().get() && rti.getRunStateCode() == RequestRunState.PAUSED) {
                sharedRegistry.getRequestTracker().tell(new ResumeRequestMessage(runInfo, managerActorRef),
                        ActorRef.noSender());
            } else {
                log.warn("delegateNewRun not available for requestId={} because of current state={}",
                        runInfo.getRun().getRequestId(), rti);
            }
        }
    }

    ...
    @Override
    public ResourceQuotaManageRespMessage manageResourceQuota(ResourceQuotaManageCommand command) {
        if (!this.sharedRegistry.isActorsInitialized() || !this.sharedRegistry.isActivityAllowed()) {
            return new ResourceQuotaManageRespMessage(command.action, command.processingQuota, null,
                    new AppException("service not in working state"));
        }

        // в общий internalApiCallsTimeoutMillis должно уложиться и захват лока, и само действие.
        long millisRemain = internalApiCallsTimeoutMillis;
        String commandInternalKey = null;
        // если не read-операция, то необходимо получить доступ на изменения.
        if (!ResourceQuotaManageReqMessage.ACTION_SEARCH.equals(command.action)) {
            long startMomentMillis = DateFactory.getCurrentInstantMs().toEpochMilli();
            commandInternalKey = UUID.randomUUID().toString();
            boolean acquireSuccess = this.sharedRegistry.acquireLockForCommand(commandInternalKey, command,
                    millisRemain, TimeUnit.MILLISECONDS);
            if (!acquireSuccess) {
                log.warn("manageResourceQuota could not acquire lock for command={}", command);
                return new ResourceQuotaManageRespMessage(command.action, command.processingQuota, null,
                        new ResourceUnavailableException(String.format("Could not acquire lock in %s millis",
                                millisRemain)));
            }
            millisRemain -= (DateFactory.getCurrentInstantMs().toEpochMilli() - startMomentMillis);
        }

        // проведение действия
        ResourceQuotaManageReqMessage msg = new ResourceQuotaManageReqMessage(command.action, command.processingQuota,
                commandInternalKey);
        CompletableFuture<Object> cf = ask(this.sharedRegistry.getWorkerPoolManager(), msg,
                java.time.Duration.ofMillis(millisRemain)).toCompletableFuture();
        try {
            //get повисает на ожидании исполнения cf. Отвисание гарантирует таймаут, переданный ask.
            Object cfResult = cf.get();
            //обработка ответа
            if (cfResult instanceof ResourceQuotaManageRespMessage) {
                return (ResourceQuotaManageRespMessage) cfResult;
            } else {
                return new ResourceQuotaManageRespMessage(msg.action, msg.processingQuota, null,
                        new AppException(String.format("answer is of unexpected type=%s",
                                cfResult.getClass().getName())));
            }
        } catch (CancellationException | InterruptedException | ExecutionException e) {
            //при неукладке актора в таймаут ask будет
            // java.util.concurrent.ExecutionException: akka.pattern.AskTimeoutException
            log.error("manageResourceQuota exception in ask pattern (possibly timeout exceeded)", e);
            return new ResourceQuotaManageRespMessage(msg.action, msg.processingQuota, null, e);
        }
    }


    ...


}
```
Откровенно говоря, при первом прочтении задания оно показалось достаточно простым и я был уверен, что уж данное задание я смогу достаточно быстро сделать. И с учетом таких оптимистичных ожиданий как-то неожиданно сложно оказалось более или менее корректно сформулировать требуемые по заданию комментарии. 
Лишний раз отмечаю, что даже вроде бы по знакомому коду при попытке подняться на третий уровень вместо понимания логического дизайна возникает некая "каша" из смешанного второго и третьего уровня понимания.
Ну и в процессе работы заметил, что сложно "подняться" над кодом еще и в том плане, что все равно так и тянет в комментариях доописать "что конкретно" код делает.
Собственно, чтобы написать качественный комментарий надо кристально ясно видеть общую логическую картину программы (ну или полный законченный логический фрагмент, включающий в себя комментируемый код) чтобы понимать какое место данный класс/кусок кода занимает в нем, 
научиться (а потом и хорошо так привыкнуть) разделять понимание кода как списка инструкций и как часть общей логической задумки.

Если вернуться непосредственно к материалу по самодокументирующемуся коду и вообще к комментариям - мы в команде сейчас все таки больше стремимся комментировать в плане "почему, зачем, какие особенности". 
У нас в продукте комментарии часто несут информацию о том, почему мы сделали именно так, а не иначе (это прям требование тимлида для случаев, когда особенность реализации диктуется какими-то ограничениями систем или продуктов наших смежников, или специфическими требованиями бизнес-заказчиков).
Потому что неоднократно были ситуации, когда мы тратили просто уйму времени на попытку понять причину именно такой реализации какого-нибудь процесса по сочетаниям "время коммита; ФС; переписки, предшествующие этому коммиту" (и не всегда удавалось найти концы - это нас кто-то/что-то заставило так реализовать или просто обычное программистское временное помутнение разума). 

А вообще у нас был (давно уже) один такой прям апологет самодокументирующегося кода. Принципиально сам не писал комментариев и ругался на тех, кто пишет. Ну и, кстати, жутко бесился, когда к нему приходили пояснить какие-то моменты по его коду (причем, насколько я сейчас понимаю, его часто такими вопросами специально и троллили :). Кстати, никогда не думал о комментариях как о софт-скилле общения - а ведь это действительно так. Как раз к примеру выше - в обычном общении тот программист тоже был не очень.

Ну а фраза из материала "То, что в проекте очевидно для вас, совсем не очевидно для всех остальных." для меня (да и, наверное, для многих программистов) звучит еще более строго - то, что очевидно сейчас, не факт, что будет очевидно через полгода-год-два. 
Сколько раз я жалел о том, что поленился оставить комментарий, хотя прекрасно в момент написания данного кода осознавал его необходимость. Вот, кстати, тоже интересный момент - почему-то часто я прекрасно помню, что хотел оставить комментарий к данному коду, а вот почему код именно такой - не помню. 

В общем, вывод такой: хорошо, когда код выразителен и тесты говорящие, но качественные комментарии могут приблизить его к идеалу.
 
