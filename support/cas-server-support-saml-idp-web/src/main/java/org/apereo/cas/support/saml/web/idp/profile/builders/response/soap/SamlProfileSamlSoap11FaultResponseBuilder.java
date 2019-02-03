package org.apereo.cas.support.saml.web.idp.profile.builders.response.soap;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.support.saml.OpenSamlConfigBean;
import org.apereo.cas.support.saml.SamlException;
import org.apereo.cas.support.saml.SamlIdPConstants;
import org.apereo.cas.support.saml.SamlIdPUtils;
import org.apereo.cas.support.saml.services.SamlRegisteredService;
import org.apereo.cas.support.saml.services.idp.metadata.SamlRegisteredServiceServiceProviderMetadataFacade;
import org.apereo.cas.support.saml.web.idp.profile.builders.SamlProfileObjectBuilder;
import org.apereo.cas.support.saml.web.idp.profile.builders.enc.SamlIdPObjectEncrypter;
import org.apereo.cas.support.saml.web.idp.profile.builders.enc.SamlIdPObjectSigner;

import lombok.val;
import org.apache.velocity.app.VelocityEngine;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.RequestAbstractType;
import org.opensaml.soap.soap11.Body;
import org.opensaml.soap.soap11.Envelope;
import org.opensaml.soap.soap11.Fault;
import org.opensaml.soap.soap11.FaultActor;
import org.opensaml.soap.soap11.FaultCode;
import org.opensaml.soap.soap11.FaultString;
import org.opensaml.soap.soap11.Header;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The {@link SamlProfileSamlSoap11FaultResponseBuilder} is responsible for
 * building the final SAML assertion for the relying party.
 *
 * @author Misagh Moayyed
 * @since 4.2
 */
public class SamlProfileSamlSoap11FaultResponseBuilder extends SamlProfileSamlSoap11ResponseBuilder {
    private static final long serialVersionUID = -1875903354216171261L;

    public SamlProfileSamlSoap11FaultResponseBuilder(final OpenSamlConfigBean openSamlConfigBean,
                                                     final SamlIdPObjectSigner samlObjectSigner,
                                                     final VelocityEngine velocityEngineFactory,
                                                     final SamlProfileObjectBuilder<Assertion> samlProfileSamlAssertionBuilder,
                                                     final SamlProfileObjectBuilder<? extends SAMLObject> saml2ResponseBuilder,
                                                     final SamlIdPObjectEncrypter samlObjectEncrypter,
                                                     final CasConfigurationProperties casProperties) {
        super(openSamlConfigBean, samlObjectSigner, velocityEngineFactory,
            samlProfileSamlAssertionBuilder, saml2ResponseBuilder, samlObjectEncrypter, casProperties);
    }


    @Override
    public Envelope build(final RequestAbstractType authnRequest,
                          final HttpServletRequest request,
                          final HttpServletResponse response,
                          final Object casAssertion,
                          final SamlRegisteredService service,
                          final SamlRegisteredServiceServiceProviderMetadataFacade adaptor,
                          final String binding,
                          final MessageContext messageContext) throws SamlException {

        val body = newSoapObject(Body.class);
        val fault = newSoapObject(Fault.class);

        val faultCode = newSoapObject(FaultCode.class);
        faultCode.setValue(FaultCode.SERVER);
        fault.setCode(faultCode);

        val faultActor = newSoapObject(FaultActor.class);
        faultActor.setValue(SamlIdPUtils.getIssuerFromSamlObject(authnRequest));
        fault.setActor(faultActor);

        val faultString = newSoapObject(FaultString.class);
        faultString.setValue(request.getAttribute(SamlIdPConstants.REQUEST_ATTRIBUTE_ERROR).toString());
        fault.setMessage(faultString);

        body.getUnknownXMLObjects().add(fault);

        val envelope = newSoapObject(Envelope.class);
        val header = newSoapObject(Header.class);
        envelope.setHeader(header);
        envelope.setBody(body);
        encodeFinalResponse(request, response, service, adaptor, envelope,
            binding, authnRequest, casAssertion, messageContext);
        return envelope;
    }
}
