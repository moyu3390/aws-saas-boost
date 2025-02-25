/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazon.aws.partners.saasfactory.saasboost;

import com.amazon.aws.partners.saasfactory.saasboost.appconfig.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SettingsServiceDAL {

    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsServiceDAL.class);
    private static final String SAAS_BOOST_ENV = System.getenv("SAAS_BOOST_ENV");
    private static final String OPTIONS_TABLE = System.getenv("OPTIONS_TABLE");
    private static final String AWS_REGION = System.getenv("AWS_REGION");

    // Package private for testing
    static final String SAAS_BOOST_PREFIX = "saas-boost";
    static final String APP_BASE_PATH = "app/";
    static final String PARAMETER_STORE_PREFIX = "/" + SAAS_BOOST_PREFIX + "/" + SAAS_BOOST_ENV + "/";
    // e.g. /saas-boost/production/SAAS_BOOST_BUCKET
    static final Pattern SAAS_BOOST_PARAMETER_PATTERN = Pattern.compile("^" + PARAMETER_STORE_PREFIX + "(.+)$");
    // e.g. /saas-boost/test/app/APP_NAME or /saas-boost/test/app/myService/SERVICE_JSON
    static final Pattern SAAS_BOOST_APP_PATTERN = Pattern.compile("^" + PARAMETER_STORE_PREFIX + APP_BASE_PATH + "(.+)$");

    private final ParameterStoreFacade parameterStore;
    private DynamoDbClient ddb;

    public SettingsServiceDAL() {
        final long startTimeMillis = System.currentTimeMillis();
        if (Utils.isBlank(AWS_REGION)) {
            throw new IllegalStateException("Missing environment variable AWS_REGION");
        }
        if (Utils.isBlank(SAAS_BOOST_ENV)) {
            throw new IllegalStateException("Missing environment variable SAAS_BOOST_ENV");
        }
        SsmClient ssm = Utils.sdkClient(SsmClient.builder(), SsmClient.SERVICE_NAME);
        // Warm up SSM for cold start hack
        ssm.getParametersByPath(request -> request.path("/" + SAAS_BOOST_PREFIX + "/JUNK"));
        parameterStore = new ParameterStoreFacade(ssm);

        if (Utils.isNotBlank(OPTIONS_TABLE)) {
            this.ddb = Utils.sdkClient(DynamoDbClient.builder(), DynamoDbClient.SERVICE_NAME);
            // Cold start performance hack -- take the TLS hit for the client in the constructor
            this.ddb.describeTable(request -> request.tableName(OPTIONS_TABLE));
        }
        LOGGER.info("Constructor init: {}", System.currentTimeMillis() - startTimeMillis);
    }

    public List<Setting> getAllSettings() {
        return getAllParametersUnder(PARAMETER_STORE_PREFIX, false)
                .stream()
                .map(SettingsServiceDAL::fromParameterStore)
                .collect(Collectors.toList());
    }

    public List<Setting> getAppConfigSettings() {
        return getAllParametersUnder(PARAMETER_STORE_PREFIX + APP_BASE_PATH, true)
                .stream()
                .map(SettingsServiceDAL::fromAppParameterStore)
                .collect(Collectors.toList());
    }

    public List<Parameter> getAllParametersUnder(String parameterStorePathPrefix, boolean recursive) {
        final long startTimeMillis = System.currentTimeMillis();
        boolean decrypt = false;
        List<Parameter> parameters = parameterStore.getParametersByPath(parameterStorePathPrefix, recursive, decrypt);
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsServiceDAL::getAllSettingsUnder {} Loaded {} parameters",
                parameterStorePathPrefix, parameters.size());
        LOGGER.info("SettingsServiceDAL::getAllSettingsUnder exec " + totalTimeMillis);
        return parameters;
    }

    public List<Setting> getNamedSettings(List<String> namedSettings) {
        LOGGER.info("getNamedSettings");
        long startTime = System.currentTimeMillis();
        List<String> parameterNames = namedSettings
                .stream()
                .map(settingName -> toParameterStore(Setting.builder().name(settingName).build()).name())
                .collect(Collectors.toList());
        List<Setting> settings = parameterStore.getParameters(parameterNames)
                .stream()
                .map(SettingsServiceDAL::fromParameterStore)
                .collect(Collectors.toList());
        long endTime = System.currentTimeMillis();
        LOGGER.info("getNamedSettings exec: {} ms", endTime - startTime);
        return settings;
    }

    public Setting getSetting(String settingName) {
        return getSetting(settingName, false);
    }

    public Setting getSetting(String settingName, boolean decrypt) {
        return fromParameterStore(parameterStore.getParameter(
                toParameterStore(Setting.builder().name(settingName).build()).name(), decrypt));
    }

    public Setting getSecret(String settingName) {
        return getSetting(settingName, true);
    }

    public String getParameterStoreReference(String settingName) {
        Setting setting = getSetting(settingName);
        return PARAMETER_STORE_PREFIX + setting.getName() + ":" + setting.getVersion();
    }

    public Setting updateSetting(Setting setting) {
        Setting updated = fromParameterStore(parameterStore.putParameter(toParameterStore(setting)));
        if (updated.isSecure()) {
            // we don't want to return the unencrypted value, so replace this
            // setting with the encrypted representation we just placed in ParameterStore
            updated = getSetting(updated.getName());
        }
        return updated;
    }

    private void deleteSetting(Setting setting) {
        parameterStore.deleteParameter(toParameterStore(setting));
    }

    public List<Map<String, Object>> rdsOptions() {
        List<Map<String, Object>> orderableOptionsByRegion = new ArrayList<>();
        QueryResponse response = ddb.query(request -> request
                .tableName(OPTIONS_TABLE)
                .keyConditionExpression("#region = :region")
                .expressionAttributeNames(Stream
                        .of(new AbstractMap.SimpleEntry<>("#region", "region"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                )
                .expressionAttributeValues(Stream
                        .of(new AbstractMap.SimpleEntry<>(":region", AttributeValue.builder().s(AWS_REGION).build()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                )
        );
        response.items().forEach(item ->
                orderableOptionsByRegion.add(fromAttributeValueMap(item))
        );
        return orderableOptionsByRegion;
    }

    private static final Comparator<Map<String, Object>> INSTANCE_TYPE_COMPARATOR = ((instance1, instance2) -> {
        // T's before M's before R's
        int compare = 0;
        char type1 = ((String) instance1.get("instance")).charAt(0);
        char type2 = ((String) instance2.get("instance")).charAt(0);
        if (type1 != type2) {
            if ('T' == type1) {
                compare = -1;
            } else if ('T' == type2) {
                compare = 1;
            } else if ('M' == type1) {
                compare = -1;
            } else if ('M' == type2) {
                compare = 1;
            }
        }
        return compare;
    });

    private static final Comparator<Map<String, Object>> INSTANCE_GENERATION_COMPARATOR = ((instance1, instance2) -> {
        Integer gen1 = Integer.valueOf(((String) instance1.get("instance")).substring(1, 2));
        Integer gen2 = Integer.valueOf(((String) instance2.get("instance")).substring(1, 2));
        return gen1.compareTo(gen2);
    });

    private static final Comparator<Map<String, Object>> INSTANCE_SIZE_COMPARATOR = ((instance1, instance2) -> {
        String size1 = ((String) instance1.get("instance")).substring(3);
        String size2 = ((String) instance2.get("instance")).substring(3);
        List<String> sizes = Arrays.asList(
                "MICRO",
                "SMALL",
                "MEDIUM",
                "LARGE",
                "XL",
                "2XL",
                "4XL",
                "12XL",
                "24XL"
        );
        return Integer.compare(sizes.indexOf(size1), sizes.indexOf(size2));
    });

    public static final Comparator<Map<String, Object>> RDS_INSTANCE_COMPARATOR = INSTANCE_TYPE_COMPARATOR
            .thenComparing(INSTANCE_GENERATION_COMPARATOR)
            .thenComparing(INSTANCE_SIZE_COMPARATOR);

    public static Map<String, Object> fromAttributeValueMap(Map<String, AttributeValue> item) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("engine", item.get("engine").s());
        option.put("region", item.get("region").s());
        Map<String, AttributeValue> optionAttributes = item.get("options").m();
        option.put("name", optionAttributes.get("name").s());
        option.put("description", optionAttributes.get("description").s());

        List<Map<String, Object>> instances = new ArrayList<>();
        for (Map.Entry<String, AttributeValue> optionAttribute : optionAttributes.get("instances").m().entrySet()) {
            //build the instance entry
            Map<String, Object> instance = new LinkedHashMap<>(); // Used a linked map so we can sort stuff
            Map<String, AttributeValue> instanceAttributes = optionAttribute.getValue().m();
            instance.put("instance",optionAttribute.getKey());
            instance.put("class",instanceAttributes.get("class").s());
            instance.put("description",instanceAttributes.get("description").s());

            List<Map<String, String>> versions = new ArrayList<>();
            List<AttributeValue> versionAttributes = instanceAttributes.get("versions").l();
            for (AttributeValue versionAttribute : versionAttributes) {
                versions.add(
                        versionAttribute.m().entrySet().stream()
                                .collect(Collectors.toMap(
                                        entry -> entry.getKey(),
                                        entry -> entry.getValue().s()
                                ))
                );
            }

            instance.put("versions", versions);
            instances.add(instance);
        }
        Collections.sort(instances, RDS_INSTANCE_COMPARATOR);
        option.put("instances", instances);

        return option;
    }

    public AppConfig setAppConfig(AppConfig appConfig) {
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsServiceDAL::setAppConfig");
        // updateSettingsAndServices sends PUTs to ParameterStore
        List<Setting> updatedAppConfigSettings = updateSettingsAndSecrets(appConfigToSettings(appConfig));
        appConfig = appConfigFromSettings(updatedAppConfigSettings);
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsServiceDAL::setAppConfig exec " + totalTimeMillis);
        return appConfig;
    }

    public ServiceConfig setServiceConfig(ServiceConfig serviceConfig) {
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsServiceDAL::setServiceConfig");
        List<Setting> updatedServiceConfigSettings = updateSettingsAndSecrets(serviceConfigToSettings(serviceConfig));
        serviceConfig = appConfigFromSettings(updatedServiceConfigSettings).getServices().get(serviceConfig.getName());
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsServiceDAL::setServiceConfig exec " + totalTimeMillis);
        return serviceConfig;
    }

    private List<Setting> updateSettingsAndSecrets(List<Setting> settingsToUpdate) {
        List<Setting> updatedSettings = new LinkedList<>();
        for (Setting setting : settingsToUpdate) {
            LOGGER.info("Updating setting {} to {}", setting.getName(), setting.getValue());
            if (setting.isSecure()) {
                Setting existing = getSetting(setting.getName());
                if (existing != null) {
                    LOGGER.info("Existing Secret {} {}", setting.getName(), existing.getValue());
                } else {
                    LOGGER.info("Secret {} doesn't exist yet", setting.getName());
                }
                // If we were passed the encrypted string for a secret (from the UI),
                // don't overwrite the secret with that gibberish...
                if (existing != null && existing.getValue().equals(setting.getValue())) {
                    // Nothing has changed, don't overwrite the value in Parameter Store
                    LOGGER.info("Skipping update of secret because encrypted values are the same");
                    updatedSettings.add(existing);
                    continue;
                }
            }
            LOGGER.info("Calling put parameter {}", setting.getName());
            updatedSettings.add(updateSetting(setting));
        }
        return updatedSettings;
    }

    public AppConfig getAppConfig() {
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsServiceDAL::getAppConfig");
        AppConfig appConfig = appConfigFromSettings(getAppConfigSettings());
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsServiceDAL::getAppConfig exec " + totalTimeMillis);
        return appConfig;
    }

    private AppConfig toAppConfig(Map<String, String> appSettings) {
        AppConfig.Builder appConfigBuilder = AppConfig.builder()
                .name(appSettings.get(APP_BASE_PATH + "APP_NAME"))
                .domainName(appSettings.get(APP_BASE_PATH + "DOMAIN_NAME"))
                .hostedZone(appSettings.get(APP_BASE_PATH + "HOSTED_ZONE"))
                .sslCertificate(appSettings.get(APP_BASE_PATH + "SSL_CERT_ARN"));

        // TODO we shouldn't assume Settings passed to this function are encrypted or decrypted
        // but right now we are assuming they're encrypted, because they always are
        BillingProvider billingProvider = null;
        Setting billingApiKey = getSetting(APP_BASE_PATH + "BILLING_API_KEY", true);
        if (billingApiKey != null && Utils.isNotBlank(billingApiKey.getValue())) {
            billingProvider = BillingProvider.builder()
                    .apiKey(appSettings.get(APP_BASE_PATH + "BILLING_API_KEY"))
                    .build();
        }
        appConfigBuilder.billing(billingProvider);

        for (Map.Entry<String, String> appSetting : appSettings.entrySet()) {
            // every key that contains a "/" is necessarily nested under app
            // e.g. /app/service_001/DB_MASTER_PASSWORD
            //      /app/service_001/SERVICE_JSON
            if (appSetting.getKey().contains("/") && appSetting.getKey().endsWith("SERVICE_JSON")) {
                ServiceConfig existingServiceConfig = Utils.fromJson(appSetting.getValue(), ServiceConfig.class);
                ServiceConfig.Builder editedServiceConfig = ServiceConfig.builder(existingServiceConfig);
                Map<String, ServiceTierConfig> newTiers = new HashMap<>();
                for (Map.Entry<String, ServiceTierConfig> nameAndTier : existingServiceConfig.getTiers().entrySet()) {
                    String name = nameAndTier.getKey();
                    ServiceTierConfig tier = nameAndTier.getValue();
                    ServiceTierConfig.Builder editedTier = ServiceTierConfig.builder(tier);
                    if (tier.hasDatabase()) {
                        // if this tier has a database, override the password with the encrypted version
                        Database.Builder editedDatabase = Database.builder(tier.getDatabase());
                        Setting dbMasterPasswordSetting = getSetting(APP_BASE_PATH + existingServiceConfig.getName() + "/" + name + "/DB_MASTER_PASSWORD", false);
                        if (dbMasterPasswordSetting != null) {
                            editedDatabase.password(dbMasterPasswordSetting.getValue());
                        }
                        editedTier.database(editedDatabase.build());
                    }
                    newTiers.put(name, editedTier.build());
                }
                editedServiceConfig.tiers(newTiers);
                appConfigBuilder.serviceConfig(editedServiceConfig.build());
            }
        }

        return appConfigBuilder.build();
    }

    public void deleteAppConfig() {
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsServiceDAL::deleteAppConfig");
        // NOTE: Could also implement this like deleteTenantSettings by combining SettingsService::REQUIRED_PARAMS
        // and SettingsService::READ_WRITE_PARAMS and building the Parameter Store path by hand to avoid the call(s)
        // to getParameters before the call to deleteParameters
        AppConfig appConfig = getAppConfig();
        for (String serviceName : appConfig.getServices().keySet()) {
            deleteServiceConfig(appConfig, serviceName);
        }
        List<String> parametersToDelete = appConfigToSettings(appConfig).stream()
                .map(s -> toParameterStore(s).name())
                .collect(Collectors.toList());
        parameterStore.deleteParameters(parametersToDelete);
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsServiceDAL::deleteAppConfig exec " + totalTimeMillis);
    }

    public void deleteServiceConfig(AppConfig appConfig, String serviceName) {
        parameterStore.deleteParameters(serviceConfigToSettings(appConfig.getServices().get(serviceName))
                .stream()
                .map(s -> toParameterStore(s).name())
                .collect(Collectors.toList()));
    }

    public static Setting fromParameterStore(Parameter parameter) {
        Setting setting = null;
        if (parameter != null) {
            String parameterStoreName = parameter.name();
            if (Utils.isEmpty(parameterStoreName)) {
                throw new RuntimeException("Can't get Setting name for blank Parameter Store name [" + parameter.toString() + "]");
            }
            String settingName = null;
            Matcher regex = SAAS_BOOST_PARAMETER_PATTERN.matcher(parameterStoreName);
            if (regex.matches()) {
                settingName = regex.group(1);
            }
            if (settingName == null) {
                throw new RuntimeException("Parameter Store Parameter " + parameter.name() + " does not match SaaS Boost pattern");
            }

            setting = Setting.builder()
                    .name(settingName) // name now might be <serviceName>/SETTING
                    .value(!"N/A".equals(parameter.value()) ? parameter.value() : "")
                    .readOnly(!SettingsService.READ_WRITE_PARAMS.contains(settingName))
                    .secure(ParameterType.SECURE_STRING == parameter.type())
                    .version(parameter.version())
                    .build();
        }
        return setting;
    }

    public static Parameter toParameterStore(Setting setting) {
        if (setting == null || !Setting.isValidSettingName(setting.getName())) {
            throw new RuntimeException("Can't create Parameter Store parameter with invalid Setting name");
        }
        String parameterName = PARAMETER_STORE_PREFIX + setting.getName();
        String parameterValue = (Utils.isEmpty(setting.getValue())) ? "N/A" : setting.getValue();
        Parameter parameter = Parameter.builder()
                .type(setting.isSecure() ? ParameterType.SECURE_STRING : ParameterType.STRING)
                .name(parameterName)
                .value(parameterValue)
                .build();
        return parameter;
    }

    public static Setting fromAppParameterStore(Parameter parameter) {
        Setting setting = null;
        if (parameter != null) {
            String parameterStoreName = parameter.name();
            if (Utils.isEmpty(parameterStoreName)) {
                throw new RuntimeException("Can't get Setting name for blank Parameter Store name");
            }
            String settingName = null;
            Matcher regex = SAAS_BOOST_APP_PATTERN.matcher(parameterStoreName);
            if (regex.matches()) {
                settingName = regex.group(1);
            }
            if (settingName == null) {
                throw new RuntimeException("Parameter Store Parameter " + parameterStoreName + " does not match SaaS Boost app pattern " + SAAS_BOOST_APP_PATTERN);
            }
            setting = Setting.builder()
                    .name(APP_BASE_PATH + settingName)
                    .value(!"N/A".equals(parameter.value()) ? parameter.value() : "")
                    .readOnly(false)
                    .secure(ParameterType.SECURE_STRING == parameter.type())
                    .version(parameter.version())
                    .build();
        }
        return setting;
    }

    public List<Setting> appConfigToSettings(AppConfig appConfig) {
        List<Setting> settings = new ArrayList<>();

        settings.add(Setting.builder()
                .name(APP_BASE_PATH + "APP_NAME")
                .value(appConfig.getName())
                .readOnly(false)
                .build());
        settings.add(Setting.builder()
                .name(APP_BASE_PATH + "DOMAIN_NAME")
                .value(appConfig.getDomainName())
                .readOnly(false)
                .build());
        settings.add(Setting.builder()
                .name(APP_BASE_PATH + "HOSTED_ZONE")
                .value(appConfig.getHostedZone())
                .readOnly(false)
                .build());
        settings.add(Setting.builder()
                .name(APP_BASE_PATH + "SSL_CERT_ARN")
                .value(appConfig.getSslCertificate())
                .readOnly(false)
                .build());

        for (ServiceConfig serviceConfig : appConfig.getServices().values()) {
            settings.addAll(serviceConfigToSettings(serviceConfig));
        }

        String billingApiKeySettingValue = null;
        if (appConfig.getBilling() != null) {
            billingApiKeySettingValue = appConfig.getBilling().getApiKey();
        }
        settings.add(Setting.builder()
                .name(APP_BASE_PATH + "BILLING_API_KEY")
                .value(billingApiKeySettingValue)
                .readOnly(false)
                .secure(true)
                .build());

        return settings;
    }

    public List<Setting> serviceConfigToSettings(ServiceConfig serviceConfig) {
        List<Setting> settings = new ArrayList<>();
        // we're keeping the DB_MASTER_PASSWORD separate so we have an accessible form *somewhere*
        // but that means we need to create the DB_MASTER_PASSWORD for each Service

        // editedServiceConfig so that we can replace the password in all databases in tiers to have empty passwords
        // that way we aren't storing actual passwords.
        ServiceConfig.Builder editedServiceConfig = ServiceConfig.builder(serviceConfig);
        Map<String, ServiceTierConfig> editedTiers = new HashMap<>();
        for (Map.Entry<String, ServiceTierConfig> nameAndTierConfig : serviceConfig.getTiers().entrySet()) {
            String dbPasswordSettingValue = null;
            if (nameAndTierConfig.getValue().hasDatabase()) {
                dbPasswordSettingValue = nameAndTierConfig.getValue().getDatabase().getPassword();

                // tiers are /saas-boost/env/app/serviceName/tierName/
                // but we're setting db password at service level
                Setting dbPasswordSetting = Setting.builder()
                        .name(APP_BASE_PATH + serviceConfig.getName() + "/" + nameAndTierConfig.getKey() + "/DB_MASTER_PASSWORD")
                        .value(dbPasswordSettingValue)
                        .secure(true).readOnly(false).build();
                settings.add(dbPasswordSetting);

                // place the passwordParam so appConfig holders can find the password if they need it
                // and override password
                // passwordParam should be an arn of the form
                // arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter/saas-boost/${Environment}/DB_MASTER_PASSWORD
                ServiceTierConfig.Builder editedTierConfig = ServiceTierConfig.builder(nameAndTierConfig.getValue());
                editedTierConfig.database(
                    Database.builder(nameAndTierConfig.getValue().getDatabase())
                        .password("**encrypted**")
                        .passwordParam(toParameterStore(dbPasswordSetting).name())
                        .build());
                editedTiers.put(nameAndTierConfig.getKey(), editedTierConfig.build());
            } else {
                editedTiers.put(nameAndTierConfig.getKey(), nameAndTierConfig.getValue());
            }
        }

        editedServiceConfig.tiers(editedTiers);
        // if we don't remove the password from the database object in serviceConfig we'll end up storing it
        // we can't @Ignore password because we're expecting to send it back
        // the UI could have two defaults: '' means no database already configured, just the value of param otherwise
        // we have this logic in the settings service to check if it's the same as the encrypted value though
        // and we do that with the billing api key. it makes more sense to do the same with the database.
        // which means we should return the encrypted password
        // which means we can't ignore the password
        // which means when we store this password in this serviceConfig we need to obfuscate.
        // but that's what I'm saying.. it doesn't matter what I store, as long as it isn't the plaintext.
        settings.add(Setting.builder()
                .name(APP_BASE_PATH + serviceConfig.getName() + "/SERVICE_JSON")
                .value(Utils.toJson(editedServiceConfig.build()))
                .readOnly(false).build());

        return settings;
    }

    public AppConfig appConfigFromSettings(List<Setting> appConfigSettings) {
        // Get the secret value for the optional billing provider or you'll always
        // be testing for empty against the encrypted hash of the "N/A" sentinel string
        return toAppConfig(
                appConfigSettings.stream()
                        .collect(Collectors.toMap(Setting::getName, Setting::getValue)));
    }
}
