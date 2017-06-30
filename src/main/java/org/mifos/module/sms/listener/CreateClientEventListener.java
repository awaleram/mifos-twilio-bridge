/**
 * Copyright 2014 Markus Geiss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mifos.module.sms.listener;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.module.sms.domain.Client;
import org.mifos.module.sms.domain.CreateClientResponse;
import org.mifos.module.sms.domain.EventSource;
import org.mifos.module.sms.domain.EventSourceDetail;
import org.mifos.module.sms.domain.SMSBridgeConfig;
import org.mifos.module.sms.event.CreateClientEvent;
import org.mifos.module.sms.exception.SMSGatewayException;
import org.mifos.module.sms.parser.JsonParser;
import org.mifos.module.sms.provider.RestAdapterProvider;
import org.mifos.module.sms.provider.SMSGateway;
import org.mifos.module.sms.provider.SMSGatewayProvider;
import org.mifos.module.sms.repository.EventSourceDetailsRepository;
import org.mifos.module.sms.repository.EventSourceRepository;
import org.mifos.module.sms.repository.SMSBridgeConfigRepository;
import org.mifos.module.sms.service.MifosClientService;
import org.mifos.module.sms.util.AuthorizationTokenBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;




import retrofit.RestAdapter;
import retrofit.RetrofitError;

import java.io.StringWriter;
import java.util.Date;

@Component
public class CreateClientEventListener implements ApplicationListener<CreateClientEvent> {

    @Value("${message.template.createclient}")
    private String messageTemplate;

    private static final Logger logger = LoggerFactory.getLogger(CreateClientEventListener.class);

    private final SMSBridgeConfigRepository smsBridgeConfigRepository;
    private final EventSourceRepository eventSourceRepository;
    private final RestAdapterProvider restAdapterProvider;
    private final SMSGatewayProvider smsGatewayProvider;
    private final JsonParser jsonParser;
    private final EventSourceDetailsRepository eventSourcingDetailsRepository;
    
    @Autowired
    public CreateClientEventListener(final SMSBridgeConfigRepository smsBridgeConfigRepository,
                                     final EventSourceRepository eventSourceRepository,
                                     final RestAdapterProvider restAdapterProvider,
                                     final SMSGatewayProvider smsGatewayProvider,
                                     final JsonParser jsonParser,
                                     final EventSourceDetailsRepository eventSourcingDetailsRepository) {
        super();
        this.smsBridgeConfigRepository = smsBridgeConfigRepository;
        this.eventSourceRepository = eventSourceRepository;
        this.restAdapterProvider = restAdapterProvider;
        this.smsGatewayProvider = smsGatewayProvider;
        this.jsonParser = jsonParser;
        this.eventSourcingDetailsRepository = eventSourcingDetailsRepository;
    }

    @Transactional
    @Override
    public void onApplicationEvent(CreateClientEvent createClientEvent) {
        logger.info("Create client event received, trying to process ...");

        final EventSource eventSource = this.eventSourceRepository.findOne(createClientEvent.getEventId());

        final SMSBridgeConfig smsBridgeConfig = this.smsBridgeConfigRepository.findByTenantId(eventSource.getTenantId());
        if (smsBridgeConfig == null) {
            logger.error("Unknown tenant " + eventSource.getTenantId() + "!");
            return;
        }

        final CreateClientResponse createClientResponse = this.jsonParser.parse(eventSource.getPayload(), CreateClientResponse.class);

        final long clientId = createClientResponse.getClientId();

        final RestAdapter restAdapter = this.restAdapterProvider.get(smsBridgeConfig);

        //following changes for eventSourcingDetails table
        EventSourceDetail eventSourceDetails = new EventSourceDetail();
        eventSourceDetails.setEventId(eventSource.getId());;
        eventSourceDetails.setTenantId(eventSource.getTenantId());
        eventSourceDetails.setEntityId(Long.toString(clientId));
        eventSourceDetails.setProcessed(Boolean.FALSE);
       
        try {
            final MifosClientService clientService = restAdapter.create(MifosClientService.class);
            final Client client = clientService.findClient(AuthorizationTokenBuilder.token(smsBridgeConfig.getMifosToken()).build(), smsBridgeConfig.getTenantId(), clientId);
            final String mobileNo = client.getMobileNo();
          
            //following changes for eventSourcingDetails table
            StringBuilder entityDescription = new StringBuilder();
            entityDescription.append("ClientId :" + clientId);
            entityDescription.append("ClientName :" + client.getDisplayName());
            eventSourceDetails.setEntitydescription(entityDescription.toString());
            
            eventSourceDetails.setEntity("CREATE_CLIENT");
            eventSourceDetails.setEntityName(client.getDisplayName());
            eventSourceDetails.setEntityMobileNo(client.getMobileNo());
            eventSourceDetails.setAction("CREATE");
            eventSourceDetails.setPayload(eventSource.getPayload());
            
            if (mobileNo != null) {
                logger.info("Mobile number found, sending message!");

                
                final VelocityContext velocityContext = new VelocityContext();
                velocityContext.put("name", client.getDisplayName());
                velocityContext.put("branch", client.getOfficeName());
                velocityContext.put("externalid", client.getExternalId());
                final StringWriter stringWriter = new StringWriter();
                Velocity.evaluate(velocityContext, stringWriter, "CreateClientMessage", this.messageTemplate);
                final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                JSONArray response= smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                JSONObject result = response.getJSONObject(0);
                if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                {
                    eventSource.setProcessed(Boolean.TRUE);
                    eventSourceDetails.setProcessed(Boolean.TRUE);
                }
                	logger.info("Message is: "+ stringWriter);
            }
            logger.info("Create client event processed!");
        } catch (RetrofitError rer) {
            if (rer.getResponse().getStatus() == 404) {
                logger.info("Client not found!");
            }
            eventSource.setProcessed(Boolean.FALSE);
            eventSource.setErrorMessage(rer.getMessage());
            eventSourceDetails.setProcessed(Boolean.FALSE);
            eventSource.setErrorMessage(rer.getMessage());
        } catch (SMSGatewayException sgex) {
            eventSource.setProcessed(Boolean.FALSE);
            eventSource.setErrorMessage(sgex.getMessage());
            eventSourceDetails.setProcessed(Boolean.FALSE);
            eventSource.setErrorMessage(sgex.getMessage());
        } catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        eventSource.setLastModifiedOn(new Date());
        eventSourceDetails.setLastModifiedOn(new Date());
        eventSourceDetails.setCreatedOn(new Date());
        this.eventSourceRepository.save(eventSource);
        this.eventSourcingDetailsRepository.save(eventSourceDetails);
    }
}
