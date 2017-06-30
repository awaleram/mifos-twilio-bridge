package org.mifos.module.sms.listener;

import java.io.StringWriter;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.module.sms.domain.BulkSms;
import org.mifos.module.sms.domain.BulkSmsListenerDomain;
import org.mifos.module.sms.domain.BulkSmsResponse;
import org.mifos.module.sms.domain.EventSource;
import org.mifos.module.sms.domain.EventSourceDetail;
import org.mifos.module.sms.domain.SMSBridgeConfig;
import org.mifos.module.sms.event.BulkSmsEvent;
import org.mifos.module.sms.exception.SMSGatewayException;
import org.mifos.module.sms.parser.JsonParser;
import org.mifos.module.sms.provider.RestAdapterProvider;
import org.mifos.module.sms.provider.SMSGateway;
import org.mifos.module.sms.provider.SMSGatewayProvider;
import org.mifos.module.sms.repository.EventSourceDetailRepository;
import org.mifos.module.sms.repository.EventSourceRepository;
import org.mifos.module.sms.repository.SMSBridgeConfigRepository;
import org.mifos.module.sms.service.MifosBulkSmsService;
import org.mifos.module.sms.util.AuthorizationTokenBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import retrofit.RestAdapter;
import retrofit.RetrofitError;

import com.google.gson.JsonObject;

@Component
public class BulkSmsSendListener implements ApplicationListener<BulkSmsEvent> {

    @Value("${message.template.loanFirstAndSecondOverdueRepaymentReminder}")
    private String messageTemplate;

    @Value("${message.template.loanrepaymentsmsreminder}")
    private String loanrepaymentsmsreminder;

    @Value("${message.template.loanThirdAndFourthOverdueRepaymentReminder}")
    private String messageForThirdAndFourthOverdue;

    @Value("${message.template.messageForDefaultWarningToClient}")
    private String messageForDefaultWarningToClient;

    @Value("${message.template.messageForDefaultWarningTogurantor}")
    private String messageForDefaultWarningTogurantor;

    @Value("${message.template.messageForDormancyWarningToclient}")
    private String messageForDormancyWarningToclient;

    private static final Logger logger = LoggerFactory.getLogger(BulkSmsSendListener.class);
    private final SMSBridgeConfigRepository smsBridgeConfigRepository;
    private final EventSourceRepository eventSourceRepository;
    private final RestAdapterProvider restAdapterProvider;
    private final SMSGatewayProvider smsGatewayProvider;
    private final EventSourceDetailRepository eventSourceDetailRepository;
    private final JsonParser jsonParser;

    @Autowired
    public BulkSmsSendListener(final SMSBridgeConfigRepository smsBridgeConfigRepository,
            final EventSourceRepository eventSourceRepository, final RestAdapterProvider restAdapterProvider,
            final SMSGatewayProvider smsGatewayProvider, final EventSourceDetailRepository eventSourceDetailRepository,
            final JsonParser jsonParser) {
        super();
        this.smsBridgeConfigRepository = smsBridgeConfigRepository;
        this.eventSourceRepository = eventSourceRepository;
        this.restAdapterProvider = restAdapterProvider;
        this.smsGatewayProvider = smsGatewayProvider;
        this.eventSourceDetailRepository = eventSourceDetailRepository;
        this.jsonParser = jsonParser;
    }

    @Override
    public void onApplicationEvent(BulkSmsEvent bulkSmsEvent) {
        logger.info(" Send  Sms to Loan Repayment reminder/overdue  for client  is in ` process ...");
        final EventSource eventSource = this.eventSourceRepository.findOne(bulkSmsEvent.getEventId());

        final SMSBridgeConfig smsBridgeConfig = this.smsBridgeConfigRepository.findByTenantId(eventSource.getTenantId());
        if (smsBridgeConfig == null) {
            logger.error("Unknown tenant " + eventSource.getTenantId() + "!");
            return;
        }
        final BulkSmsResponse bulkSmsResponse = this.jsonParser.parse(eventSource.getPayload(), BulkSmsResponse.class);
        final String reportName = bulkSmsResponse.getReportName();
        String date = bulkSmsResponse.getDate();
        final RestAdapter restAdapter = this.restAdapterProvider.get(smsBridgeConfig);
        final MifosBulkSmsService BulkSmsService = restAdapter.create(MifosBulkSmsService.class);
        final Integer officeId = 0;
        final BulkSms bulkSms = BulkSmsService.findLoanReminder(AuthorizationTokenBuilder.token(smsBridgeConfig.getMifosToken()).build(),
                smsBridgeConfig.getTenantId(), reportName, date, officeId);
        final VelocityContext velocityContext = new VelocityContext();
        final ArrayList<JsonObject> bulkSms_clientdata = bulkSms.getData();
        BulkSmsListener bulkSmsListener = new BulkSmsListener();
        String mobileNo = null;
        ArrayList<EventSourceDetail> detail = new ArrayList<EventSourceDetail>();
        BulkSmsListenerDomain bulkSmsListenerDomain = bulkSmsListener.ReportDataProcessor(reportName, bulkSms_clientdata);
        ArrayList<String> clientNameList = bulkSmsListenerDomain.getClientNameList();
        ArrayList<String> clientBranchList = bulkSmsListenerDomain.getClientBranchList();
        ArrayList<String> LoanDueDateList = bulkSmsListenerDomain.getLoanDueDateList();
        ArrayList<String> clientMobileNumberList = bulkSmsListenerDomain.getClientMobileNumberList();
        ArrayList<String> entityIdList = bulkSmsListenerDomain.getLoanIdList();
        ArrayList<String> clientIdList = bulkSmsListenerDomain.getClientIdList();
        ArrayList<String> productShortNameList = bulkSmsListenerDomain.getProductShortNameList();
        ArrayList<String> guarantorsNameList = bulkSmsListenerDomain.getGuarantorsNameList();
        ArrayList<String> guarantorsBranchList = bulkSmsListenerDomain.getGuarantorsBranchList();
        ArrayList<String> guarantorsMobileNumberList = bulkSmsListenerDomain.getGuarantorsMobileNumberList();
        ArrayList<String> loanOverdueDueAmountList = bulkSmsListenerDomain.getLoanOverdueDueAmountList();
        ArrayList<String> loanOverdueMonthList = bulkSmsListenerDomain.getLoanOverdueMonthList();
        ArrayList<String> guarantorscomittedShareList = bulkSmsListenerDomain.getGuarantorscomittedShareList();
        ArrayList<String> savingIdList = bulkSmsListenerDomain.getSavingIdList();
        int totalMessageSent = 0;
        int totalMessageUnsent = 0;

        for (int i = 0; i < clientMobileNumberList.size(); i++) {
            EventSourceDetail eventSourceDetail = new EventSourceDetail();
            try {
                velocityContext.put("name", clientNameList.get(i));
                if (reportName.equalsIgnoreCase("Loan Third Overdue Repayment Reminder")
                        || reportName.equalsIgnoreCase("Loan Fourth Overdue Repayment Reminder")
                        || reportName.equalsIgnoreCase("DefaultWarning -  guarantors")) {
                    velocityContext.put("gurantorname", guarantorsNameList.get(i));
                    velocityContext.put("branch", guarantorsBranchList.get(i));
                    mobileNo = guarantorsMobileNumberList.get(i);
                    eventSourceDetail.setEntityId(entityIdList.get(i));
                    eventSourceDetail.setEntityName("Loans");
                    detail = eventSourceDetailRepository.findByEntityIdandMobileNumberandProcessed(entityIdList.get(i),
                            guarantorsMobileNumberList.get(i), "Loans");
                } else {
                    mobileNo = clientMobileNumberList.get(i);
                    if (reportName.equalsIgnoreCase("DormancyWarning - Clients")) {
                        detail = eventSourceDetailRepository.findByEntityIdandMobileNumberandProcessed(savingIdList.get(i),
                                clientMobileNumberList.get(i), "savings");
                        eventSourceDetail.setEntityId(savingIdList.get(i));
                        eventSourceDetail.setEntityName("savings");
                    } else {
                        detail = eventSourceDetailRepository.findByEntityIdandMobileNumberandProcessed(entityIdList.get(i),
                                clientMobileNumberList.get(i), "Loans");
                        eventSourceDetail.setEntityId(entityIdList.get(i));
                        eventSourceDetail.setEntityName("Loans");
                    }
                }
                eventSourceDetail.setEntity_Mobile_No(mobileNo);
                eventSourceDetail.setEventId(eventSource.getId());
                eventSourceDetail.setTenantId(eventSource.getTenantId());
                eventSourceDetail.setAction(eventSource.getAction());
                eventSourceDetail.setEntity(eventSource.getEntity());
                eventSourceDetail.setPayload(eventSource.getPayload());
                eventSourceDetail.setReportName(reportName);
                eventSourceDetail.setProcessed(Boolean.FALSE);
                eventSourceDetail.setEntitydescription("clientId:" + clientIdList.get(i) + " " + "clientName:" + clientNameList.get(i)
                        + "productshort_Name:" + productShortNameList.get(i));
                Date now = new Date();
                eventSourceDetail.setCreatedOn(now);
                eventSourceDetail.setLastModifiedOn(now);
                if (mobileNo.equals("null") || mobileNo.equalsIgnoreCase("NA") || mobileNo.equalsIgnoreCase("  ") || mobileNo.length() <= 0) {
                    eventSourceDetail.setErrorMessage("Mobile number is not Valid");
                    this.eventSourceDetailRepository.save(eventSourceDetail);
                    if (reportName.equalsIgnoreCase("Loan First Overdue Repayment Reminder")) {
                        logger.info("Loan First Overdue Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                + productShortNameList.get(i) + "\n event not processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    } else if (reportName.equalsIgnoreCase("Loan Second Overdue Repayment Reminder")) {
                        logger.info("Loan Second Overdue Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                + productShortNameList.get(i) + "\n event not processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    } else if (reportName.equalsIgnoreCase("Loan Third Overdue Repayment Reminder")) {
                        logger.info("Loan Third Overdue Repayment Reminder Sms  to\n" + "clientName: " + guarantorsNameList.get(i) + " \n"
                                + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                + productShortNameList.get(i) + "\n event not processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    } else if (reportName.equalsIgnoreCase("Loan Fourth Overdue Repayment Reminder")) {
                        logger.info("Loan Fourth Overdue Repayment Reminder Sms  to\n" + "clientName: " + guarantorsNameList.get(i) + " \n"
                                + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                + productShortNameList.get(i) + "\n event not processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    } else if (reportName.equalsIgnoreCase("Loan Repayment Reminders")) {
                        logger.info("Loan Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i) + " \n" + "MobileNo: "
                                + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: " + productShortNameList.get(i)
                                + "\n event  is not  processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    } else if (reportName.equalsIgnoreCase("DefaultWarning - Clients")) {
                        logger.info("DefaultWarning Sms To Clients   to\n" + "clientName: " + clientNameList.get(i) + " \n" + "MobileNo: "
                                + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: " + productShortNameList.get(i)
                                + "\n event is not  processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    } else if (reportName.equalsIgnoreCase("DefaultWarning -  guarantors")) {
                        logger.info("DefaultWarning Sms To guarantors   to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                + productShortNameList.get(i) + "\n event is not  processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    } else if (reportName.equalsIgnoreCase("DormancyWarning - Clients")) {
                        logger.info("DormancyWarning Sms To clients    to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                + "MobileNo: " + mobileNo + "\n" + "savingsId: " + savingIdList.get(i) + "\n" + "productName: "
                                + productShortNameList.get(i) + "\n event is not  processed!");
                        totalMessageUnsent = totalMessageUnsent + 1;
                    }
                } else if (mobileNo != null) {
                    long diffInDays = 0;
                    String sendSmsReportName = null;
                    Boolean isSMSProcessed = Boolean.FALSE;
                    EventSourceDetail eventSourceDetail1 = new EventSourceDetail();
                    if (detail.size() != 0) {
                        int size = detail.size() - 1;
                        eventSourceDetail1 = detail.get(size);
                        sendSmsReportName = eventSourceDetail1.getReportName();
                        Date lastModifiedate = eventSourceDetail1.getLastModifiedOn();
                        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
                        Date crrentDate = df.parse(date);
                        long timeDiff = crrentDate.getTime() - lastModifiedate.getTime();
                        diffInDays = timeDiff / (24 * 60 * 60 * 1000);
                        if(reportName.equalsIgnoreCase(eventSourceDetail1.getReportName())){
                        	isSMSProcessed = eventSourceDetail1.getProcessed();
                        }
                        
                    }
                    if (reportName.equalsIgnoreCase("Loan First Overdue Repayment Reminder")) {
                        final StringWriter stringWriter = new StringWriter();
                        if (detail.size() != 0) {
                            if (isSMSProcessed ==Boolean.FALSE || diffInDays > 0) {
                                velocityContext.put("branch", clientBranchList.get(i));
                                velocityContext.put("month", loanOverdueMonthList.get(i));
                                velocityContext.put("overdueamount", loanOverdueDueAmountList.get(i));
                                Velocity.evaluate(velocityContext, stringWriter, "loanFirstAndSecondOverdueRepaymentReminder",
                                        this.messageTemplate);
                                final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                                JSONArray response= smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                                JSONObject result = response.getJSONObject(0);
                                if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                                {
                                	eventSource.setProcessed(Boolean.TRUE);
                                    eventSourceDetail.setProcessed(Boolean.TRUE);
                                 }
                                 eventSourceDetail.setMessage(stringWriter.toString());
                                this.eventSourceDetailRepository.save(eventSourceDetail);
                                logger.info("Loan First Overdue Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i)
                                        + " \n" + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n"
                                        + "productName: " + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString()
                                        + "event processed!");
                                totalMessageSent = totalMessageSent + 1;
                            }
                        } else if (detail.size() == 0) {
                            velocityContext.put("branch", clientBranchList.get(i));
                            velocityContext.put("month", loanOverdueMonthList.get(i));
                            velocityContext.put("overdueamount", loanOverdueDueAmountList.get(i));
                            Velocity.evaluate(velocityContext, stringWriter, "loanFirstAndSecondOverdueRepaymentReminder",
                                    this.messageTemplate);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                            	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                             }                             
                            eventSourceDetail.setMessage(stringWriter.toString());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("Loan First Overdue Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                    + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "event processed!");
                            totalMessageSent = totalMessageSent + 1;
                        }

                    }
                    if (reportName.equalsIgnoreCase("Loan Repayment Reminders")) {
                        if (isSMSProcessed ==Boolean.FALSE) {
                                final StringWriter stringWriter = new StringWriter();
                                velocityContext.put("branch", clientBranchList.get(i));
                                velocityContext.put("duedate", LoanDueDateList.get(i));
                                Velocity.evaluate(velocityContext, stringWriter, "loanrepaymentsmsreminder", this.loanrepaymentsmsreminder);
                                final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                                JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                                JSONObject result = response.getJSONObject(0);
                                if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                                {
                                	eventSource.setProcessed(Boolean.TRUE);
                                    eventSourceDetail.setProcessed(Boolean.TRUE);
                                 }                                 
                                eventSourceDetail.setReportName(reportName);
                                eventSourceDetail.setMessage(stringWriter.toString());
                                this.eventSourceDetailRepository.save(eventSourceDetail);
                                logger.info("Loan Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                        + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                        + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "\nevent processed!");
                                totalMessageSent = totalMessageSent + 1;

                        } else if (detail.size() == 0) {
                            final StringWriter stringWriter = new StringWriter();
                            velocityContext.put("branch", clientBranchList.get(i));
                            velocityContext.put("duedate", LoanDueDateList.get(i));
                            Velocity.evaluate(velocityContext, stringWriter, "loanrepaymentsmsreminder", this.loanrepaymentsmsreminder);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                            	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                             }                             
                            eventSourceDetail.setMessage(stringWriter.toString());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("Loan Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                    + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "\nevent processed!");
                            totalMessageSent = totalMessageSent + 1;
                        }
                    }

                    if (detail.size() == 0 || diffInDays >=0) {
                        final StringWriter stringWriter = new StringWriter();
                        if (reportName.equalsIgnoreCase("Loan Second Overdue Repayment Reminder")
                        		&& isSMSProcessed == Boolean.FALSE) {
                            velocityContext.put("month", loanOverdueMonthList.get(i));
                            velocityContext.put("overdueamount", loanOverdueDueAmountList.get(i));
                            velocityContext.put("branch", clientBranchList.get(i));
                            Velocity.evaluate(velocityContext, stringWriter, "loanFirstAndSecondOverdueRepaymentReminder",
                                    this.messageTemplate);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response= smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            { 	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                                isSMSProcessed = Boolean.TRUE;
                             }
                              eventSourceDetail.setMessage(stringWriter.toString());
                            eventSourceDetail.setReportName(reportName);
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("Loan Second Overdue Repayment Reminder Sms  to\n" + "clientName: " + clientNameList.get(i) + " \n"
                                    + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "event processed!");
                            totalMessageSent = totalMessageSent + 1;
                        } else if (reportName.equalsIgnoreCase("Loan Third Overdue Repayment Reminder")
                        		&& isSMSProcessed == Boolean.FALSE) {
                            Velocity.evaluate(velocityContext, stringWriter, "messageForThirdAndFourthOverdue",
                                    this.messageForThirdAndFourthOverdue);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response= smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                            	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                                isSMSProcessed = Boolean.TRUE;
                             }
                            eventSourceDetail.setReportName(reportName);
                            eventSourceDetail.setMessage(stringWriter.toString());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("Loan Third Overdue Repayment Reminder Sms  to\n" + "guarantorsName: " + guarantorsNameList.get(i)
                                    + " \n" + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "\nevent processed!");
                            totalMessageSent = totalMessageSent + 1;
                        } else if (reportName.equalsIgnoreCase("Loan Fourth Overdue Repayment Reminder")
                        		&& isSMSProcessed == Boolean.FALSE) {
                            Velocity.evaluate(velocityContext, stringWriter, "messageForThirdAndFourthOverdue",
                                    this.messageForThirdAndFourthOverdue);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                            	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                                isSMSProcessed = Boolean.TRUE;
                             }
                             eventSourceDetail.setReportName(reportName);
                            eventSourceDetail.setMessage(stringWriter.toString());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("Loan Fourth Overdue Repayment Reminder  to\n" + "guarantorsName: " + guarantorsNameList.get(i)
                                    + " \n" + "MobileNo: " + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "\nevent processed!");
                            totalMessageSent = totalMessageSent + 1;
                        } else if (reportName.equalsIgnoreCase("DefaultWarning - Clients")
                        		&&  isSMSProcessed == Boolean.FALSE) {
                            velocityContext.put("month", loanOverdueMonthList.get(i));
                            velocityContext.put("overdueamount", loanOverdueDueAmountList.get(i));
                            velocityContext.put("branch", clientBranchList.get(i));
                            Velocity.evaluate(velocityContext, stringWriter, "messageForDefaultWarningToClient",
                                    this.messageForDefaultWarningToClient);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                            	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                                isSMSProcessed = Boolean.TRUE;
                                
                             }
                            eventSourceDetail.setLastModifiedOn(now);
                            eventSourceDetail.setReportName(reportName);
                            eventSourceDetail.setMessage(stringWriter.toString());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("DefaultWarning to  Clients  \n" + "clientName: " + clientNameList.get(i) + " \n" + "MobileNo: "
                                    + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "\nevent processed!");
                            totalMessageSent = totalMessageSent + 1;
                        } else if (reportName.equalsIgnoreCase("DefaultWarning -  guarantors")
                        		&&  isSMSProcessed == Boolean.FALSE) {
                            velocityContext.put("commitedShare", guarantorscomittedShareList.get(i));
                            Velocity.evaluate(velocityContext, stringWriter, "messageForDefaultWarningTogurantor",
                                    this.messageForDefaultWarningTogurantor);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response=smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                            	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                                isSMSProcessed = Boolean.TRUE;
                             }
                             eventSourceDetail.setLastModifiedOn(now);
                            eventSourceDetail.setReportName(reportName);
                            eventSourceDetail.setMessage(stringWriter.toString());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("DefaultWarning to  Clients  \n" + "clientName: " + clientNameList.get(i) + " \n" + "MobileNo: "
                                    + mobileNo + "\n" + "loanId: " + entityIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "\nevent processed!");
                            totalMessageSent = totalMessageSent + 1;
                        } else if (reportName.equalsIgnoreCase("DormancyWarning - Clients")
                        		&&  isSMSProcessed == Boolean.FALSE) {
                        	if (detail.size() == 0 || diffInDays + 1 >= 90) {
                            velocityContext.put("branch", clientBranchList.get(i));
                            Velocity.evaluate(velocityContext, stringWriter, "messageForDormancyWarningToclient",
                                    this.messageForDormancyWarningToclient);
                            final SMSGateway smsGateway = this.smsGatewayProvider.get(smsBridgeConfig.getSmsProvider());
                            JSONArray response= smsGateway.sendMessage(smsBridgeConfig, mobileNo, stringWriter.toString());
                            JSONObject result = response.getJSONObject(0);
                            if(result.getString("status").equals("success")||result.getString("status").equalsIgnoreCase("success"))
                            {
                            	eventSource.setProcessed(Boolean.TRUE);
                                eventSourceDetail.setProcessed(Boolean.TRUE);
                                isSMSProcessed = Boolean.TRUE;
                             }
                            eventSourceDetail.setLastModifiedOn(now);
                            eventSourceDetail.setReportName(reportName);
                            eventSourceDetail.setMessage(stringWriter.toString());
                            this.eventSourceDetailRepository.save(eventSourceDetail);
                            logger.info("DormancyWarning to  Clients  \n" + "clientName: " + clientNameList.get(i) + " \n" + "MobileNo: "
                                    + mobileNo + "\n" + "SavingId: " + savingIdList.get(i) + "\n" + "productName: "
                                    + productShortNameList.get(i) + "\n" + "message: " + stringWriter.toString() + "\nevent processed!");
                            totalMessageSent = totalMessageSent + 1;
                        	}
                        }
                    }
                }
            } catch (RetrofitError rer) {
                if (rer.getResponse().getStatus() == 404) {
                    logger.info("Client not found!");
                }
                eventSource.setProcessed(Boolean.FALSE);
                eventSource.setErrorMessage(rer.getMessage());
                eventSourceDetail.setProcessed(Boolean.FALSE);
                eventSourceDetail.setErrorMessage(rer.getMessage());

            } catch (SMSGatewayException sgex) {
                eventSource.setProcessed(Boolean.FALSE);
                eventSource.setErrorMessage(sgex.getMessage());
                eventSourceDetail.setProcessed(Boolean.FALSE);
                eventSourceDetail.setErrorMessage(sgex.getMessage());

            } catch (ParseException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            eventSource.setLastModifiedOn(new Date());
            eventSourceDetail.setLastModifiedOn(new Date());
            this.eventSourceRepository.save(eventSource);
        }
        logger.info(reportName + ": Total Number of clients to sent Reminder/Overdue messages:" + clientMobileNumberList.size());
        logger.info(reportName + ": Number of messages sent successfully:" + totalMessageSent);
        logger.info(reportName + ": Number of unsucessful messages:" + totalMessageUnsent);

    }
}
