package org.mifos.module.sms.listener;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.module.sms.domain.Client;
import org.mifos.module.sms.domain.EventSource;
import org.mifos.module.sms.domain.EventSourceDetail;
import org.mifos.module.sms.domain.Loan;
import org.mifos.module.sms.domain.LoanApprovalToGuarantorsResponse;
import org.mifos.module.sms.domain.SMSBridgeConfig;
import org.mifos.module.sms.event.LoanApprovalToGuarantorsEvent;
import org.mifos.module.sms.exception.SMSGatewayException;
import org.mifos.module.sms.parser.JsonParser;
import org.mifos.module.sms.provider.RestAdapterProvider;
import org.mifos.module.sms.provider.SMSGateway;
import org.mifos.module.sms.provider.SMSGatewayProvider;
import org.mifos.module.sms.repository.EventSourceDetailsRepository;
import org.mifos.module.sms.repository.EventSourceRepository;
import org.mifos.module.sms.repository.SMSBridgeConfigRepository;
import org.mifos.module.sms.service.MifosClientService;
import org.mifos.module.sms.service.MifosLoanApprovalService;
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
public class LoanApprovalToGuarantorsEventListener implements
		ApplicationListener<LoanApprovalToGuarantorsEvent> {

	@Value("${message.template.loanApproval}")
	private String messageTemplate;

	private static final Logger logger = LoggerFactory
			.getLogger(LoanApprovalToGuarantorsEventListener.class);

	private final SMSBridgeConfigRepository smsBridgeConfigRepository;
	private final EventSourceRepository eventSourceRepository;
	private final EventSourceDetailsRepository eventSourceDetailsRepository;
	private final RestAdapterProvider restAdapterProvider;
	private final SMSGatewayProvider smsGatewayProvider;
	private final JsonParser jsonParser;

	@Autowired
	public LoanApprovalToGuarantorsEventListener(
			SMSBridgeConfigRepository smsBridgeConfigRepository,
			EventSourceRepository eventSourceRepository,
			RestAdapterProvider restAdapterProvider,
			SMSGatewayProvider smsGatewayProvider, JsonParser jsonParser,
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
	public void onApplicationEvent(
			LoanApprovalToGuarantorsEvent loanApprovalToGuarantorsEvent) {

		logger.info("Loan approval to guarantors event received, trying to process ...");

		final EventSource eventSource = this.eventSourceRepository
				.findOne(loanApprovalToGuarantorsEvent.getEventId());

		final SMSBridgeConfig smsBridgeConfig = this.smsBridgeConfigRepository
				.findByTenantId(eventSource.getTenantId());
		if (smsBridgeConfig == null) {
			logger.error("Unknown tenant " + eventSource.getTenantId() + "!");
			return;
		}

		LoanApprovalToGuarantorsResponse loanApprovalToGuarantorsResponse = this.jsonParser
				.parse(eventSource.getPayload(),
						LoanApprovalToGuarantorsResponse.class);

		final long clientId = loanApprovalToGuarantorsResponse.getClientId();

		final long loanId = loanApprovalToGuarantorsResponse.getLoanId();

		final RestAdapter restAdapter = this.restAdapterProvider
				.get(smsBridgeConfig);

		final String authToken = AuthorizationTokenBuilder.token(
				smsBridgeConfig.getMifosToken()).build();

		final MifosLoanApprovalService loanService = restAdapter
				.create(MifosLoanApprovalService.class);
		final Loan loan = loanService.findLoan(authToken,
				smsBridgeConfig.getTenantId(), loanId);

		final MifosClientService clientService = restAdapter
				.create(MifosClientService.class);
		final Client client = clientService.findClient(authToken,
				smsBridgeConfig.getTenantId(), clientId);

		if (loan.getGuarantors() != null) {
			loan.guarantorsData(loan.getGuarantors());
		}else {
			logger.info("You need to have at leat one guarantor to send message...");
		}

		List<Long> guarantorIdList = new ArrayList<Long>();
		guarantorIdList = loan.getGuarantorsId();

		for (int i = 0; i < guarantorIdList.size(); i++) {

			if (guarantorIdList.get(i) != null) {
				EventSourceDetail eventSourceDetails = new EventSourceDetail();
				eventSourceDetails.setEventId(eventSource.getId());
				eventSourceDetails.setEntity(eventSource.getEntity());
				eventSourceDetails.setAction(eventSource.getAction());
				eventSourceDetails.setPayload(eventSource.getPayload());
				eventSourceDetails.setTenantId(eventSource.getTenantId());
				eventSourceDetails.setEntity("Loan_Approve");
				eventSourceDetails.setProcessed(Boolean.FALSE);
				
				try {

					Long guarantorId = guarantorIdList.get(i);
					final Client guarantor = clientService.findClient(
							authToken, smsBridgeConfig.getTenantId(),
							guarantorId);
					eventSourceDetails.setEntityName(guarantor.getDisplayName());
					eventSourceDetails.setEntityMobileNo(guarantor.getMobileNo());
					eventSourceDetails.setAction("Loan_Approve");
					
					
					Double amount = null;
					if (loan.getAmount() != null) {
						amount = loan.getAmount().get(i);
					}

					final String mobileNo = guarantor.getMobileNo();
					if (mobileNo != null) {
						logger.info("Mobile number found, sending message!");

						final VelocityContext velocityContext = new VelocityContext();
						velocityContext.put("guarantorName", guarantor.getDisplayName());
						velocityContext.put("lonee", client.getDisplayName());
						velocityContext.put("amountCommited", amount);
						velocityContext.put("branch", client.getOfficeName());

						final StringWriter stringWriter = new StringWriter();
						Velocity.evaluate(velocityContext, stringWriter, "LoanApprovalToGuarantorsMessage", this.messageTemplate);

						final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
						JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
						JSONObject result = response.getJSONObject(0);
		                if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
		                {
		                	eventSource.setProcessed(Boolean.TRUE);
		                	eventSourceDetails.setProcessed(Boolean.TRUE);
		                }
						logger.info("Message is: "+ stringWriter);
					}

					eventSource.setProcessed(Boolean.TRUE);
					eventSourceDetails.setProcessed(Boolean.TRUE);
					logger.info("Loan approval to guarantors event processed!");
				} catch (RetrofitError ret) {
					if (ret.getResponse().getStatus() == 404) {
						logger.info("Loan not found!");
					}
					eventSource.setProcessed(Boolean.FALSE);
					eventSourceDetails.setProcessed(Boolean.FALSE);
					eventSource.setErrorMessage(ret.getMessage());
					eventSourceDetails.setErrorMessage(ret.getMessage());
				} catch (SMSGatewayException sgex) {
					eventSource.setProcessed(Boolean.FALSE);
					eventSourceDetails.setProcessed(Boolean.FALSE);
					eventSource.setErrorMessage(sgex.getMessage());
					eventSourceDetails.setErrorMessage(sgex.getMessage());
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				eventSource.setLastModifiedOn(new Date());
				eventSourceDetails.setLastModifiedOn(new Date());
				this.eventSourceRepository.save(eventSource);
				this.eventSourceDetailsRepository.save(eventSourceDetails);
				
			}
		}
	}
}
