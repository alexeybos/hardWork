На самом деле данный материал вызывает у меня определенную сложность еще с первого прочтения, наверное больше года назад (первая часть).
Неуловимое чувство, что понимание где-то вот-вот прям близко, но в руки не дается. Так что даже и сейчас я не в полной мере уверен в правильности своего понимания всей концепции в деталях. 

Примеры кода наверное не очень удачные, т.к. по работе сейчас кодирования почти нет и не придумал ничего, что можно было бы нового добавить в дипломный или в три-в-ряд...
Поэтому реализация задания здесь на примере небольшого groovy-скрипта для групповой операции по передаче данных по задолженности в госуслуги.

Код по первому проходу (по сути тут код следует тестам):
```groovy
//вот такие тесты (два для примера, плюс несколько подобных тестов)
@Test
void testCheckInputParameters_WithoutContactsNotMfOrYota() {
    inputParams = new HashMap<>();
    inputParams.put("name", "123");
    inputParams.put("surname", "123");
    inputParams.put("identificationTypeId", "123");
    inputParams.put("number", "123");
    inputParams.put("dateOfIssue", "123");
    inputParams.put("serviceProviderCode", "123");
    inputParams.put("dueDate", "123");
    inputParams.put("sum", "123");
    ReflectionTestUtils.setField(scenario, "inParams", inputParams);
    Throwable exception = assertThrows(BusinessException.class,
            () -> callMethod(scenario, "checkInputParameters"));
    assertTrue(exception.getMessage().contains("contactPhone, appName"));
}

@Test
void testCheckInputParameters_PositiveMF() throws Throwable {
    inputParams = new HashMap<>();
    inputParams.put("name", "123");
    inputParams.put("surname", "123");
    inputParams.put("identificationTypeId", "123");
    inputParams.put("number", "123");
    inputParams.put("dateOfIssue", "123");
    inputParams.put("serviceProviderCode", "MF");
    inputParams.put("dueDate", "123");
    inputParams.put("sum", "123");
    ReflectionTestUtils.setField(scenario, "inParams", inputParams);
    callMethod(scenario, "checkInputParameters");
}

// собственно код:
/**
 * Checking input parameters
 *
 */
void checkInputParameters() {
    List<String> requiredPropertyNames = new ArrayList()
    String name = getString(inParams, "name")
    //обязательные
    if (StringUtils.isBlank(name)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.name"))
    }
    String surname = getString(inParams, "surname")
    if (StringUtils.isBlank(surname)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.surname"))
    }
    String identificationTypeId = getString(inParams, "identificationTypeId")
    if (StringUtils.isBlank(identificationTypeId)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.identificationTypeId"))
    }
    String number = getString(inParams, "number")
    if (StringUtils.isBlank(number)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.number"))
    }
    String dateOfIssue = getString(inParams, "dateOfIssue")
    if (StringUtils.isBlank(dateOfIssue)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.dateOfIssue"))
    }
    String serviceProviderCode = getString(inParams, "serviceProviderCode")
    if (StringUtils.isBlank(serviceProviderCode)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.serviceProviderCode"))
    }
    String dueDate = getString(inParams, "dueDate")
    if (StringUtils.isBlank(dueDate)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.dueDate"))
    }
    String sum = getString(inParams, "sum")
    if (StringUtils.isBlank(sum)) {
        requiredPropertyNames.add(labelService.msg("SMEVDebtsNotify.property.sum"))
    }

    List<String> errors = new ArrayList();
    if (requiredPropertyNames.size() > 0) {
        def propertyNameString = requiredPropertyNames.join(', ')
        errors.add(labelService.msg("SMEVDebtsNotify.check.mandatoryParamsNotExists", propertyNameString))
    }

    //условно обязательные для случая, когда serviceProviderCode не MF и YOTA
    if (!PROVIDER_CODE_MF.equalsIgnoreCase(serviceProviderCode) &&
            !PROVIDER_CODE_YOTA.equalsIgnoreCase(serviceProviderCode)) {
        String contacts = getString(inParams, "contactPhone")
        if (StringUtils.isBlank(contacts)) contacts = getString(inParams, "appName")
        if (StringUtils.isBlank(contacts)) {
            errors.add(labelService.msg("SMEVDebtsNotify.execute.conditionalParamsNotExists",
                    "contactPhone, appName"))
        }
    }

    if (errors.size() > 0) {
        def errorsString = errors.join(',\n')
        LOGGER.error(errorsString)
        throw new BusinessException(errorsString)
    }
}
```
Здесь мне кажется, что как раз присутствует недостаток, описанный во втором материале, когда в тестах наверняка пропущены какие-нибудь проверки. Код в основном следует тестам, хотя не должен этого делать.    
Но вообще поймал себя на мысли, что всегда "по-умолчанию" считал, что при TDD код просто обязан следовать тестам, т.к. по идее тесты следуют спецификации и, транзитивно, код тоже следует спецификации. Хотя по факту ведь получается, что в основном тесты это уже конкретная реализация и код начинает следовать уже не третьему уровню, а, по сути, второму логическому уровню.  
И почему-то даже явное указание из второго материала по уровням размышления о системе очень на короткое время избавляют меня от этого заблуждения и быстро откатывается опять на второй уровень.
Это конечно совсем не хорошо, но каждое знакомство с этим материалом (а я уже раза минимум третий раз к нему возвращаюсь) для меня снова открытие (или скорее новое напоминание) что следовать надо дизайну (спецификации, а не коду или тесту).
В общем, как и прежде - надо периодически, наверное вообще раз в месяц перечитывать этот блок материалов.

Далее собственно попытка отрефлексировать материалы по уровням рассуждений: 
```groovy
/**
 * Маппинг и дообогащение параметров запроса для последующей передачи в PIC.
 * Формирование тела запроса в PIC согласно его спецификации.
 * @param request
 * @return
 */
Map prepareBodyParameters(RunDataExecutionRequest request) {
    Map debtorDetails = new HashMap<>()

    // заполнение структуры nameInfo
    debtorDetails.put("nameInfo", getNameInfo())

    String birthDate = getString(inParams, "birthDate")
    if (birthDate) debtorDetails.put("birthDate", birthDate)

    // заполнение структуры identificationDocument
    debtorDetails.put("identificationDocument", getIdentificationDocument())

    // заполнение структуры identificationAttributes
    debtorDetails.put("identificationAttributes", getIdentificationAttributes())

    //serviceProviderCode
    String serviceProviderCode = getString(inParams, "serviceProviderCode")
    debtorDetails.put("serviceProviderCode", serviceProviderCode)

    //заполнение информации о долге (debt info)
    Map debtDetails = getDebtDetails(request)

    String contactPhone = getContactPhone(serviceProviderCode)
    if (contactPhone) debtDetails.put("contactPhone", contactPhone)
    String appName = getAppName(serviceProviderCode)
    if (appName) debtDetails.put("appName", appName)

    return [debtorDetails: debtorDetails, debtDetails: debtDetails]
}

//метод для получение и заполнение структуры с основной информацией по имени должника
Map getNameInfo() {
    Map nameInfo = [
            name: getString(inParams, "name"),
            surname: getString(inParams, "surname")
    ]
    String patronymic = getString(inParams, "patronymic")
    if (patronymic) nameInfo.put("patronymic", patronymic)
    return nameInfo
}

//метод для получение и заполнение структуры с информацией по документу, удостоверящему личность
Map getIdentificationDocument() {
    Map identificationDocument = new HashMap()
    identificationDocument.put("type", [identificationTypeId: getLong(inParams, "identificationTypeId")])
    String series = getString(inParams, "series")
    if (series) identificationDocument.put("series", series)
    identificationDocument.put("number", getString(inParams, "number"))
    identificationDocument.put("dateOfIssue", getString(inParams, "dateOfIssue"))
    return identificationDocument
}

// метод получения информации по долгу
Map getDebtDetails(RunDataExecutionRequest request) {
    Map debtDetails = new HashMap()
    String phone = request.getRunData().getMsisdn()
    if (phone) {
        debtDetails.put("phone", phone)
    } else {
        String contract = CustomersUtils.getAccountNumber(LOGGER, oapiService, labelService, oapiWrapper,
                scenarioRequestId, customerId, locale)
        if (contract) debtDetails.put("contract", contract)
    }
    debtDetails.put("dueDate", getString(inParams, "dueDate"))
    debtDetails.put("sum", getDouble(inParams, "sum"))
    return debtDetails
}

//метод получения контактного телефона в зависимости от наличия его во входных параметрах и провайдера
private String getContactPhone(String serviceProviderCode) {
    String contactPhone = getString(inParams, "contactPhone")
    if (contactPhone) return contactPhone
    if (PROVIDER_CODE_MF.equalsIgnoreCase(serviceProviderCode)
            || PROVIDER_CODE_YOTA.equalsIgnoreCase(serviceProviderCode)) return CONTACT_PHONE
    return contactPhone
}

//метод получения приложения для контакта зависимости от наличия его во входных параметрах и провайдера
private String getAppName(String serviceProviderCode) {
    String appName = getString(inParams, "appName")
    if (appName) return appName
    if (PROVIDER_CODE_MF.equalsIgnoreCase(serviceProviderCode)) return APP_NAME_MF
    if (PROVIDER_CODE_YOTA.equalsIgnoreCase(serviceProviderCode)) return APP_NAME_YOTA
    return appName
}

/**
 * Подготовка identificationAttributes - дополнительных атрибутов для идентификации во внешних системах (госуслугах)
 * Алгоритм
 * 1. Берет входные данные из параметров INN, email, phoneNumber, SNILS
 * 2. Если ВСЕ перечисленные поля пустые, то
 *    (а) в поле email заносит значение EMAIL = "info@megafon.ru"
 */
Map getIdentificationAttributes() {
    Map identificationAttributes = new HashMap()
    String inn = getString(inParams, "INN")
    String email = getString(inParams, "email")
    String phoneNumber = getString(inParams, "phoneNumber")
    String snils = getString(inParams, "SNILS")

    //Если ВСЕ поля пустые, то заполняем поле email
    if (!inn && !email && !phoneNumber && !snils) {
        identificationAttributes.put("email", EPGU_EMAIL)
        return identificationAttributes
    }

    if (inn) identificationAttributes.put("INN", inn)
    if (email) identificationAttributes.put("email", email)
    if (phoneNumber) identificationAttributes.put("phoneNumber", phoneNumber)
    if (snils) identificationAttributes.put("SNILS", snils)
    return identificationAttributes
}
```
В данной части кода попытка следования дизайну, при котором для получения конкретных частей итогового объекта для отправки дальше используются отдельные методы (ну и конкретные наименования используемых констант - но это я и так всегда делаю).
Первоначально по привычке (и по накатанной) планировал всю реализацию в рамках одного метода (максимум двух).
В итоге (хочется верить) и тесты следуют спецификации, проверяя заполнение структур согласно требованиям документации, а не следуют коду. И в свою очередь код, не подчиняется написанным тестам, а также следует спецификации.  
В общем, все равно спокойной уверенности в понимании у меня нет. Лишнее напоминание об обязательном периодическом возвращении к этим важным материалам - по крайней мере после каждого ознакомления с ними у меня ощущение, что я стал понимать больше.