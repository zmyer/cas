package org.apereo.cas.config.support.authentication;

import org.apereo.cas.CipherExecutor;
import org.apereo.cas.adaptors.yubikey.DefaultYubiKeyAccountValidator;
import org.apereo.cas.adaptors.yubikey.YubiKeyAccountRegistry;
import org.apereo.cas.adaptors.yubikey.YubiKeyAccountValidator;
import org.apereo.cas.adaptors.yubikey.YubiKeyAuthenticationHandler;
import org.apereo.cas.adaptors.yubikey.YubiKeyCredential;
import org.apereo.cas.adaptors.yubikey.YubiKeyMultifactorAuthenticationProvider;
import org.apereo.cas.adaptors.yubikey.registry.JsonYubiKeyAccountRegistry;
import org.apereo.cas.adaptors.yubikey.registry.OpenYubiKeyAccountRegistry;
import org.apereo.cas.adaptors.yubikey.registry.WhitelistYubiKeyAccountRegistry;
import org.apereo.cas.adaptors.yubikey.registry.YubiKeyAccountRegistryEndpoint;
import org.apereo.cas.adaptors.yubikey.web.flow.YubiKeyAccountCheckRegistrationAction;
import org.apereo.cas.adaptors.yubikey.web.flow.YubiKeyAccountSaveRegistrationAction;
import org.apereo.cas.authentication.AuthenticationEventExecutionPlanConfigurer;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.AuthenticationMetaDataPopulator;
import org.apereo.cas.authentication.MultifactorAuthenticationProvider;
import org.apereo.cas.authentication.bypass.MultifactorAuthenticationProviderBypass;
import org.apereo.cas.authentication.handler.ByCredentialTypeAuthenticationHandlerResolver;
import org.apereo.cas.authentication.metadata.AuthenticationContextAttributeMetaDataPopulator;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.PrincipalFactoryUtils;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.http.HttpClient;

import com.yubico.client.v2.YubicoClient;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnEnabledEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.webflow.execution.Action;

/**
 * This is {@link YubiKeyAuthenticationEventExecutionPlanConfiguration}.
 *
 * @author Misagh Moayyed
 * @author Dmitriy Kopylenko
 * @since 5.1.0
 */
@Configuration("yubikeyAuthenticationEventExecutionPlanConfiguration")
@EnableConfigurationProperties(CasConfigurationProperties.class)
@Slf4j
public class YubiKeyAuthenticationEventExecutionPlanConfiguration {
    @Autowired
    @Qualifier("yubikeyAccountCipherExecutor")
    private ObjectProvider<CipherExecutor> yubikeyAccountCipherExecutor;

    @Autowired
    private CasConfigurationProperties casProperties;

    @Autowired
    @Qualifier("servicesManager")
    private ObjectProvider<ServicesManager> servicesManager;

    @Autowired
    @Qualifier("noRedirectHttpClient")
    private ObjectProvider<HttpClient> httpClient;

    @Autowired
    @Qualifier("yubikeyBypassEvaluator")
    private ObjectProvider<MultifactorAuthenticationProviderBypass> yubikeyBypassEvaluator;

    @Bean
    @RefreshScope
    @ConditionalOnMissingBean(name = "yubikeyAuthenticationMetaDataPopulator")
    public AuthenticationMetaDataPopulator yubikeyAuthenticationMetaDataPopulator() {
        val authenticationContextAttribute = casProperties.getAuthn().getMfa().getAuthenticationContextAttribute();
        return new AuthenticationContextAttributeMetaDataPopulator(
            authenticationContextAttribute,
            yubikeyAuthenticationHandler(),
            yubikeyMultifactorAuthenticationProvider().getId()
        );
    }

    @ConditionalOnMissingBean(name = "yubikeyPrincipalFactory")
    @Bean
    @RefreshScope
    public PrincipalFactory yubikeyPrincipalFactory() {
        return PrincipalFactoryUtils.newPrincipalFactory();
    }

    @RefreshScope
    @Bean
    @ConditionalOnMissingBean(name = "yubicoClient")
    public YubicoClient yubicoClient() {
        val yubi = this.casProperties.getAuthn().getMfa().getYubikey();

        if (StringUtils.isBlank(yubi.getSecretKey())) {
            throw new IllegalArgumentException("Yubikey secret key cannot be blank");
        }
        if (yubi.getClientId() <= 0) {
            throw new IllegalArgumentException("Yubikey client id is undefined");
        }

        val client = YubicoClient.getClient(yubi.getClientId(), yubi.getSecretKey());
        if (!yubi.getApiUrls().isEmpty()) {
            val urls = yubi.getApiUrls().toArray(ArrayUtils.EMPTY_STRING_ARRAY);
            client.setWsapiUrls(urls);
        }
        return client;
    }

    @Bean
    @RefreshScope
    @ConditionalOnMissingBean(name = "yubikeyAuthenticationHandler")
    public AuthenticationHandler yubikeyAuthenticationHandler() {
        val yubi = this.casProperties.getAuthn().getMfa().getYubikey();
        return new YubiKeyAuthenticationHandler(yubi.getName(),
            servicesManager.getIfAvailable(), yubikeyPrincipalFactory(),
            yubicoClient(), yubiKeyAccountRegistry(),
            yubi.getOrder());
    }

    @Bean
    @RefreshScope
    @ConditionalOnMissingBean(name = "yubiKeyAccountRegistrationAction")
    public Action yubiKeyAccountRegistrationAction() {
        return new YubiKeyAccountCheckRegistrationAction(yubiKeyAccountRegistry());
    }

    @Bean
    @RefreshScope
    @ConditionalOnMissingBean(name = "yubiKeySaveAccountRegistrationAction")
    public Action yubiKeySaveAccountRegistrationAction() {
        return new YubiKeyAccountSaveRegistrationAction(yubiKeyAccountRegistry());
    }

    @Bean
    @RefreshScope
    @ConditionalOnMissingBean(name = "yubiKeyAccountValidator")
    public YubiKeyAccountValidator yubiKeyAccountValidator() {
        return new DefaultYubiKeyAccountValidator(yubicoClient());
    }

    @Bean
    @RefreshScope
    @ConditionalOnMissingBean(name = "yubiKeyAccountRegistry")
    public YubiKeyAccountRegistry yubiKeyAccountRegistry() {
        val yubi = casProperties.getAuthn().getMfa().getYubikey();

        val cipher = yubikeyAccountCipherExecutor.getIfAvailable();
        if (yubi.getJsonFile() != null) {
            LOGGER.debug("Using JSON resource [{}] as the YubiKey account registry", yubi.getJsonFile());
            val registry = new JsonYubiKeyAccountRegistry(yubi.getJsonFile(), yubiKeyAccountValidator());
            registry.setCipherExecutor(cipher);
            return registry;
        }
        if (yubi.getAllowedDevices() != null) {
            LOGGER.debug("Using statically-defined devices for [{}] as the YubiKey account registry",
                yubi.getAllowedDevices().keySet());
            val registry = new WhitelistYubiKeyAccountRegistry(yubi.getAllowedDevices(), yubiKeyAccountValidator());
            registry.setCipherExecutor(cipher);
            return registry;
        }

        LOGGER.warn("All credentials are considered eligible for YubiKey authentication. "
                + "Consider providing an account registry implementation via [{}]",
            YubiKeyAccountRegistry.class.getName());
        val registry = new OpenYubiKeyAccountRegistry(new DefaultYubiKeyAccountValidator(yubicoClient()));
        registry.setCipherExecutor(cipher);
        return registry;
    }

    @Bean
    @ConditionalOnEnabledEndpoint
    public YubiKeyAccountRegistryEndpoint yubiKeyAccountRegistryEndpoint() {
        return new YubiKeyAccountRegistryEndpoint(casProperties, yubiKeyAccountRegistry());
    }

    @Bean
    @RefreshScope
    public MultifactorAuthenticationProvider yubikeyMultifactorAuthenticationProvider() {
        val yubi = casProperties.getAuthn().getMfa().getYubikey();
        val p = new YubiKeyMultifactorAuthenticationProvider(yubicoClient(), httpClient.getIfAvailable());
        p.setBypassEvaluator(yubikeyBypassEvaluator.getIfAvailable());
        p.setFailureMode(yubi.getFailureMode());
        p.setOrder(yubi.getRank());
        p.setId(yubi.getId());
        return p;
    }

    @ConditionalOnMissingBean(name = "yubikeyAuthenticationEventExecutionPlanConfigurer")
    @Bean
    public AuthenticationEventExecutionPlanConfigurer yubikeyAuthenticationEventExecutionPlanConfigurer() {
        return plan -> {
            val yubi = casProperties.getAuthn().getMfa().getYubikey();
            if (yubi.getClientId() > 0 && StringUtils.isNotBlank(yubi.getSecretKey())) {
                plan.registerAuthenticationHandler(yubikeyAuthenticationHandler());
                plan.registerAuthenticationMetadataPopulator(yubikeyAuthenticationMetaDataPopulator());
                plan.registerAuthenticationHandlerResolver(new ByCredentialTypeAuthenticationHandlerResolver(YubiKeyCredential.class));
            }
        };
    }
}
