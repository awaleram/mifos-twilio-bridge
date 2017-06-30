package org.mifos.module.sms.listener;

import java.io.StringWriter;
import java.util.Date;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.module.sms.domain.Client;
import org.mifos.module.sms.domain.EventSource;
import org.mifos.module.sms.domain.EventSourceDetail;
import org.mifos.module.sms.domain.LoanDisbursementResponse;
import org.mifos.module.sms.domain.SMSBridgeConfig;
import org.mifos.module.sms.event.LoanDisbursementEvent;
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

@Component
public class LoanDisbursementEventListener implements ApplicationListener<LoanDisbursementEvent> {

	@Value("${message.template.loandisbursed}")
	private String messageTemplate;
	
	private static final Logger logger = LoggerFactory.getLogger(LoanDisbursementEvent.class);
	
	private final SMSBridgeConfigRepository smsBridgeConfigRepository;
	private final EventSourceRepository eventSourceRepository;
	private final RestAdapterProvider restAdapterProvider;
	private final SMSGatewayProvider smsGatewayProvider;
	private final JsonParser jsonParser;
	private final EventSourceDetailsRepository eventSourceDetailsRepository;
	
	@Autowired
	public LoanDisbursementEventListener(SMSBridgeConfigRepository smsBridgeConfigRepository,
											EventSourceRepository eventSourceRepository,
											RestAdapterProvider restAdapterProvider,
											SMSGatewayProvider smsGatewayProvider, 
											JsonParser jsonParser,
											EventSourceDetailsRepository eventSourceDetailsRepository) {
		super();
		this.smsBridgeConfigRepository = smsBridgeConfigRepository;
		this.eventSourceRepository = eventSourceRepository;
		this.restAdapterProvider = restAdapterProvider;
		this.smsGatewayProvider = smsGatewayProvider;
		this.jsonParser = jsonParser;
		this.eventSourceDetailsRepository = eventSourceDetailsRepository;
	}

	@Transactional
	@Override
	public void onApplicationEvent(LoanDisbursementEvent loanDisbursementEvent) {
		
		logger.info("Loan disbursement event received, trying to process ...");
		
		final EventSource eventSource = this.eventSourceRepository.findOne(loanDisbursementEvent.getEventId());
		
		final SMSBridgeConfig smsBridgeConfig = this.smsBridgeConfigRepository.findByTenantId(eventSource.getTenantId());
		if (smsBridgeConfig == null) {
			logger.error("Unknown tenant " + eventSource.getTenantId() + "!");
			return;
		}
		
		LoanDisbursementResponse loanDisbursementResponse = this.jsonParser.parse(eventSource.getPayload(), LoanDisbursementResponse.class);
		
		final long clientId = loanDisbursementResponse.getClientId();
		
		final RestAdapter restAdapter = this.restAdapterProvider.get(smsBridgeConfig);
		
		//Following Changes for EventSourcing Details table
		final EventSourceDetail eventSourceDetail = new EventSourceDetail();
		eventSourceDetail.setEventId(eventSource.getId());
		eventSourceDetail.setTenantId(eventSource.getTenantId());
		eventSourceDetail.setEntityId(Long.toString(clientId));
		eventSourceDetail.setProcessed(Boolean.FALSE);
		
		
		
		try{
			final String authToken = AuthorizationTokenBuilder.token(smsBridgeConfig.getMifosToken()).build();
			
			final MifosClientService clientService = restAdapter.create(MifosClientService.class);
			final Client client = clientService.findClient(authToken, smsBridgeConfig.getTenantId(), clientId);
			
			final String mobileNo = client.getMobileNo();
			if (mobileNo != null) {
				logger.info("Mobile number found, sending message!");
				
				final VelocityContext velocityContext = new VelocityContext();
				velocityContext.put("firstName", client.getFirstname());
				velocityContext.put("clientName", client.getDisplayName());
				velocityContext.put("branch", client.getOfficeName());
				
				//Following Changes for EventSourcing Details table
				 StringBuilder entityDescription = new StringBuilder();
				 entityDescription.append("ClientId : " + clientId);
				 entityDescription.append("Client name : " + client.getDisplayName());
				 eventSourceDetail.setEntitydescription(entityDescription.toString());
				 eventSourceDetail.setAction("LOANACCOUNT");
				 eventSourceDetail.setEntityName(client.getDisplayName());
				 eventSourceDetail.setEntityMobileNo(client.getMobileNo());
				 eventSourceDetail.setAction("DISBURSMENT");
				 eventSourceDetail.setPayload(eventSource.getPayload());
				 eventSourceDetail.setEntity("LOAN_DISBURSMENT");
				final StringWriter stringWriter = new StringWriter();
				Velocity.evaluate(velocityContext, stringWriter, "LoanDisbursementMessage", this.messageTemplate);
				
				final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
				JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
				JSONObject result = response.getJSONObject(0);
                if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                {
                	eventSource.setProcessed(Boolean.TRUE);
                	eventSourceDetail.setProcessed(Boolean.TRUE);
                }
				logger.info("Message is: "+ stringWriter);
			}
			logger.info("Loan disbursement event processed!");
		} catch(RetrofitError ret) {
			if (ret.getResponse().getStatus() == 404) {
				logger.info("Loan not found!");
			}
			eventSource.setProcessed(Boolean.FALSE);
			eventSource.setErrorMessage(ret.getMessage());
			eventSourceDetail.setProcessed(Boolean.FALSE);
			eventSourceDetail.setErrorMessage(ret.getMessage());
		} catch (SMSGatewayException sgex) {
			eventSource.setProcessed(Boolean.FALSE);
			eventSource.setErrorMessage(sgex.getMessage());
			eventSourceDetail.setProcessed(Boolean.FALSE);
			eventSourceDetail.setErrorMessage(sgex.getMessage());
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		eventSource.setLastModifiedOn(new Date());
		eventSourceDetail.setLastModifiedOn(new Date());
		eventSourceDetail.setCreatedOn(new Date());
		this.eventSourceRepository.save(eventSource);
		this.eventSourceDetailsRepository.save(eventSourceDetail);
	}
}
