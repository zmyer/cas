package org.apereo.cas.token;

import org.apereo.cas.CipherExecutor;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.RegisteredServiceAccessStrategyUtils;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.token.cipher.RegisteredServiceTokenTicketCipherExecutor;
import org.apereo.cas.util.EncodingUtils;
import org.apereo.cas.web.BaseCasActuatorEndpoint;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;

/**
 * This is {@link JWTTokenCipherSigningPublicKeyEndpoint}.
 *
 * @author Misagh Moayyed
 * @since 6.1.0
 */
@Slf4j
@Endpoint(id = "jwtTicketSigningPublicKey", enableByDefault = false)
public class JWTTokenCipherSigningPublicKeyEndpoint extends BaseCasActuatorEndpoint {
    private final CipherExecutor tokenCipherExecutor;
    private final ServicesManager servicesManager;

    public JWTTokenCipherSigningPublicKeyEndpoint(final CasConfigurationProperties casProperties,
                                                  final CipherExecutor tokenCipherExecutor,
                                                  final ServicesManager servicesManager) {
        super(casProperties);
        this.tokenCipherExecutor = tokenCipherExecutor;
        this.servicesManager = servicesManager;
    }

    /**
     * Fetch public key.
     *
     * @param service the service
     * @return the string
     * @throws Exception the exception
     */
    @ReadOperation(produces = MediaType.TEXT_PLAIN_VALUE)
    public String fetchPublicKey(@Nullable final String service) throws Exception {
        val factory = KeyFactory.getInstance("RSA");
        var signingKey = tokenCipherExecutor.getSigningKey();

        if (StringUtils.isNotBlank(service)) {
            val registeredService = this.servicesManager.findServiceBy(service);
            RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(registeredService);
            val serviceCipher = new RegisteredServiceTokenTicketCipherExecutor();
            if (serviceCipher.supports(registeredService)) {
                val cipher = serviceCipher.getTokenTicketCipherExecutorForService(registeredService);
                if (cipher.isEnabled()) {
                    signingKey = cipher.getSigningKey();
                }
            }
        }

        if (signingKey instanceof RSAPrivateCrtKey) {
            val rsaSigningKey = (RSAPrivateCrtKey) signingKey;
            val publicKey = factory.generatePublic(new RSAPublicKeySpec(rsaSigningKey.getModulus(), rsaSigningKey.getPublicExponent()));
            return EncodingUtils.encodeBase64(publicKey.getEncoded());
        }
        return null;
    }
}
