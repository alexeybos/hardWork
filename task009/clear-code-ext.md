#### 1.1. Методы, которые используются только в тестах:
В процессе поиска я нашел несколько методов, которые перестали вызываться из-за рефакторинга кода, но тесты на данную функциональность остались - ну эти я конечно удалил.
Но вообще, я так понимаю, что целью этого задания были все же методы, которые написаны **специально** для использования в тестах.
Долго искал на GitHub что-то подходящее, в конце концов остановился на таком примере (хотя и явно не самом удачном)
```java
 /**
   * This method is only for testing purspose. It computes TED with a fixed
   * path type in the strategy to trigger execution of a specific single-path
   * function.
   *
   * @param t1 source tree.
   * @param t2 destination tree.
   * @param spfType single-path function to trigger (LEFT or RIGHT).
   * @return tree edit distance.
   */
  public float computeEditDistance_spfTest(Node<D> t1, Node<D> t2, int spfType) {
    // Index the nodes of both input trees.
    init(t1, t2);
    // Initialise delta array.
    delta = new float[size1][size2];
    // Fix a path type to trigger specific spf.
    for (int i = 0; i < delta.length; i++) {
      for (int j = 0; j < delta[i].length; j++) {
        // Fix path type.
        if (spfType == LEFT) {
          delta[i][j] = it1.preL_to_lld(i) + 1;
        } else if (spfType == RIGHT) {
          delta[i][j] = it1.preL_to_rld(i) + 1;
        }
      }
    }
    // Initialise structures for distance computation.
    tedInit();
    // Compute the distance.
    return gted(it1, it2);
  }
  
//один из вызовов в тесте
@Test
public void distanceUnitCostStringNodeDataCostModelSpfL() {
    // Parse the input.
    BracketStringInputParser parser = new BracketStringInputParser();
    Node<StringNodeData> t1 = parser.fromString(testCase.getT1());
    Node<StringNodeData> t2 = parser.fromString(testCase.getT2());
    // Initialise APTED.
    APTED<StringUnitCostModel, StringNodeData> apted = new APTED<>(new StringUnitCostModel());
    // This cast is safe due to unit cost.
    int result = (int)apted.computeEditDistance_spfTest(t1, t2, 0);
    assertEquals(testCase.getD(), result);
}
```
Получается основная проблема в сложности управлением выбором стратегии (left/right) для гарантированного тестирования каждой из них.
На самом деле не уверен, что предлагаемый в итоге вариант лучше используемого, т.к. все же пришлось поменять видимость ряда полей класса (тоже ведь очень плохо).
Вот такой вариант в итоге:
```java
// изменение видимости нужных полей. можно и package-private, но тут без разницы - все равно некрасиво...
protected float[][] delta;
protected InputTreeView<D> it1;
protected InputTreeView<D> it2;

protected void init(Node<D> t1, Node<D> t2) { ... }
protected void tedInit() { ... }
protected float gted(InputTreeView<D> it1, InputTreeView<D> it2) { ... }

//спец. класс в тестовом классе (т.к. данный метод используется только в одном тестовом классе, 
// можно прямо в нем сделать внутренний класс)
private static class TestableAPTED<UCE, D> extends APTED<UCE, D> {
    public static final int SPF_LEFT = 0;
    public static final int SPF_RIGHT = 1;

    private final int spfType;

    public TestableAPTED(UCE costModel, int spfType) {
        super(costModel);
        this.spfType = spfType;
    }
    
    // вместо реального computeEditDistance (не стал уж тащить сюда еще и реализацию computeOptStrategy_postL и computeOptStrategy_postR)
    /* 
    public float computeEditDistance(Node<D> t1, Node<D> t2) {
        // Index the nodes of both input trees.
        init(t1, t2);
        // Determine the optimal strategy for the distance computation.
        // Use the heuristic from [2, Section 5.3].
        if (it1.lchl < it1.rchl) {
          delta = computeOptStrategy_postL(it1, it2);
        } else {
          delta = computeOptStrategy_postR(it1, it2);
        }
        // Initialise structures for distance computation.
        tedInit();
        // Compute the distance.
        return gted(it1, it2);
      }  
  */
    @Override
    public float computeEditDistance(Node<D> t1, Node<D> t2) {
        init(t1, t2);
        int s1 = it1.size;
        int s2 = it2.size;
        delta = new float[s1][s2];

        // вместо стандартной стратегии выбора (т.е. вместо computeOptStrategy_postL и computeOptStrategy_postR)
        for (int i = 0; i < delta.length; i++) {
            for (int j = 0; j < delta[i].length; j++) {
                if (spfType == SPF_LEFT) {
                    delta[i][j] = it1.preL_to_lld(i) + 1;
                } else if (spfType == SPF_RIGHT) {
                    delta[i][j] = it1.preL_to_rld(i) + 1;
                }
            }
        }
        tedInit();
        return gted(it1, it2);
    }
}

//теперь вызов в тесте
@Test
public void distanceUnitCostStringNodeDataCostModelSpfL() {
    // Parse the input.
    BracketStringInputParser parser = new BracketStringInputParser();
    Node<StringNodeData> t1 = parser.fromString(testCase.getT1());
    Node<StringNodeData> t2 = parser.fromString(testCase.getT2());
    // Initialise APTED.
    APTED<StringUnitCostModel, StringNodeData> apted = new TestableAPTED<>(new StringUnitCostModel(), TestableAPTED.SPF_LEFT);
    // This cast is safe due to unit cost.
    int result = (int)apted.computeEditDistance_spfTest(t1, t2, 0);
    assertEquals(testCase.getD(), result);
}
```
Получилось конечно так себе, но другого подходящего примера не нашел. Ну, по крайней мере от самого тестового метода в "продакшен коде" избавился.
Ну а вообще, в зависимости от причин появления таких "методов для тестов", используется несколько основных подходов:
1. использование reflection API для доступа к private полям/методам (в принципе тоже не очень рекомендуемый вариант)
2. использование моков (Mockito)
3. вынесение логики в отдельный метод/класс, если для тестирования требуется слишком глубокое отдельное тестирования private метода.
4. тестирование "поведения, а не состояния" вместо ситуации, когда, например, создается специальный геттер для получения какого-то внутреннего состояния "в интересах" тестирования.   


#### 1.2. Цепочки методов. Метод вызывает другой метод, который вызывает другой метод, который вызывает другой метод, который вызывает другой метод... и далее и далее.
В рабочих проектах из подобного нашел только цепочки геттеров. Собственно это тоже нарушение закона Деметры, но думал что все таки будут еще цепочки с промежуточной модификацией. Нету. 
Типовой пример:
```java
private BulkRequestShort composeBulkRequestShort(Long requestId, RequestTrackInfo rti) {
    BulkRequestShort requestShort = new BulkRequestShort();
    //...
    requestShort.setCreated(rti.getRunInfo().getRun().getRequest().getNaviDate().toInstant());
}

```
Исправленный вариант с делегированием методов, чтобы каждый класс знал только о соседе:
```java
public class LoadRequest {
    //...
    private Date naviDate;
    //...
    public Instant getNaviInstant() {
        return this.naviDate != null ? this.naviDate.toInstant() : null;
    }
}

public class Run extends BulkRequestReferenced {
    
    private LoadRequest request;
    //...
    public Instant getRequestNaviInstant() {
        return this.request != null ? this.request.getNaviInstant() : null;
    }

}

public class RunInfo {
    @Getter @Setter private Run run;
    //...
    public Instant getRequestNaviInstant() {
        return this.run != null ? this.run.getRequestNaviInstant() : null;
    }
}

public class RequestTrackInfo {
    private RunInfo runInfo;
    //...
    public Instant getRequestNaviInstant() {
        return this.runInfo != null ? this.runInfo.getRequestNaviInstant() : null;
    }
}

// Итого:
private BulkRequestShort composeBulkRequestShort(Long requestId, RequestTrackInfo rti) {
    BulkRequestShort requestShort = new BulkRequestShort();
    //...
    requestShort.setCreated(rti.getRequestNaviInstant());
}
```
Ну а в случаях, когда нет возможности изменять классы в данной цепочке, чтобы внезапно не получить NPE в таких цепочках можно "изолировать" данную цепочку в отдельном методе с защитой от null.

#### 1.3. У метода слишком большой список параметров.
А вот тут с кандидатами на рефакторинг вообще не было проблем, к сожалению. Взял вот такую функцию:
```java
public String startOrderWatch(CrabWrapper crabWrapper, String externalId, String pstxId, Long runId, Long runDataId,
                                  int stepNumber, String orderId, String routingKey,
                                  Locale locale) throws AppException {
        OrderWatchParam orderWatchParam = new OrderWatchParam();
        orderWatchParam.setRunId(runId);
        orderWatchParam.setRunDataId(runDataId);
        orderWatchParam.setStepNumber(stepNumber);
        try {
            orderWatchParam.setOrderId(Long.valueOf(orderId, 10));
        } catch (NumberFormatException e) {
            throw new BusinessException(lbl.msg(locale, "JsonCrabService.startOrderWatch.invalidOrderId", orderId,
                    e), e);
        }
        orderWatchParam.setRoutingKeyCallback(routingKey);

        RunCrabRequestResult response = run(crabWrapper, externalId, CrabCommand.PROCESSING_TYPE_PARALLEL, pstxId,
                CHECK_ORDER_SCENARIO_NAME, orderWatchParam, locale);

        if (response.isSuccess()) {
            return response.getExternalId();
        } else {
            throw new AppException(lbl.msg(locale, "JsonCrabService.startOrderWatch.error",
                    orderId, runId, runDataId, response.getState()));
        }
    }
```
В качестве основного способа рефакторинга использовал паттерн Builder для класса, в который вынес все используемые в вызове метода параметры.
В билдере, кстати, можно было бы еще добавить и валидацию некоторых параметров, но немного поленился. Внутри самого метода есть вызов еще одной функции с большим количеством параметров (run с семью параметрами). 
Ее парамтеры тоже можно "переоформить" на паттерн Builder и я тоже сделал примерный вариант ее вызова в таком варианте.
```java
package com.peterservice.oapi.subsloader.bulkrunner.service.crab.std;

import com.peterservice.oapi.subsloader.bulkrunner.util.crab.wrapper.CrabWrapper;

import java.util.Locale;

public class StartOrderWatchParams {
    private final CrabWrapper crabWrapper;
    private final String externalId;
    private final String pstxId;
    private final Long runId;
    private final Long runDataId;
    private final int stepNumber;
    private final String orderId;
    private final String routingKey;
    private final Locale locale;

    private StartOrderWatchParams(Builder builder) {
        this.crabWrapper = builder.crabWrapper;
        this.externalId = builder.externalId;
        this.pstxId = builder.pstxId;
        this.runId = builder.runId;
        this.runDataId = builder.runDataId;
        this.stepNumber = builder.stepNumber;
        this.orderId = builder.orderId;
        this.routingKey = builder.routingKey;
        this.locale = builder.locale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private CrabWrapper crabWrapper;
        private String externalId;
        private String pstxId;
        private Long runId;
        private Long runDataId;
        private int stepNumber;
        private String orderId;
        private String routingKey;
        private Locale locale;

        public Builder crabWrapper(CrabWrapper crabWrapper) {
            this.crabWrapper = crabWrapper;
            return this;
        }
        public Builder externalId(String externalId) {
            this.externalId = externalId;
            return this;
        }
        public Builder pstxId(String pstxId) {
            this.pstxId = pstxId;
            return this;
        }
        public Builder runId(Long runId) {
            this.runId = runId;
            return this;
        }
        public Builder runDataId(Long runDataId) {
            this.runDataId = runDataId;
            return this;
        }
        public Builder stepNumber(int stepNumber) {
            this.stepNumber = stepNumber;
            return this;
        }
        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }
        public Builder routingKey(String routingKey) {
            this.routingKey = routingKey;
            return this;
        }
        public Builder locale(Locale locale) {
            this.locale = locale;
            return this;
        }
        public StartOrderWatchParams build() {
            return new StartOrderWatchParams(this);
        }
    }

    public CrabWrapper getCrabWrapper() {
        return crabWrapper;
    }
    public String getExternalId() {
        return externalId;
    }
    public String getPstxId() {
        return pstxId;
    }
    public Long getRunId() {
        return runId;
    }
    public Long getRunDataId() {
        return runDataId;
    }
    public int getStepNumber() {
        return stepNumber;
    }
    public String getOrderId() {
        return orderId;
    }
    public String getRoutingKey() {
        return routingKey;
    }
    public Locale getLocale() {
        return locale;
    }
}

public String startOrderWatch2(StartOrderWatchParams startParams) throws AppException {
    OrderWatchParam orderWatchParam = new OrderWatchParam();
    orderWatchParam.setRunId(startParams.getRunId());
    orderWatchParam.setRunDataId(startParams.getRunDataId());
    orderWatchParam.setStepNumber(startParams.getStepNumber());
    try {
        orderWatchParam.setOrderId(Long.valueOf(startParams.getOrderId(), 10));
    } catch (NumberFormatException e) {
        throw new BusinessException(lbl.msg(startParams.getLocale(),
                "JsonCrabService.startOrderWatch.invalidOrderId", startParams.getOrderId(),
                e), e);
    }
    orderWatchParam.setRoutingKeyCallback(startParams.getRoutingKey());

//        RunCrabRequestResult response = run(crabWrapper, externalId, CrabCommand.PROCESSING_TYPE_PARALLEL, pstxId,
//                CHECK_ORDER_SCENARIO_NAME, orderWatchParam, locale);
    RunOrderWatchRequest runRequest = RunOrderWatchRequest.builder()
            .crabWrapper(startParams.getCrabWrapper())
            .externalId(startParams.getExternalId())
            .processingType(CrabCommand.PROCESSING_TYPE_PARALLEL)
            .pstxId(startParams.getPstxId())
            .handlerName(CHECK_ORDER_SCENARIO_NAME)
            .params(orderWatchParam)
            .locale(startParams.getLocale())
            .build();
    RunCrabRequestResult response = run(runRequest);

    if (response.isSuccess()) {
        return response.getExternalId();
    } else {
        throw new AppException(lbl.msg(startParams.getLocale(), "JsonCrabService.startOrderWatch.error",
                startParams.getOrderId(), startParams.getRunId(), startParams.getRunDataId(), response.getState()));
    }
}

// новый вызов для startOrderWatch2
// this.crabService.startOrderWatch(this.crabWrapper, "order-track-" + msg.orderId, msg.pstxid, msg.runId,
//msg.runDataId, msg.stepNumber, msg.orderId, this.routingKey, Locale.getDefault());
StartOrderWatchParams params = StartOrderWatchParams.builder()
        .crabWrapper(this.crabWrapper)
        .externalId("order-track-" + msg.orderId)
        .pstxId(msg.pstxid)
        .runId(msg.runId)
        .runDataId(msg.runDataId)
        .stepNumber(msg.stepNumber)
        .orderId(msg.orderId)
        .routingKey(this.routingKey)
        .locale(Locale.getDefault())
        .build();
this.crabService.startOrderWatch2(params);
```
Получаем как минимум следующие положительные эффекты: 
1. вместо 9 и 7 параметров вызова по одному для каждой функции (startOrderWatch и run)
2. возможность встраивания валидации параметров непосредственно в билдеры
3. исключаем вероятность перепутать порядок параметров при вызове функций (особенно с учетом того, что половина из них имеет тип String)

#### 1.4. Странные решения. Когда несколько методов используются для решения одной и той же проблемы, создавая несогласованность.

Тут на самом деле у нас в наследство от предыдущей команды осталась реализация двух wrapper (OpaiWrapper и CrabWrapper).
Скорее всего изначально в них планировались какие-то существенные различия в поведении, но по итогу очень много действий дублируется с небольшими отличиями.
Например, два HTTP-клиента для OAPI и CRAB (используются соответственно в OapiServiceApacheImpl и SimpleCrabWrapper), которые различаются в аутентификации и в реакции на 401 ошибку, в остальном же реализации похожие.
На самом деле некоторое время назад мы постоянно путались в этих клиентах.
Полный рефакторинг тут я наверное не готов привести - объемный получается, но вот концептуально...
Собственно идея - вынести всю общую HTTP-логику в отдельный (уже существующий) пакет, в котором уже были классы для HTTP-клиента, используемого в OapiServiceApacheImpl.
Ну и перенаправить зависимости от OapiServiceApacheImpl и SimpleCrabWrapper на эту общую логику. Наверное, как-то так (на самом деле наброски такого рефакторинга уже у нас были):
Для OapiServiceApacheImpl
```java
// Было
public class OapiServiceApacheImpl implements OapiService {

    LabelService labelService;
    ConfigService configService;
    //...
    private CloseableHttpResponse executeHttpRequest(HttpMethod httpMethod, URI uri, Map<String, String> headers,
                                                     String body, int connGetTimeoutMillis, int responseTimeoutMillis)
            throws IOException {
        HttpRequestBase request;
        // метод, ури, боди
        if (httpMethod.equals(GET)) {
            request = new HttpGet(uri);
        } else if (httpMethod.equals(POST)) {
            request = new HttpPost(uri);
        } else if (httpMethod.equals(PUT)) {
            request = new HttpPut(uri);
        } else if (httpMethod.equals(PATCH)) {
            request = new HttpPatch(uri);
        } else if (httpMethod.equals(DELETE)) {
            request = new HttpDelete(uri);
        } else {
            throw new IllegalArgumentException("http method=" + httpMethod + " currently not supported");
        }
        if (body != null && request instanceof HttpEntityEnclosingRequest) {
            // content-type должен быть предоставлен
            if (!headers.containsKey(CONTENT_TYPE_HEADER)) {
                throw new IllegalArgumentException("content type header absense is incompatible with body-contained request");
            }
            // кодирование с оглядкой на возможный content-encoding
            Charset charSet = Charset.forName(headers.getOrDefault(CONTENT_ENCODING_HEADER, UTF_8.toString()));
            HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) request;
            enclosingRequest.setEntity(new ByteArrayEntity(body.getBytes(charSet)));
        }

        // валидация и выставление таймаутов
        if (connGetTimeoutMillis <= 0 || responseTimeoutMillis <= 0) {
            throw new IllegalArgumentException("timeouts values must be positive. Values are: connGet = " +
                    connGetTimeoutMillis + " response = " + responseTimeoutMillis);
        }
        RequestConfig reqConfig = RequestConfig.custom().setConnectionRequestTimeout(connGetTimeoutMillis)
                .setSocketTimeout(responseTimeoutMillis).build();
        request.setConfig(reqConfig);

        // заголовки
        if (headers != null) {
            headers.forEach(request::setHeader);
        }

        HttpStatistic statistic = HttpStatistic.getInstance();
        long start = System.currentTimeMillis();
        try {
            CloseableHttpResponse response = this.httpClient.execute(request, httpClientContext);
            int statusCode = response.getStatusLine().getStatusCode();
            statistic.process(httpMethod, statusCode, uri.getPath(), start, System.currentTimeMillis());
            return response;
        } catch (IOException e) {
            if (e instanceof SocketTimeoutException) {
                statistic.timeoutException(httpMethod, uri.getPath(), start, System.currentTimeMillis());
            } else {
                statistic.errorOnUserLevel(httpMethod, uri.getPath(), start, System.currentTimeMillis());
            }
            throw e;
        }
    }

}

// Стало
public class OapiServiceApacheImpl implements OapiService {

    LabelService labelService;
    ConfigService configService;
    //...
    private CloseableHttpResponse executeHttpRequest(HttpMethod httpMethod, URI uri, Map<String, String> headers,
                                                     String body, int connGetTimeoutMillis, int responseTimeoutMillis)
            throws IOException {
        if (connGetTimeoutMillis <= 0 || responseTimeoutMillis <= 0) {
            throw new IllegalArgumentException("timeouts values must be positive. Values are: connGet = " +
                    connGetTimeoutMillis + " response = " + responseTimeoutMillis);
        }
        HttpRequestBase request = ApacheHttpRequests.create(httpMethod, uri, body, headers,
                CONTENT_TYPE_HEADER, CONTENT_ENCODING_HEADER);
        RequestConfig reqConfig = RequestConfig.custom().setConnectionRequestTimeout(connGetTimeoutMillis)
                .setSocketTimeout(responseTimeoutMillis).build();
        request.setConfig(reqConfig);
        ApacheHttpRequests.applyHeaders(request, headers);

        HttpStatistic statistic = HttpStatistic.getInstance();
        long start = System.currentTimeMillis();
        try {
            CloseableHttpResponse response = this.httpClient.execute(request, httpClientContext);
            int statusCode = response.getStatusLine().getStatusCode();
            statistic.process(httpMethod, statusCode, uri.getPath(), start, System.currentTimeMillis());
            return response;
        } catch (IOException e) {
            if (ApacheHttpIOExceptionUtil.isNetworkFailure(e)) {
                statistic.timeoutException(httpMethod, uri.getPath(), start, System.currentTimeMillis());
            } else {
                statistic.errorOnUserLevel(httpMethod, uri.getPath(), start, System.currentTimeMillis());
            }
            throw e;
        }
    }
}
```
Для SimpleCrabWrapper
```java
// Было
public class SimpleCrabWrapper implements CrabWrapper {
    public static final String HEADER_NAME_VALUE_SEPARATOR = ": ";
    private final static Logger LOG = LoggerFactory.getLogger(SimpleCrabWrapper.class);
    //...
    public CrabResult query(HttpMethod httpMethod, String query, Map<String, String> params, byte[] body,
                            String contentType, Charset contentCharset, String accept, Charset acceptCharset,
                            Locale locale)
            throws AppException {
        URI callUri;
        try {
            URIBuilder uriBuilder = new URIBuilder("http://" + this.crabServer +
                    (query.startsWith("/") ? query : "/" + query));
            if (params != null) {
                for (String paramName : params.keySet()) {
                    uriBuilder.addParameter(paramName, params.get(paramName));
                }
            }
            callUri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new BusinessException(labelService.msg(locale, "SimpleCrabWrapper.query.uri.error",
                    query, params, e), e);
        }

        if (StringUtils.isBlank(this.authToken)) {
            login();
        }
        HttpRequestBase crabRequest;
        if (httpMethod.equals(GET)) {
            crabRequest = new HttpGet(callUri);
        } else if (httpMethod.equals(DELETE)) {
            crabRequest = new HttpDelete(callUri);
        } else if (httpMethod.equals(PATCH)) {
            crabRequest = new HttpPatch(callUri);
        } else if (httpMethod.equals(PUT)) {
            crabRequest = new HttpPut(callUri);
        } else if (httpMethod.equals(POST)) {
            crabRequest = new HttpPost(callUri);
        } else {
            throw new BusinessException("Unknown request method: " + httpMethod);
        }
        if (crabRequest instanceof HttpEntityEnclosingRequest && body != null) {
            HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) crabRequest;
            enclosingRequest.setEntity(new ByteArrayEntity(body));
        }
        //        crabRequest.setConfig...
//        crabRequest.setHeader...
//        crabRequest.setHeader...
//        crabRequest.setHeader...


        CrabResult res;
        try (CloseableHttpResponse response = this.httpClient.execute(crabRequest, httpClientContext)) {
            res = extractResponse(response, callUri.toString());
        } catch (IOException e) {
            if (e instanceof UnknownHostException) {
                LOG.debug("cannot request CRAB service, explicit ResourceUnavailable exception type", e);
                throw new ResourceUnavailableException(
                        labelService.msg("SimpleCrabWrapper.query.error.serverNotFound", query, params, e), e);
            } else {
                LOG.debug("cannot request CRAB service", e);
                throw new AppException(labelService.msg("SimpleCrabWrapper.query.error", query, params, e), e);
            }
        }

        return res;
    }

}

// Стало
public class SimpleCrabWrapper implements CrabWrapper {
    public static final String HEADER_NAME_VALUE_SEPARATOR = ": ";
    private final static Logger LOG = LoggerFactory.getLogger(SimpleCrabWrapper.class);
    //...
    public CrabResult query(HttpMethod httpMethod, String query, Map<String, String> params, byte[] body,
                            String contentType, Charset contentCharset, String accept, Charset acceptCharset,
                            Locale locale)
            throws AppException {
        URI callUri;
        try {
            URIBuilder uriBuilder = new URIBuilder("http://" + this.crabServer +
                    (query.startsWith("/") ? query : "/" + query));
            if (params != null) {
                for (String paramName : params.keySet()) {
                    uriBuilder.addParameter(paramName, params.get(paramName));
                }
            }
            callUri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new BusinessException(labelService.msg(locale, "SimpleCrabWrapper.query.uri.error",
                    query, params, e), e);
        }

        ensureAuthenticated(locale);
        HttpRequestBase crabRequest = ApacheHttpRequests.create(httpMethod, callUri, body, contentCharset);
        //...
//        crabRequest.setConfig...
//        crabRequest.setHeader...
//        crabRequest.setHeader...
//        crabRequest.setHeader...


        CrabResult res;
        try (CloseableHttpResponse response = this.httpClient.execute(crabRequest, httpClientContext)) {
            res = extractResponse(response, callUri.toString(), locale);
        } catch (IOException e) {
            throw ApacheHttpIOExceptionUtil.toAppException(e,
                    labelService.msg("SimpleCrabWrapper.query.error.serverNotFound", query, params, e),
                    labelService.msg("SimpleCrabWrapper.query.error", query, params, e));
        }

        return res;
    }
}
```
Общие классы (пример):
```java
public final class ApacheHttpRequests {

    private ApacheHttpRequests() {
    }

    public static HttpRequestBase create(HttpMethod httpMethod, URI uri) {
        if (httpMethod.equals(GET)) {
            return new HttpGet(uri);
        }
        if (httpMethod.equals(POST)) {
            return new HttpPost(uri);
        }
        if (httpMethod.equals(PUT)) {
            return new HttpPut(uri);
        }
        if (httpMethod.equals(PATCH)) {
            return new HttpPatch(uri);
        }
        if (httpMethod.equals(DELETE)) {
            return new HttpDelete(uri);
        }
        throw new IllegalArgumentException("http method=" + httpMethod + " currently not supported");
    }

    public static HttpRequestBase create(HttpMethod httpMethod, URI uri, byte[] body, Charset contentCharset) {
        HttpRequestBase request = create(httpMethod, uri);
        if (body != null && request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) request;
            enclosingRequest.setEntity(new ByteArrayEntity(body));
        }
        return request;
    }

    public static HttpRequestBase create(HttpMethod httpMethod, URI uri, String body,
                                         Map<String, String> headers, String contentTypeHeader,
                                         String contentEncodingHeader) {
        HttpRequestBase request = create(httpMethod, uri);
        if (body != null && request instanceof HttpEntityEnclosingRequest) {
            if (headers != null && !headers.containsKey(contentTypeHeader)) {
                throw new IllegalArgumentException("content type header absense is incompatible with body-contained request");
            }
            Charset charSet = Charset.forName(headers != null
                    ? headers.getOrDefault(contentEncodingHeader, UTF_8.toString())
                    : UTF_8.toString());
            HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) request;
            enclosingRequest.setEntity(new ByteArrayEntity(body.getBytes(charSet)));
        }
        return request;
    }

    public static void applyHeaders(HttpRequestBase request, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(request::setHeader);
        }
    }
}

public final class ApacheHttpIOExceptionUtil {

    private ApacheHttpIOExceptionUtil() {
    }

    public static boolean isNetworkFailure(IOException e) {
        return e instanceof UnknownHostException
                || e instanceof SocketTimeoutException
                || (e.getCause() != null && e.getCause() instanceof UnknownHostException);
    }

    public static boolean isNetworkFailure(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof IOException && isNetworkFailure((IOException) current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static AppException toAppException(IOException e, String resourceUnavailableMessage, String appExceptionMessage) {
        if (isNetworkFailure(e)) {
            return new ResourceUnavailableException(resourceUnavailableMessage, e);
        }
        return new AppException(appExceptionMessage, e);
    }
}
```
Это вот самое известное мне место в рабочем проекте. Более "мелких" примеров для рефакторинга найти не смог.

#### 1.5. Чрезмерный результат. Метод возвращает больше данных, чем нужно вызывающему его компоненту. 
Тоже в принципе относительно распространенная проблема в продукте. В основном из-за изначально закладываемого универсализма, который впоследствии не используется.
```java
public static Map getSubscriberInfo(OapiWrapper oapiWrapper, Logger logger, OapiService oapiService,
                                        LabelService labelService, String scenarioRequestId, Long subscriberId,
                                        String fields, Locale locale) throws AppException {

        String url = "/openapi/v1/subscribers/" + subscriberId;
        Map headers = new HashMap();
        headers.put("Content-Type", "application/json");
        Map requestParams = new HashMap();
        requestParams.put("fields", fields);

        OapiResponse result = oapiService.execute(oapiWrapper, HttpMethod.GET, headers, url, requestParams,
                null, locale);
        Map resultMap = (Map) result.getContentObject();
        CommonUtils.checkResponseCode(logger, labelService, result.getCode(), resultMap, url, scenarioRequestId, locale);

        return resultMap;
    }
    
// При этом данный метод с самого создания используется только для получения (отдельно) полей msisdn и timeZone, для чего 
// сделаны специальные предопределенный public методы
// public static String getTimezone
// public static String getMsisdn
// Однако несмотря на это в ряде мест вызывается именно общий метод getSubscriberInfo от результата которого берется только msisdn и timeZone
// осталось или исторически до появления выделенных методов или же copy-past в новые сценарии из старых. 
// В качестве решения просто поменять модификатор на private, а вызовы (таковых немного) заменить на соответствующие узкоспециальные методы
private static Map getSubscriberInfo(OapiWrapper oapiWrapper, Logger logger, OapiService oapiService,
                                    LabelService labelService, String scenarioRequestId, Long subscriberId,
                                    String fields, Locale locale) throws AppException {

    /// ...
}
```