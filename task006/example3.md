```groovy
class RatePlanChangeViaProductOffering extends AbstractScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger(RatePlanChangeViaProductOffering.class)
    private static final String NEED_CHECK_CALLBACK = "needCheckCallback"
    private static final String ORDER_ID = "orderId"
    private static final String COMPRISED_PRODUCT_OFFERINGS = "comprisedProductOfferingParameters"
    private static final String PERSONAL_OFFERING_PARAMETERS = "personalOfferingParameters"
    private static final String OPERATION_WORK_REASON = "operationWorkReason"
    private static final String BALANCE_VOLUME = "balanceVolume"

    private static final long RATE_PLAN_TYPE = 1L

    private OapiWrapper oapiWrapper
    private String scenarioRequestId
    private String pstxid
    Map scenarioContext

    @Override
    ScenarioStepResult execute(ScenarioRequest scenarioRequest, int stepNumber, OapiWrapper oapiWrapper,
                               CrabWrapper crabWrapper, Locale locale) throws AppException {
        String replyTo = scenarioRequest.replyTo
        PreCheckUtils preCheckUtils = new PreCheckUtils()
        ...
        scenarioRequest.getRequests().each {

            def runDataExecutionRequest = it
            scenarioContext = it.context ? it.context : new HashMap()
            this.pstxid = it.runData.pstxId
            String runForUser = it.runData.naviUser
            String correlationId = it.runData.correlationId
            Map inParams = it.runData.parsedParams
            Long customerId = it.getRunData().getCustomerId() //для клиентских проверок
            Long subscriberId = it.runData.subscriberId
            Map originalError = new HashMap()
            try {

                Boolean needCheckCallback = getValue(this.scenarioContext, NEED_CHECK_CALLBACK)
                //анализируем необходимость запуска и запускаем проверки
                if (!needCheckCallback) {
                    preCheckUtils.init(oapiService, oapiWrapper, labelService, scenarioRequestId, pstxid, locale)

                    if (ParamsUtil.getBoolean(inParams, "isActiveProductOfferingsCheck")) {
                        preCheckUtils.isSubscriberHasActiveProductOfferingWithException(subscriberId,
                                ParamsUtil.getString(inParams, "activeProductOfferingIdsForCheck"))
                    }
                    ...
                }
                //проверки закончены

                if (needCheckCallback) {
                    processCallback(runDataExecutionRequest, scenarioStepResult, scenarioRequestId,
                            subscriberId, this.scenarioContext)
                } else {
                    List<Map> params = adjustInParams(inParams)
                    inParams.put('productOfferings', params)
                    boolean isActivateProductOfferingsIfTheSameRatePlan = ParamsUtil.getBoolean(inParams,
                            "isActivateProductOfferingsIfTheSameRatePlan")
                    loadProductOfferingParameters(params, subscriberId, isActivateProductOfferingsIfTheSameRatePlan)
                    processMainAction(scenarioRequestId, inParams, pstxid, subscriberId, oapiWrapper, locale, replyTo,
                            runForUser, correlationId, this.scenarioContext, scenarioStepResult,
                            runDataExecutionRequest, originalError)
                }
            } catch (AppException e) {
                ...
                stepResult.setError(e)
                scenarioStepResult.add(stepResult)
            }
        }
        return scenarioStepResult
    }

    /**
     * Действия при получении коллбэка
     */
    void processCallback(RunDataExecutionRequest runDataExecutionRequest,
                         SimpleScenarioStepResult scenarioStepResult, String scenarioRequestId,
                         Long subscriberId, Map scenarioContext) {

        String callbackPreviousStepResponse = runDataExecutionRequest.priorScenarioStepCallback == null ?
                labelService.msg("RatePlanChangeViaProductOffering.execute.callbackDescriptionIsNull") :
                runDataExecutionRequest.priorScenarioStepCallback.stepStatus

        def statusId
        def resultCallbackMessage

        if (callbackPreviousStepResponse == CallbackDescription.SUCCESS) {
            statusId = RunData.STATUS_DONE
            resultCallbackMessage = ""
        } else {
            statusId = RunData.STATUS_ERR
            resultCallbackMessage = labelService.msg(
                    "RatePlanChangeViaProductOffering.execute.readCallbackError",
                    scenarioRequestId, subscriberId, callbackPreviousStepResponse)
        }
        scenarioStepResult.add(CommonUtils.prepareResponse(runDataExecutionRequest,
                null, scenarioContext, prepareOutParams(ParamsUtil.getLong(scenarioContext, ORDER_ID)),
                resultCallbackMessage, statusId, null, true))
    }

    ...

    /**
     * Действия при первоначальном запуске сценария по абоненту
     */
    private void processMainAction(String scenarioRequestId, Map inParams, String pstxid, Long subscriberId,
                                   OapiWrapper oapiWrapper, Locale locale, String replyTo, String runForUser,
                                   String correlationId, Map scenarioContext,
                                   SimpleScenarioStepResult scenarioStepResult,
                                   RunDataExecutionRequest runDataExecutionRequest, Map originalError) {

        // Входные параметры операции
        checkInputParams(scenarioRequestId, inParams)
        Boolean isWaitingCallback = CommonUtils.isWaitingCallback(activationDate, timeZone, scenarioRequestId,
                labelService, LOGGER)
        productOfferingCheckActivate(this.oapiWrapperAuth, scenarioRequestId, pstxid, subscriberId, productOfferings,
                timeZone, locale, originalError, checkBalance)

        String priority = ParamsUtil.getString(inParams, "priority")

        Long orderId = productOfferingActivate(scenarioRequestId, pstxid, subscriberId, this.oapiWrapperAuth, replyTo,
                runForUser, correlationId, productOfferings, timeZone, locale, isWaitingCallback, priority?.toUpperCase(),
                ParamsUtil.getString(inParams, OPERATION_WORK_REASON), noSMS, checkBalance)
        Map<String, Object> outParams = prepareOutParams(orderId)
        scenarioContext.put(ORDER_ID, orderId)

        if (isWaitingCallback) {
            scenarioContext.put(NEED_CHECK_CALLBACK, true)
            scenarioStepResult.add(CommonUtils.prepareResponse(runDataExecutionRequest, orderId, scenarioContext,
                    outParams, null, RunData.STATUS_IN_PROGRESS, null, false))
        } else {
            scenarioContext.put(NEED_CHECK_CALLBACK, false)
            scenarioStepResult.add(CommonUtils.prepareResponse(runDataExecutionRequest, null, scenarioContext,
                    outParams, null, null, null, true))
        }
    }
    ...
    /**
     * Проверка возможности подключения тарифного плана и дополнительных продуктов
     */
    private void productOfferingCheckActivate(OapiWrapper oapiWrapper, String scenarioRequestId, String pstxid,
                                              Long subscriberId, List productOfferings, String timeZone,
                                              Locale locale, Map originalError, Boolean  checkBalance) {

        String url = "openapi/v2/subscribers/${subscriberId}/productOfferings/activate/check/bulk"
        Map headers = CommonUtils.getBaseHeaders(pstxid)
        headers.put("ps-timezone", timeZone)
        headers.put("ps-business-process", "BULK_OPERATIONS")
        Map body

        List mapped = mapParameters(productOfferings, checkBalance)

        body = [parameters: mapped]
        String bodyString = oapiService.toJSON(body, locale)
        LOGGER.debug(labelService.msg("RatePlanChangeViaProductOffering.checkActivate.call", scenarioRequestId,
                url, subscriberId))
        OapiResponse response = oapiService.execute(oapiWrapper, HttpMethod.POST, headers, url,
                null, bodyString, locale)
        Map resultMap = (Map) response.contentObject
        CommonUtils.checkResponseCode(LOGGER, labelService, response.code, resultMap, url, scenarioRequestId, locale)
        List conflictsList = (List) resultMap.get("conflicts")
        if (conflictsList != null && !conflictsList.isEmpty()) {
            List<String> emptyList = new ArrayList<>();
            CommonUtils.handleConflicts(LOGGER, labelService, resultMap, url, scenarioRequestId, emptyList,
                    true, originalError)
        }

        LOGGER.debug(labelService.msg("RatePlanChangeViaProductOffering.checkActivate.end", scenarioRequestId,
                url, subscriberId))

    }

    /**
     * Метод получения заголовка, урл и тела запроса согласно положению рычага
     *
     */
    static Map getActivateParamsBySwitch(Map headers, Map queryParameters, List productOfferings,
                                         Long subscriberId, String operationWorkReason, Boolean checkBalance) {
        Map body = new HashMap()
        String url = "/openapi/v2/subscribers/${subscriberId}/productOfferings/activate/bulk"
        List<Map<String, Object>> finalOfferingsParams = mapParameters(productOfferings, checkBalance)
        if (StringUtils.isNotBlank(operationWorkReason)) {
            finalOfferingsParams.each {offering ->
                offering.put("comment", StringUtils.trimToNull(operationWorkReason))
            }
        }
        body.put("parameters", finalOfferingsParams)

        return [url: url, changedHeaders: headers, changedQueryParameters: queryParameters, body: body]
    }

    /**
     * Вызов OAPI-функции на подключение продуктового предложения (тарифного плана) абоненту
     * @return orderId
     */
    private Long productOfferingActivate(String scenarioRequestId, String pstxid, Long subscriberId,
                                         OapiWrapper oapiWrapper, String replyTo, String runForUser,
                                         String correlationId, List productOfferings, String timeZone,
                                         Locale locale, boolean isWaitingCallback, String priority,
                                         String operationWorkReason, Boolean noSMS, Boolean checkBalance) {
        Long orderId
        Map headers = CommonUtils.getBaseHeaders(pstxid)
        headers.put("ps-timezone", timeZone)
        headers.put("ps-business-process", "BULK_OPERATIONS")
        Map queryParameters
        if (noSMS) {
            runForUser = runForUser + "_NOSMS"
        }
        if (isWaitingCallback) {
            queryParameters = CommonUtils.getBaseAsyncQueryParameters(runForUser, replyTo, correlationId)
        } else {
            queryParameters = CommonUtils.getBaseQueryParameters(runForUser)
        }
        if (priority) {
            queryParameters.put("orderPriority", priority.toUpperCase())
        }

        Map requestParams = getActivateParamsBySwitch(headers, queryParameters, productOfferings,
                subscriberId, operationWorkReason, checkBalance)

        String bodyString = oapiService.toJSON(requestParams.body, locale)

        LOGGER.debug(labelService.msg("RatePlanChangeViaProductOffering.activateRatePlan.call",
                scenarioRequestId, requestParams.url, subscriberId))

        OapiResponse response = oapiService.execute(oapiWrapper, HttpMethod.POST, requestParams.changedHeaders,
                requestParams.url, requestParams.changedQueryParameters, bodyString, locale)

        Map responseMap = (Map) response.contentObject
        CommonUtils.checkResponseCode(LOGGER, labelService, response.code, responseMap, requestParams.url,
                scenarioRequestId, locale)
        LOGGER.debug(labelService.msg("RatePlanChangeViaProductOffering.activateRatePlan.end", scenarioRequestId,
                subscriberId))
        orderId = ParamsUtil.getLong(responseMap, "orderId")
        return orderId
    }

    ...
    /**
     * processParametersIfRatePlanChange
     * выполняет преобразование продуктовых предложений в соответствии с дополнительными входными параметрами,
     * влияющими на продуктовое предложение, с типом "тарифный план":
     * 1. удаляет activationPeriod.dateTo для ПП типа "тарифный план" (cleanUpDateToIfRatePlanChange)
     * 2. если в списке подключаемых ПП несколько ПП (>1), есть ПП с типом "тарифный план", он соответствует текущему ТП абонента и
     * isActivateProductOfferingsIfTheSameRatePlan = true, то данный ПП исключается из списка подключаемых ПП
     * @param productOfferings
     * @param subscriberId
     */
    private void processParametersIfRatePlanChange(List<Map<String, Object>> productOfferings, long subscriberId,
                                                   boolean isActivateProductOfferingsIfTheSameRatePlan) {
        def requests = productOfferings.stream().map({ offering ->
            def request = [
                    productOfferingId: offering["productOfferingId"],
                    activationPeriod : offering["activationPeriod"]
            ]
            List<Map<String, Object>> characteristicUses = getCharacteristicUses(offering)
            if (characteristicUses.size() > 0) {
                request.put("characteristicUses", characteristicUses)
            }
            return request
        }).collect(Collectors.toList())
        Map offeringTypes = findProductOfferingCategory(subscriberId, requests)
                .stream()
                .collect(Collectors.toMap(
                        { it["productOfferingId"] as Long },
                        {
                            ParamsUtil.getLong(it, "category", "productCategoryId")
                        })
                ) as Map
        if (offeringTypes.containsValue(RATE_PLAN_TYPE)) {
            cleanUpDateToIfRatePlanChange(productOfferings, offeringTypes)
            if (isActivateProductOfferingsIfTheSameRatePlan && productOfferings.size() > 1) {
                deleteSameRatePlanOffering(productOfferings, offeringTypes, subscriberId)
            }
        }
    }
    ...
    
    static void processFileData(Map<String, Object> offering,
                                List<Map<String, Object>> requestParams) {
        Map<String, Object> activationPeriod = offering["activationPeriod"] as Map
        Map<String, Object> params = [
                productOfferingId: offering["productOfferingId"],
                activationPeriod : [dateFrom: activationPeriod["dateFrom"]]
        ]
        List<Map<String, Object>> characteristicUses = getCharacteristicUses(offering)
        if (characteristicUses.size() > 0) {
            params.put("characteristicUses", characteristicUses)
        }
        requestParams.add(params)
    }

    static List<Map<String, Object>> mapParameters(List<Map<String, Object>> productOfferings, Boolean checkBalance) {
        List<Map<String, Object>> result = new ArrayList<>()
        productOfferings.each { offering ->
            def params = new HashMap<>()
            params.put("productOfferingId", offering["productOfferingId"])
            params.put("activationPeriod", offering["activationPeriod"])
            if (checkBalance != null) {
                params.put("actionParameters", [checkBalance: checkBalance])
            }

            //теперь во входных параметрах могут быть дополнительные characteristicUses. Учитываем.
            List<Map<String, Object>> characteristicUses = getCharacteristicUses(offering)
            if (characteristicUses.size() > 0) {
                params.put("characteristicUses", characteristicUses)
            }

            def personalProductOfferingId = offering.get("personalProductOfferingId")
            /**
             * На этапе формирования данных из файла или из default params мы
             * положили все данные для индивидуализации в PERSONAL_OFFERING_PARAMETERS
             */
            Map personalParameters = (Map) offering[PERSONAL_OFFERING_PARAMETERS]
            if (personalParameters) {
                if (personalProductOfferingId) {
                    personalParameters.put("personalOfferingId", personalProductOfferingId)
                }
                params.put(PERSONAL_OFFERING_PARAMETERS, personalParameters)
            } else {
                if (personalProductOfferingId) {
                    params.put(PERSONAL_OFFERING_PARAMETERS, [personalOfferingId: personalProductOfferingId])
                }
                def customParameters = offering["recurringChargesCustomParameters"]
                if (!customParameters || customParameters["price"] == null) {
                    result.add(params as Map)
                    return
                }
                def priceValue = customParameters["price"]
                def price = [recurringChargesParameters: [[price: priceValue]]]
                String type = offering["offeringType"]
                if (type == COMPRISED_PRODUCT_OFFERINGS) {
                    params.put(type, [price])
                } else {
                    params.put(type, price)
                }
            }
            result.add(params as Map)
        }
        return result

    }

    private static List<Map<String, Object>> getCharacteristicUses(Map<String, Object> offering) {
        //после прогона через json-десериализацию тип может сбиться, так что перестраховка по-простому
        Double balanceVolumeValue = ParamsUtil.getDouble(offering, BALANCE_VOLUME)
        //теперь во входных параметрах могут быть дополнительные characteristicUses. Учитываем.
        List<Map<String, Object>> characteristicUses = new ArrayList<>()
        if (balanceVolumeValue != null) {
            characteristicUses.add(["charSpecCode": "balanceVolume", "value": balanceVolumeValue])
        }

        if (ParamsUtil.exists(offering, "characteristicUses")) {
            characteristicUses.addAll((List<Map<String, Object>>) getValue(offering, "characteristicUses"))
        }
        return characteristicUses
    }

    private static List mapProductOfferings(List productOfferings, Boolean checkBalance) {
        def mapped = productOfferings.stream().map({ offering ->
            def map = [
                    activationPeriod : offering["activationPeriod"],
                    productOfferingId: offering["productOfferingId"]
            ]
            def personalProductOfferingId = offering["personalProductOfferingId"]
            if (personalProductOfferingId) {
                map.put("personalOfferingParameters", [personalOfferingId: personalProductOfferingId])
            }
            if (checkBalance != null) {
                map.put("actionParameters", [checkBalance: checkBalance])
            }
            return map
        }).collect(Collectors.toList()) as List
        mapped
    }
}
```
Тесты:
```java
    /**
     * Тест, что если operationWorkReason передан в параметрах, то он успешно попадает в body оапи-запроса активации.
     *
     */
    @Test
    @SuppressWarnings("unused")
    public void testProcessMainActionWithWorkReason() throws Throwable {
        String scenarioRequestId = "scenarioRequestId";
        ReflectionTestUtils.setField(scenario, "labelService", this.labelService);
        setOapiService(scenario, oapiService);
        when(oapiService.toJSON(any(), any())).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            Map<String, Object> mainArg = cast(arguments[0]);
            return OBJECT_MAPPER.writeValueAsString(mainArg);
        });
        SimpleOapiResponse response = new SimpleOapiResponse();
        response.setCode(200);
        Map<String, Object> contentObject = map(
                e("zone", map(e("timeZone", "Europe/Moscow")))
        );
        response.setContentObject(contentObject);
        when(oapiService.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(response);
        String inParams = readResourceFile("/samples/scenario/inputParameters/RatePlanChangeViaProductOffering/" +
                "RPChangeViaPODefaultParamsWithReason.json");
        Map<String, Object> inParamsMap = mappingProcessor.buildParams(inParams, "{}",
                "RatePlanChangeViaProductOffering", "", Locale.getDefault());
        //в тест вынесены отдельные этапы логики сценария, не все. В общем имитация достаточно грубая, и может
        // давать впечатление ошибок сценария, хотя это могут быть и ошибки грубости теста.
        List<Map<String, Object>> adjustedInParams = callMethod(scenario, "adjustInParams", inParamsMap);
        inParamsMap.put("productOfferings", adjustedInParams);
        //Map<String, Object> resultParams = map(e("productOfferings", adjustedInParams));
        Map<String, Object> scenarioContext = new HashMap<>();
        SimpleScenarioStepResult scenarioStepResult = new SimpleScenarioStepResult();
        SimpleRunDataExecutionRequest executionRequest = new SimpleRunDataExecutionRequest(
                1L, new RunData(), null, new RunDetail(), null
        );
        Map<String, Object> originalError = new HashMap<>();
        callMethod(scenario, "processMainAction", scenarioRequestId, inParamsMap, "pstxId",
                1L, oapiWrapper, Locale.getDefault(), "replyTo", "runForUser", "correlationId", scenarioContext,
                scenarioStepResult, executionRequest, originalError);

        String urlTimeZoneRequest = "/openapi/v1/subscribers/1";
        String urlActivate = "/openapi/v2/subscribers/1/productOfferings/activate/bulk";

        verify(oapiService, times(1)).execute(
                eq(oapiWrapper), any(), any(),
                eq(urlTimeZoneRequest), any(), any(), any()
        );
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(oapiService, times(1)).execute(
                eq(oapiWrapperAuth), any(), any(),
                eq(urlActivate), any(), bodyCaptor.capture() ,
                any()
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode bodyJson = mapper.readTree(bodyCaptor.getValue());
        for (int i = 0; i < 2; i++) {
            JsonNode reason = bodyJson.at("/parameters/" + i + "/comment");
            assertFalse(reason.isMissingNode());
            assertEquals("testWorkReason", reason.textValue());
        }
    }

    /**
     * Тест на действия по подключению только не-финансовых продуктов (без balanceVolume)
     * Проверяется по выборочным параметрам что они корректно попадают в функцию активации
     * Проверяется что общая логика сценария состоит из 1 прохода.
     */
    @Test
    @SuppressWarnings({"unchecked"})
    public void testScenarioExecutionWithOnlyNonBalanceProdOffers() throws Throwable {
        String scenarioRequestId = "zzz-scenarioRequestId";
        RunDetail runDetail = new RunDetail();
        runDetail.setId(11L);
        Map scenarioContext = new HashMap<>();

        when(oapiService.toJSON(any(), any())).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            Map<String, Object> mainArg = cast(arguments[0]);
            return OBJECT_MAPPER.writeValueAsString(mainArg);
        });
        //заглушки на все вызываемые урлы
        when(oapiService.execute(any(), any(), any(), any(), any(), any(), any()))
                .then(stubForStdFullExecuteTests());

        Map<String, Object> paramsParsed = (Map<String, Object>) getJsonObject(
                "/samples/scenario/inputParameters/RatePlanChangeViaProductOffering/" +
                "RPChangeViaPODefaultParamsWithReason.json");

        SimpleScenarioRequest request = new SimpleScenarioRequest();
        request.setId(scenarioRequestId);
        RunData runData = composeRunData(paramsParsed);
        SimpleRunDataExecutionRequest executionRequest = new SimpleRunDataExecutionRequest(13L, runData,
                scenarioContext, runDetail, null);
        request.add(executionRequest);
        request.setReplyTo("amqp://?exchange=nx.crab2bulk_operations.pub&key=ps.bulk_operations.1");

        int scenarioStepNumber = 0;
        ScenarioStepResult stepResult = null;

        stepResult = this.scenarioService.executeScenario(SCENARIO_EXECUTE_NAME, request, scenarioStepNumber, oapiWrapper,
                oapiWrapperAuth, oapiService, crabWrapper, Locale.getDefault());

        assertNotNull(stepResult);
        //проверки первого захода - атрибуты результата, oapi-вызовы и их параметры
        for (RunDataExecutionResult oneRes : stepResult.getRunDataResults()) {
            assertNull(oneRes.getError());
            assertFalse(oneRes.getOrderId().isEmpty());
            assertFalse(oneRes.isEndStep());
            scenarioContext = oneRes.getContext();
            assertTrue((Boolean)scenarioContext.get("needCheckCallback"));
        }
        verify(oapiService, times(1)).execute(
                eq(oapiWrapper), any(), any(),
                eq(URL_SUBS_TIMEZONE), any(), any(), any()
        );
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(oapiService, times(1)).execute(
                eq(oapiWrapperAuth), any(), any(),
                eq(URL_PRODOFFERS_ACTIVATE), any(), bodyCaptor.capture() ,
                any()
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode bodyJson = mapper.readTree(bodyCaptor.getValue());
        JsonNode reason = bodyJson.at("/parameters/0/comment");
        assertFalse(reason.isMissingNode());
        assertEquals("testWorkReason", reason.textValue());
        JsonNode productOfferings = bodyJson.at("/parameters");
        assertTrue(productOfferings.isArray());

        JsonNode characteristicUses = bodyJson.at("/parameters/0/characteristicUses");
        assertTrue(characteristicUses.isArray());
        assertEquals(1, characteristicUses.size());
        assertEquals("personalOfferingId", characteristicUses.get(0).get("charSpecCode").asText());
        assertEquals(123, characteristicUses.get(0).get("value").asInt());

        Iterator<JsonNode> offeringsIt = productOfferings.elements();
        Set<Long> activatedOfferingIds = Streams.stream(offeringsIt)
                .map(jn -> jn.path("productOfferingId").asLong())
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(1L, 177L)), activatedOfferingIds);

        //вызов с колбэком - должны получить isEnd флаг.
        executionRequest = new SimpleRunDataExecutionRequest(13L, runData,
                scenarioContext, runDetail, composeCallbackDescription());
        if (request.getRequests() != null) request.getRequests().clear();
        request.add(executionRequest);
        request.setReplyTo("amqp://?exchange=nx.crab2bulk_operations.pub&key=ps.bulk_operations.1");
        stepResult = this.scenarioService.executeScenario(SCENARIO_EXECUTE_NAME, request, scenarioStepNumber, oapiWrapper,
                oapiWrapperAuth, oapiService, crabWrapper, Locale.getDefault());
        assertNotNull(stepResult);
        //проверки колбэка - сценарий должен финишировать.
        for (RunDataExecutionResult oneRes : stepResult.getRunDataResults()) {
            assertNull(oneRes.getError());
            assertNull(oneRes.getOrderId());
            assertTrue(oneRes.isEndStep());
        }
    }

    /**
     * Тест на действия по подключению микса финансовых и не-финансовых продуктов (с/без balanceVolume)
     * Проверяется по выборочным параметрам что они корректно попадают в функцию активации
     * Проверяется что общая логика сценария состоит из 1 прохода.
     */
    @Test
    @SuppressWarnings({"unchecked"})
    public void testScenarioExecutionWithMixedBalanceProdOffers() throws Throwable {
        String scenarioRequestId = "zzz-scenarioRequestId";
        RunDetail runDetail = new RunDetail();
        runDetail.setId(11L);
        Map scenarioContext = new HashMap<>();

        when(oapiService.toJSON(any(), any())).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            Map<String, Object> mainArg = cast(arguments[0]);
            return OBJECT_MAPPER.writeValueAsString(mainArg);
        });
        //заглушки на все вызываемые урлы
        when(oapiService.execute(any(), any(), any(), any(), any(), any(), any()))
                .then(stubForStdFullExecuteTests());

        Map<String, Object> paramsParsed = (Map<String, Object>) getJsonObject(
                "/samples/scenario/inputParameters/RatePlanChangeViaProductOffering/" +
                        "RPChangeViaPODefParamsMixedBalanceOfferings.json");

        SimpleScenarioRequest request = new SimpleScenarioRequest();
        request.setId(scenarioRequestId);
        RunData runData = composeRunData(paramsParsed);
        SimpleRunDataExecutionRequest executionRequest = new SimpleRunDataExecutionRequest(13L, runData,
                scenarioContext, runDetail, null);
        request.add(executionRequest);
        request.setReplyTo("amqp://?exchange=nx.crab2bulk_operations.pub&key=ps.bulk_operations.1");

        int scenarioStepNumber = 0;
        ScenarioStepResult stepResult = null;

        // первое исполнение
        stepResult = this.scenarioService.executeScenario(SCENARIO_EXECUTE_NAME, request, scenarioStepNumber, oapiWrapper,
                oapiWrapperAuth, oapiService, crabWrapper, Locale.getDefault());

        assertNotNull(stepResult);
        //проверки первого захода - атрибуты результата, выборочно oapi-вызовы и их параметры
        for (RunDataExecutionResult oneRes : stepResult.getRunDataResults()) {
            assertNull(oneRes.getError());
            assertFalse(oneRes.getOrderId().isEmpty());
            assertFalse(oneRes.isEndStep());
            scenarioContext = oneRes.getContext();
            assertTrue((Boolean)scenarioContext.get("needCheckCallback"));
        }
        verify(oapiService, times(1)).execute(
                eq(oapiWrapper), any(), any(),
                eq(URL_SUBS_TIMEZONE), any(), any(), any()
        );
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(oapiService, times(1)).execute(
                eq(oapiWrapperAuth), any(), any(),
                eq(URL_PRODOFFERS_ACTIVATE), any(), bodyCaptor.capture() ,
                any()
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode bodyJson = mapper.readTree(bodyCaptor.getValue());
        JsonNode reason = bodyJson.at("/parameters/0/comment");
        assertFalse(reason.isMissingNode());
        assertEquals("testWorkReason", reason.textValue());

        JsonNode productOfferings = bodyJson.at("/parameters");
        assertTrue(productOfferings.isArray());
        Iterator<JsonNode> offeringsIt = productOfferings.elements();
        Set<Long> activatedOfferingIds = Streams.stream(offeringsIt)
                .map(jn -> jn.path("productOfferingId").asLong())
                .collect(Collectors.toSet());
        //assertEquals(new HashSet<>(List.of(1L)), activatedOfferingIds);
        assertEquals(new HashSet<>(List.of(1L, 888L)), activatedOfferingIds);

        //вызов с колбэком по заказу - должны сразу получить isEnd флаг (т.к. теперь НЕ остаются офферинги на вторую фазу)
        executionRequest = new SimpleRunDataExecutionRequest(13L, runData,
                scenarioContext, runDetail, composeCallbackDescription());
        if (request.getRequests() != null) request.getRequests().clear();
        request.add(executionRequest);
        request.setReplyTo("amqp://?exchange=nx.crab2bulk_operations.pub&key=ps.bulk_operations.1");
        stepResult = this.scenarioService.executeScenario(SCENARIO_EXECUTE_NAME, request, scenarioStepNumber, oapiWrapper,
                oapiWrapperAuth, oapiService, crabWrapper, Locale.getDefault());

        assertNotNull(stepResult);
        for (RunDataExecutionResult oneRes : stepResult.getRunDataResults()) {
            assertNull(oneRes.getError());
            assertNull(oneRes.getOrderId());
            assertTrue(oneRes.isEndStep());
        }

    }

    /**
     * Тест на действия по подключению только финансовых продуктов (с balanceVolume)
     * Проверяется по выборочным параметрам что они корректно попадают в функцию активации
     * Проверяется что общая логика сценария состоит из 2 проходов - первый пустышка, второй со всеми офферингами.
     */
    @Test
    @SuppressWarnings({"unchecked"})
    public void testScenarioExecutionWithOnlyBalanceProdOffers() throws Throwable {
        String scenarioRequestId = "zzz-scenarioRequestId";
        RunDetail runDetail = new RunDetail();
        runDetail.setId(11L);
        Map scenarioContext = new HashMap<>();

        when(oapiService.toJSON(any(), any())).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            Map<String, Object> mainArg = cast(arguments[0]);
            return OBJECT_MAPPER.writeValueAsString(mainArg);
        });
        //заглушки на все вызываемые урлы
        when(oapiService.execute(any(), any(), any(), any(), any(), any(), any()))
                .then(stubForStdFullExecuteTests());

        Map<String, Object> paramsParsed = (Map<String, Object>) getJsonObject(
                "/samples/scenario/inputParameters/RatePlanChangeViaProductOffering/" +
                        "RPChangeViaPODefParamsOnlyBalanceOfferings.json");

        SimpleScenarioRequest request = new SimpleScenarioRequest();
        request.setId(scenarioRequestId);
        RunData runData = composeRunData(paramsParsed);
        SimpleRunDataExecutionRequest executionRequest = new SimpleRunDataExecutionRequest(13L, runData,
                scenarioContext, runDetail, null);
        request.add(executionRequest);
        request.setReplyTo("amqp://?exchange=nx.crab2bulk_operations.pub&key=ps.bulk_operations.1");

        int scenarioStepNumber = 0;
        ScenarioStepResult stepResult = null;

        // первое исполнение
        stepResult = this.scenarioService.executeScenario(SCENARIO_EXECUTE_NAME, request, scenarioStepNumber, oapiWrapper,
                oapiWrapperAuth, oapiService, crabWrapper, Locale.getDefault());

        assertNotNull(stepResult);
        //проверки первого захода - атрибуты результата, выборочно oapi-вызовы и их параметры
        assertNotNull(stepResult);

        for (RunDataExecutionResult oneRes : stepResult.getRunDataResults()) {
            assertNull(oneRes.getError());
            assertFalse(oneRes.getOrderId().isEmpty());
            assertFalse(oneRes.isEndStep());
            scenarioContext = oneRes.getContext();
            assertTrue((Boolean)scenarioContext.get("needCheckCallback"));
        }
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(oapiService, times(1)).execute(
                eq(oapiWrapperAuth), any(), any(),
                eq(URL_PRODOFFERS_ACTIVATE_PUBLIC), any(), bodyCaptor.capture() ,
                any()
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode bodyJson = mapper.readTree(bodyCaptor.getValue());
        JsonNode reason = bodyJson.at("/parameters/0/comment");
        assertFalse(reason.isMissingNode());
        assertEquals("testWorkReason", reason.textValue());
        JsonNode balCharSpecCode = bodyJson.at("/parameters/0/characteristicUses/0/charSpecCode");
        JsonNode balValue = bodyJson.at("/parameters/0/characteristicUses/0/value");
        assertEquals("balanceVolume", balCharSpecCode.textValue());
        assertTrue(balValue.isNumber());

        JsonNode characteristicUses = bodyJson.at("/parameters/0/characteristicUses");
        assertTrue(characteristicUses.isArray());
        assertEquals(2, characteristicUses.size());
        assertEquals("balanceVolume", characteristicUses.get(0).get("charSpecCode").asText());
        assertEquals(45.02, characteristicUses.get(0).get("value").asDouble());
        assertEquals("personalOfferingId", characteristicUses.get(1).get("charSpecCode").asText());
        assertEquals(123, characteristicUses.get(1).get("value").asInt());

        JsonNode productOfferings = bodyJson.at("/parameters");
        assertTrue(productOfferings.isArray());
        Iterator<JsonNode> offeringsIt = productOfferings.elements();
        Set<Long> activatedOfferingIds = Streams.stream(offeringsIt)
                .map(jn -> jn.path("productOfferingId").asLong())
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(List.of(866L, 888L)), activatedOfferingIds);

        //вызов с колбэком второго заказа - ждем isEnd
        executionRequest = new SimpleRunDataExecutionRequest(13L, runData,
                scenarioContext, runDetail, composeCallbackDescription());
        if (request.getRequests() != null) request.getRequests().clear();
        request.add(executionRequest);
        stepResult = this.scenarioService.executeScenario(SCENARIO_EXECUTE_NAME, request, scenarioStepNumber, oapiWrapper,
                oapiWrapperAuth, oapiService, crabWrapper, Locale.getDefault());

        assertNotNull(stepResult);
        for (RunDataExecutionResult oneRes : stepResult.getRunDataResults()) {
            assertNull(oneRes.getError());
            assertNull(oneRes.getOrderId());
            assertTrue(oneRes.isEndStep());
        }

    }

    private RunData composeRunData(Map<String, Object> parsedParams) {
        RunData runData = new RunData();
        runData.setId(55L);
        runData.setCustomerId(2L);
        runData.setSubscriberId(1L);
        runData.setOperationId(66L);
        runData.setNaviUser("UnknownUser");
        runData.setPstxId("pstx-qwerty-123456");
        runData.setParsedParams(parsedParams);
        runData.setCorrelationId("correlation-qwerty-123456");
        return runData;
    }

    private SimpleCallbackDescription composeCallbackDescription() {
        SimpleCallbackDescription desc = new SimpleCallbackDescription();
        desc.setCorrelationId("11-22-33");
        desc.setCallbackBody(Collections.emptyMap());
        desc.setCallbackSource(new SimpleCallbackSource("{}"));
        desc.setStep("no-step");
        desc.setTime(Date.from(Instant.now()));
        desc.setStepStatus(CallbackDescription.SUCCESS);
        return desc;
    }

    private Answer<OapiResponse> stubForStdFullExecuteTests() {
        return new Answer<OapiResponse>() {
            @Override
            public OapiResponse answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] args = invocationOnMock.getArguments();
                HttpMethod method = (HttpMethod) args[1];
                String url = (String) args[3];

                Map<String, Object> respHeaders = new HashMap<>();
                respHeaders.put("Content-Type", "application/json");
                SimpleOapiResponse mockResponse = new SimpleOapiResponse();
                mockResponse.setHeaders(respHeaders);

                if ((method == HttpMethod.GET) && URL_SUBS_TIMEZONE.equalsIgnoreCase(url)) {
                    mockResponse.setCode(200);
                    mockResponse.setContentObject(map(e("zone", map(e("timeZone", "Europe/Moscow")))));
                } else if (method == HttpMethod.POST) {
                    if (URL_PRODOFFERS_ACTIVATE_CHECK.equalsIgnoreCase(url)) {
                        mockResponse.setCode(200);
                        mockResponse.setContentObject(map(e("conflicts", Collections.emptyList())));
                    } else if (URL_PRODOFFERS_ACTIVATE_PARAMETERS.equalsIgnoreCase(url)) {
                        //эту ветку не проверяем - по сути категория ПП запрашивается и для
                        // категории "ТП" особые пляски.
                        mockResponse.setCode(200);
                        mockResponse.setContentObject(Collections.emptyMap());
                    } else if (URL_PRODOFFERS_ACTIVATE.equalsIgnoreCase(url) ||
                            URL_PRODOFFERS_ACTIVATE_PUBLIC.equalsIgnoreCase(url)) {
                        mockResponse.setCode(200);
                        mockResponse.setContentObject(map(e("orderId", 556677L)));
                    } else {
                        mockResponse.setCode(500);
                        mockResponse.setContentObject(map(e("mockError", "unknown url-method")));
                    }
                } else {
                    mockResponse.setCode(500);
                    mockResponse.setContentObject(map(e("mockError", "unknown url-method")));
                }
                return mockResponse;
            }
        };
    }

    /**
     * Входные параметры для функции getActivateParamsBySwitch
     */
    public Object[][] getInputParamsForGetActivateParamsBySwitch() {
        String urlActivateSwitchOn = "/openapi/v2/subscribers/1/productOfferings/activate/bulk";
        List<Map<String, Object>> productOfferingsSwitchOn = list(
                map(
                        e("productOfferingId", 1L),
                        e("activationPeriod", map(e("dateFrom", "2019-09-07T16:00:01"))),
                        e("actionParameters", map(e("checkBalance", false)))
                ),
                map(
                        e("productOfferingId", 2L),
                        e("activationPeriod", map(e("dateFrom", "2019-09-07T16:00:01"),
                                                  e("dateTo", "2020-09-07T16:00:01"))),
                        e("actionParameters", map(e("checkBalance", false)))
                )
        );
        Map<String, Object> bodySwitchOn = map(e("parameters", productOfferingsSwitchOn));


        return new Object[][]{
                {null, productOfferingsSwitchOn, urlActivateSwitchOn, bodySwitchOn}
        };
    }

    /**
     * Тест для функции getActivateParamsBySwitch
     */
    @Test
    @Parameters(method = "getInputParamsForGetActivateParamsBySwitch")
    @SuppressWarnings("unused")
    public void testGetActivateParamsBySwitch(String priority,
                                              List<Map<String, Object>> productOfferings, String url,
                                              Map<String, Object> body) throws Throwable {
        Map<String, Object> headers = map(e("tstHeader", "tstHeaderValue"));
        Map<String, Object> queryParameters = map(e("tstQueryParameters", "tstQueryParametersValue"));

        Map<String, Object> result = this.scenario.getActivateParamsBySwitch(headers, queryParameters,
                productOfferings, 1L, null,false);

        Map<String, Object> resHeaders = getFromMap(result, "changedHeaders");
        Map<String, Object> resBody = getFromMap(result, "body");
        String resUrl = getFromMap(result, "url");
        assertEquals(url, resUrl, "URLs should be equals");
        if (priority != null) {
            assertTrue(resHeaders.containsKey("pstxpriority"), "pstxpriority should be in header");
            assertEquals("tstHeaderValue", getFromMap(resHeaders, "tstHeader"),
                    "tstHeader should be in header");
        } else {
            assertFalse(resHeaders.containsKey("pstxpriority"), "pstxpriority should NOT be in header");
            assertEquals("tstHeaderValue", getFromMap(resHeaders, "tstHeader"),
                    "tstHeader should be in header");
        }
        assertEquals(body, resBody, "Body should be equals");
    }
```
