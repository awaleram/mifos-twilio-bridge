package org.mifos.module.sms.listener;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import org.apache.commons.lang.BooleanUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.module.sms.domain.EventSource;
import org.mifos.module.sms.domain.EventSourceDetail;
import org.mifos.module.sms.domain.IncomingSms;
import org.mifos.module.sms.domain.IncomingSmsClientId;
import org.mifos.module.sms.domain.IncomingSmsLoanAndSavingAccoutData;
import org.mifos.module.sms.domain.LoanAccountSummaryData;
import org.mifos.module.sms.domain.MiniStatementDetails;
import org.mifos.module.sms.domain.SMSBridgeConfig;
import org.mifos.module.sms.domain.SavingsAccountSummaryData;
import org.mifos.module.sms.event.IncomingSmsEvent;
import org.mifos.module.sms.exception.SMSGatewayException;
import org.mifos.module.sms.parser.JsonParser;
import org.mifos.module.sms.provider.RestAdapterProvider;
import org.mifos.module.sms.provider.SMSGateway;
import org.mifos.module.sms.provider.SMSGatewayProvider;
import org.mifos.module.sms.repository.EventSourceDetailRepository;
import org.mifos.module.sms.repository.EventSourceRepository;
import org.mifos.module.sms.repository.SMSBridgeConfigRepository;
import org.mifos.module.sms.service.MifosIncomingSmsService;
import org.mifos.module.sms.util.AuthorizationTokenBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import retrofit.RestAdapter;
import retrofit.RetrofitError;

@Component
public class IncomingSmsSendListener implements ApplicationListener<IncomingSmsEvent> {

    @Value("${message.template.unregisteredmobilenumbers}")
    private String unregisteredmobilenumbers;
    
    @Value("${message.template.loanAndSavingsBalance}")
    private String loanAndSavingBalance;
    
    @Value("${message.template.invalidText}")
    private String invalidText;
    
    @Value("${message.template.miniStatement}")
    private String miniStatement;
    
    @Value("${message.template.balance}")
    private String balance;
    
    @Value("${message.template.loanAndSavingsTransaction}")
    private String loanAndSavingsTransaction;
    
    @Value("${dataTable}")
    private String dataTable;

    private static final Logger logger = LoggerFactory.getLogger(IncomingSmsSendListener.class);

    private final SMSBridgeConfigRepository smsBridgeConfigRepository;
    private final EventSourceRepository eventSourceRepository;
    private final RestAdapterProvider restAdapterProvider;
    private final SMSGatewayProvider smsGatewayProvider;
    private final JsonParser jsonParser;
    private final EventSourceDetailRepository eventSourceDetailRepository;
    private final JdbcTemplate jdbcTemplate;
    @Autowired
    public IncomingSmsSendListener(final SMSBridgeConfigRepository smsBridgeConfigRepository,
            final EventSourceRepository eventSourceRepository, final RestAdapterProvider restAdapterProvider,
            final SMSGatewayProvider smsGatewayProvider, final JsonParser jsonParser,
            final EventSourceDetailRepository eventSourceDetailRepository,
            final JdbcTemplate jdbcTemplate) {
        super();
        this.smsBridgeConfigRepository = smsBridgeConfigRepository;
        this.eventSourceRepository = eventSourceRepository;
        this.restAdapterProvider = restAdapterProvider;
        this.smsGatewayProvider = smsGatewayProvider;
        this.jsonParser = jsonParser;
        this.eventSourceDetailRepository=eventSourceDetailRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    @Override
    public void onApplicationEvent(IncomingSmsEvent incomingSmsEvent) {
        final EventSource eventSource = this.eventSourceRepository.findOne(incomingSmsEvent.getEventId());

        final SMSBridgeConfig smsBridgeConfig = this.smsBridgeConfigRepository.findByTenantId(eventSource.getTenantId());
        if (smsBridgeConfig == null) {
            logger.error("Unknown tenant " + eventSource.getTenantId() + "!");
            return;
        }
        final IncomingSmsClientId incomingSmsClientId = this.jsonParser.parse(eventSource.getPayload(), IncomingSmsClientId.class);

        String MobileNo = incomingSmsClientId.getMobileNo();
       
        int size = MobileNo.length();
        String incomingMobileNo = MobileNo.substring(1,size);
        //String Mobilenumber=MobileNo.substring(3,size);
       // MobileNo=MobileNo.substring(4,size);    
        int count=0;
        final RestAdapter restAdapter = this.restAdapterProvider.get(smsBridgeConfig);
        try {
        	Date now =new Date();
            String message = "";
            final StringWriter stringWriter = new StringWriter();
            final VelocityContext velocityContext = new VelocityContext();
            final MifosIncomingSmsService IncomingSmsService = restAdapter.create(MifosIncomingSmsService.class);
            final ArrayList<IncomingSms> incomingSms = (ArrayList<IncomingSms>) IncomingSmsService.findClientId(AuthorizationTokenBuilder
                    .token(smsBridgeConfig.getMifosToken()).build(), smsBridgeConfig.getTenantId(), incomingMobileNo, "clients");
            EventSourceDetail eventSourceDetail = new EventSourceDetail();
            eventSourceDetail.setAction("Send");
            eventSourceDetail.setEntity("Notifications");
            eventSourceDetail.setEventId(incomingSmsEvent.getEventId());
            eventSourceDetail.setPayload(eventSource.getPayload()); 
            eventSourceDetail.setTenantId(smsBridgeConfig.getTenantId());
            eventSourceDetail.setEntityName("IncomingSms");
            eventSourceDetail.setProcessed(Boolean.FALSE);
            eventSourceDetail.setCreatedOn(now);
            eventSourceDetail.setLastModifiedOn(now);           
            if (incomingSms.size() > 0) {
                //for(int i=0;i<incomingSms.size();i++){                    
                 for(IncomingSms incomingSmsID : incomingSms){  
               // IncomingSms incomingSmsID = incomingSms.get(i);
                String mobileNo ="";
                Long officeId = incomingSmsID.getParentId();
                if(incomingSmsID.getEntityMobileNo()!=null){
                    mobileNo=incomingSmsID.getEntityMobileNo();
                }
                                 
                    /**
                     * method for getting loans And Savings AccountBalance
                     */
                    if (incomingMobileNo.equalsIgnoreCase(mobileNo)||mobileNo.equalsIgnoreCase(0+MobileNo)) {
                    	//ArrayList<SmsEnabledBranch> smsEnabledOffice = IncomingSmsService.findSmsEnabledOffice(AuthorizationTokenBuilder.token(smsBridgeConfig.getMifosToken()).build(),
                    		//	smsBridgeConfig.getTenantId(), dataTable, incomingSmsID.getParentId());
                    	
                    	     if(officeId != null && incomingMobileNo!=null ){
                    	    	boolean checkForSmsEnabled = isSmsEnabled(officeId);
                    	    	 
                    	    	 if(checkForSmsEnabled == true){                   	     
                    	
                        IncomingSmsLoanAndSavingAccoutData incomingSmsLoanAndSavingAccoutData = IncomingSmsService.findDetails(
                                AuthorizationTokenBuilder.token(smsBridgeConfig.getMifosToken()).build(),
                                smsBridgeConfig.getTenantId(), incomingSmsID.getEntityId());                        
                        ArrayList<LoanAccountSummaryData> loanAccounts = (ArrayList<LoanAccountSummaryData>) incomingSmsLoanAndSavingAccoutData
                                .getLoanAccounts(); 
                        ArrayList<SavingsAccountSummaryData> sharesBalance = IncomingSmsService.findsharesBalance(
                                AuthorizationTokenBuilder.token(smsBridgeConfig.getMifosToken()).build(),
                                smsBridgeConfig.getTenantId(), incomingSmsID.getEntityId());
                        //log
                        //eventSourceDetail.setEntitydescription("clientId:" + incomingSmsID.getEntityId() + " " + "clientName:" + incomingSmsID.getEntityName());
                                
                        //checking 
                           logger.info("Inital "+incomingSmsID.getEntityName());
                           logger.info("Inital "+incomingSmsID.getParentName());
                           String requestType = incomingSmsClientId.getMessage().trim().toLowerCase();
                           logger.info("Requested Type : " + incomingSmsClientId.getMessage());
                        if (requestType.contains("balance")) {
                                  if ( loanAccounts!=null) {
                                      String[]str=incomingSmsID.getEntityName().split(" ");
                                      velocityContext.put("name", str[0]);                                      
                                      velocityContext.put("branch", incomingSmsID.getParentName());
                                      logger.info("loan Account customer Name"+str[0]);
                                      logger.info("loan Account office Address",incomingSmsID.getParentName());
                                  for (LoanAccountSummaryData loanaccountsId : loanAccounts) {
                                      if(loanaccountsId.getLoanBalance()!=null){
                                        message = message + " Loan Bal(ACCNO:" + loanaccountsId.getId() + ")- "
                                                + loanaccountsId.getLoanBalance().setScale(2) + "";
                                       }
                            }
                            }   
                                  
                                if (sharesBalance!=null&&sharesBalance.size()>0) {
                                	String[]str1=incomingSmsID.getEntityName().split(" ");
                                    velocityContext.put("name", str1[0]);                                      
                                    velocityContext.put("branch", incomingSmsID.getParentName());                                  
                                for (SavingsAccountSummaryData sharesAccountDetails : sharesBalance) {                                    
                                    if(sharesAccountDetails.getAccountBalance()!=null){
                                    message = message + " Saving Bal(ACCNO:" + sharesAccountDetails.getAccountNo() + ")- "
                                            + sharesAccountDetails.getAccountBalance().setScale(2) + "";
                                    }
                                    else{
                                        message = message + " Saving Bal(ACCNO:" + sharesAccountDetails.getAccountNo() + ")- "
                                                + "0.00" + " ";                               
                                    }
                                }
                            }
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            if(!message.isEmpty()){ 
                                velocityContext.put("balancemessage", message); 
                                Velocity.evaluate(velocityContext, stringWriter, "balance", this.balance);                    
                             
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig, incomingMobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                            }
                            eventSourceDetail.setEntityMobileNo(mobileNo);
                            eventSourceDetail.setMessage(stringWriter.toString());
                            eventSourceDetail.setEntitydescription("clientId:" + incomingSmsID.getEntityId() + " " + "clientName:" + incomingSmsID.getEntityName());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info(stringWriter.toString());
                            }
                            else{
                                String[]str=incomingSmsID.getEntityName().split(" ");
                                velocityContext.put("name", str[0]);
                                velocityContext.put("branch", incomingSmsID.getParentName());                     
                                Velocity.evaluate(velocityContext, stringWriter, "loanAndSavingBalance", this.loanAndSavingBalance);                             
                                JSONArray response=smsGateway.sendMessage(smsBridgeConfig, incomingMobileNo, stringWriter.toString()); 
                                JSONObject result = response.getJSONObject(0);
                                if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                                {
                                    eventSourceDetail.setProcessed(Boolean.TRUE);
                                }
                                eventSourceDetail.setEntityMobileNo(mobileNo);
                                eventSourceDetail.setMessage(stringWriter.toString());
                                eventSourceDetail.setEntitydescription("clientId:" + incomingSmsID.getEntityId() + " " + "clientName:" + incomingSmsID.getEntityName());
                                this.eventSourceDetailRepository.save(eventSourceDetail);                               
                                logger.info(stringWriter.toString());
                            }
                        }
                        /**
                         * method for getting miniStatement by passing clientId
                         * as parameter
                         */
                        else if (requestType.contains("mini")) {
                            ArrayList<MiniStatementDetails> miniStatementDetail = IncomingSmsService.findMiniStatementDetails(
                                    AuthorizationTokenBuilder.token(smsBridgeConfig.getMifosToken()).build(),
                                    smsBridgeConfig.getTenantId(), incomingSmsID.getEntityId());
                            for (MiniStatementDetails miniStatementDetails : miniStatementDetail) {
                                String recno = miniStatementDetails.getReceiptNumber();
                                if (recno.contains("dummy")) {
                                    recno = "RPT: ";
                                }
                                message = message + "Rpt-"+recno+":"+" "+ miniStatementDetails.getTransactionDate() + " "
                                        + miniStatementDetails.getAmount().setScale(2) + "  ";
                                String[]str2=incomingSmsID.getEntityName().split(" ");
                                velocityContext.put("name", str2[0]);                                      
                                velocityContext.put("branch", incomingSmsID.getParentName());                                
                                                          
                            }
                            
                              final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            if(!message.isEmpty()){
                                velocityContext.put("meassage", message); 
                                Velocity.evaluate(velocityContext, stringWriter, "miniStatement", this.miniStatement);                         
                            JSONArray response= smsGateway.sendMessage(smsBridgeConfig, incomingMobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                            }
                            eventSourceDetail.setMessage(stringWriter.toString());
                            eventSourceDetail.setEntityMobileNo(mobileNo);
                            eventSourceDetail.setEntitydescription("clientId:" + incomingSmsID.getEntityId() + " " + "clientName:" + incomingSmsID.getEntityName());
                            this.eventSourceDetailRepository.save(eventSourceDetail);                           
                            logger.info(stringWriter.toString());
                            }
                            else{
                                 String[]str=incomingSmsID.getEntityName().split(" ");
                                velocityContext.put("name", str[0]);
                                velocityContext.put("branch", incomingSmsID.getParentName());                         
                                if(loanAccounts!=null||sharesBalance.size()>0){
                                Velocity.evaluate(velocityContext, stringWriter, "loanAndSavingsTransaction", this.loanAndSavingsTransaction);                             
                                JSONArray response= smsGateway.sendMessage(smsBridgeConfig, incomingMobileNo, stringWriter.toString());
                                JSONObject result = response.getJSONObject(0);
                                if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                                {
                                	eventSource.setProcessed(Boolean.TRUE);
                                    eventSourceDetail.setProcessed(Boolean.TRUE);

                                }
                                eventSourceDetail.setMessage(stringWriter.toString());
                                eventSourceDetail.setEntityMobileNo(mobileNo);
                                eventSourceDetail.setEntitydescription("clientId:" + incomingSmsID.getEntityId() + " " + "clientName:" + incomingSmsID.getEntityName());
                                this.eventSourceDetailRepository.save(eventSourceDetail);                               
                                logger.info(stringWriter.toString());}
                                else{
                                    Velocity.evaluate(velocityContext, stringWriter, "loanAndSavingBalance", this.loanAndSavingBalance);                            
                                    JSONArray response=smsGateway.sendMessage(smsBridgeConfig, incomingMobileNo, stringWriter.toString());
                                    JSONObject result = response.getJSONObject(0);
                                    if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                                    {
                                        eventSourceDetail.setProcessed(Boolean.TRUE);
                                    }
                                    eventSourceDetail.setMessage(stringWriter.toString());
                                    eventSourceDetail.setEntityMobileNo(mobileNo);
                                    eventSourceDetail.setEntitydescription("clientId:" + incomingSmsID.getEntityId() + " " + "clientName:" + incomingSmsID.getEntityName());
                                    this.eventSourceDetailRepository.save(eventSourceDetail);                                   
                                    logger.info(stringWriter.toString());
                                }
                            }
                            
                        } else {
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            String[]str=incomingSmsID.getEntityName().split(" ");
                            velocityContext.put("name", str[0]);
                            velocityContext.put("branch", incomingSmsID.getParentName());                     
                            Velocity.evaluate(velocityContext, stringWriter, "invalidText", this.invalidText);                            
                            logger.info(stringWriter.toString());
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig, incomingMobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                                eventSourceDetail.setProcessed(Boolean.TRUE);

                            }
                            eventSourceDetail.setMessage(stringWriter.toString());
                            eventSourceDetail.setEntityMobileNo(mobileNo);
                            eventSourceDetail.setEntitydescription("clientId:" + incomingSmsID.getEntityId() + " " + "clientName:" + incomingSmsID.getEntityName());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                           
                        }
                    }
                  }
                 }   	     
                    else{
                        count++;
                        if(count==incomingSms.size()){
                            count=0;
                            Velocity.evaluate(null, stringWriter, "unregisteredmobilenumbers", this.unregisteredmobilenumbers);
                            logger.info(stringWriter.toString());
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig,incomingMobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                            }
                            eventSourceDetail.setMessage(stringWriter.toString());
                            eventSourceDetail.setEntityMobileNo(mobileNo);
                            eventSourceDetail.setEntitydescription("clientId:" + " " + " " + "clientName:" + " ");
                            this.eventSourceDetailRepository.save(eventSourceDetail);                           
                        }
                    }
                }
                     
            }
                    else {
                        Velocity.evaluate(null, stringWriter, "unregisteredmobilenumbers", this.unregisteredmobilenumbers);
                        logger.info(stringWriter.toString());
                        final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                        JSONArray response=smsGateway.sendMessage(smsBridgeConfig, incomingMobileNo, stringWriter.toString());
                        JSONObject result = response.getJSONObject(0);
                        if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                        {
                            eventSourceDetail.setProcessed(Boolean.TRUE);

                        }
                        eventSourceDetail.setMessage(stringWriter.toString());
                        eventSourceDetail.setEntityMobileNo( incomingMobileNo);
                        eventSourceDetail.setEntitydescription("clientId:" + " " + " " + "clientName:" + " ");
                        this.eventSourceDetailRepository.save(eventSourceDetail);                           
            
                    }
                
            
        } catch (RetrofitError rer) {
            if (rer.getResponse().getStatus() == 404) {
                logger.info("Client not found!");
                rer.printStackTrace();
            }
        } catch (SMSGatewayException sgex) {
            eventSource.setProcessed(Boolean.FALSE);
            eventSource.setErrorMessage(sgex.getMessage());
            sgex.printStackTrace();
        } catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

    }
    
    public boolean isSmsEnabled(Long officeId){
    	String sql = " SELECT sms_enabled FROM OfficeDetails WHERE office_id = " + officeId;
    	int queryResult = this.jdbcTemplate.queryForInt(sql);
    	boolean isSmsEnabled = BooleanUtils.toBoolean(queryResult);
    	return isSmsEnabled;
    }

}
