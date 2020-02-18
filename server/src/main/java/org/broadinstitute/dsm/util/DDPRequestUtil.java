package org.broadinstitute.dsm.util;

import com.google.api.client.http.HttpStatusCodes;
import com.google.gson.*;
import lombok.NonNull;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Executor;
import org.apache.http.util.EntityUtils;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.handlers.util.*;
import org.broadinstitute.ddp.util.GoogleBucket;
import org.broadinstitute.dsm.db.DDPInstance;
import org.broadinstitute.dsm.exception.SurveyNotCreated;
import org.broadinstitute.dsm.model.ddp.DDPParticipant;
import org.broadinstitute.dsm.statics.ApplicationConfigConstants;
import org.broadinstitute.dsm.statics.DBConstants;
import org.broadinstitute.dsm.statics.RoutePath;
import org.broadinstitute.dsm.statics.UserErrorMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class DDPRequestUtil {

    private static final Logger logger = LoggerFactory.getLogger(DDPRequestUtil.class);

    private static Executor blindTrustEverythingExecutor;

    public DDPRequestUtil() {
        try {
            if (Boolean.valueOf(TransactionWrapper.getSqlFromConfig("portal.enableBlindTrustDDP"))) {
                if (blindTrustEverythingExecutor == null) {
                    blindTrustEverythingExecutor = Executor.newInstance(SecurityUtil.buildHttpClient());
                    logger.info("Loaded blindTrustEverythingExecutor for DDP requests.");
                }
            }
        }
        catch (Exception e) {
            logger.error("Starting up the blindTrustEverythingExecutor ", e);
            System.exit(-3);
        }
    }

    public static String getContentAsString(HttpResponse response) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
        return org.apache.commons.io.IOUtils.toString(rd);
    }

    // make a get request
    public static <T> T getResponseObject(Class<T> responseClass, String sendRequest, String name, boolean auth0Token) throws IOException {
        logger.info("Requesting data from " + name + " w/ " + sendRequest);
        org.apache.http.client.fluent.Request request = SecurityUtil.createGetRequestWithHeader(sendRequest, name, auth0Token);
        T objects;
        if (blindTrustEverythingExecutor != null) {
            objects = blindTrustEverythingExecutor.execute(request).handleResponse(res -> getResponse(res, responseClass, sendRequest));
        }
        else {
            objects = request.execute().handleResponse(res -> getResponse(res, responseClass, sendRequest));
        }
        if (objects != null) {
            logger.info("Got response back");
        }
        return objects;
    }

    // make a post request
    public static Integer postRequest(String sendRequest, Object objectToPost, String name, boolean auth0Token) throws IOException {
        logger.info("Requesting data from " + name + " w/ " + sendRequest);
        org.apache.http.client.fluent.Request request = SecurityUtil.createPostRequestWithHeader(sendRequest, name, auth0Token, objectToPost);

        int responseCode;
        if (blindTrustEverythingExecutor != null) {
            responseCode = blindTrustEverythingExecutor.execute(request).handleResponse(res -> getResponseCode(res, sendRequest));
        }
        else {
            responseCode = request.execute().handleResponse(res -> getResponseCode(res, sendRequest));
        }
        return responseCode;
    }

    private static Integer getResponseCode(HttpResponse res, String sendRequest) {
        int responseCodeInt = res.getStatusLine().getStatusCode();
        if (responseCodeInt != HttpStatusCodes.STATUS_CODE_OK) {
            logger.error("Got " + responseCodeInt + " from " + sendRequest);
            if (responseCodeInt == HttpStatusCodes.STATUS_CODE_SERVER_ERROR) {
                throw new RuntimeException("Got " + responseCodeInt + " from " + sendRequest);
            }
        }
        return responseCodeInt;
    }

    private static <T> T getResponse(HttpResponse res, Class<T> responseClass, String sendRequest) {
        int responseCodeInt = getResponseCode(res, sendRequest);
        if (responseCodeInt == HttpStatusCodes.STATUS_CODE_OK) {
            try {
                logger.info("Got response back");
                String message = EntityUtils.toString(res.getEntity(), "UTF-8");
                return new Gson().fromJson(message, responseClass);
            }
            catch (IOException e) {
                throw new RuntimeException("Failed to read HttpResponse ", e);
            }
        }
        return null;
    }

    public static byte[] getPDFByteArray(@NonNull String sendRequest, @NonNull String name, boolean auth0Token) throws IOException {
        logger.info("Requesting data from " + name + " w/ " + sendRequest);

        org.apache.http.client.fluent.Request request = SecurityUtil.createGetRequestWithHeader(sendRequest, name, auth0Token);

        byte[] bytes;
        if (blindTrustEverythingExecutor != null) {
            bytes = blindTrustEverythingExecutor.execute(request).handleResponse(res -> getResponseByteArray(res, sendRequest));
        }
        else {
            bytes = request.execute().handleResponse(res -> getResponseByteArray(res, sendRequest));
        }
        return bytes;
    }

    private static byte[] getResponseByteArray(HttpResponse res, String sendRequest) {
        int responseCodeInt = getResponseCode(res, sendRequest);
        if (responseCodeInt == HttpStatusCodes.STATUS_CODE_OK) {
            try {
                InputStream is = res.getEntity().getContent();
                return IOUtils.toByteArray(is);
            }
            catch (IOException e) {
                throw new RuntimeException("Failed to read HttpResponse ", e);
            }
        }
        return null;
    }

    /**
     * Getting all participants of a ddp back
     *
     * @param instance DDPInstance object (information of the ddp like url, token)
     * @return HashMap<String                                                               ,                                                                                                                               DDPParticipant>
     * Key: (String) ddp_participant_id
     * Value: DDPParticipant (Information of participant from the ddp)
     */
    public static Map<String, DDPParticipant> getDDPParticipant(@NonNull DDPInstance instance) {
        Map<String, DDPParticipant> mapDDPParticipantInstitution = new HashMap<>();
        String sendRequest = instance.getBaseUrl() + RoutePath.DDP_PARTICIPANTINSTITUTIONS_PATH;
        try {
            ParticipantInstitutionInfo[] participantInfo = DDPRequestUtil.getResponseObject(ParticipantInstitutionInfo[].class,
                    sendRequest, instance.getName(), instance.isHasAuth0Token());
            if (participantInfo != null) {
                logger.info("Got " + participantInfo.length + " ParticipantInstitutionInfo ");
                for (ParticipantInstitutionInfo info : participantInfo) {
                    String key = info.getParticipantId();
                    mapDDPParticipantInstitution.put(key, new DDPParticipant(info.getShortId(), info.getLegacyShortId(), info.getFirstName(),
                            info.getLastName(), info.getAddress()));
                }
            }
        }
        catch (Exception ioe) {
            throw new RuntimeException("Couldn't get participants from " + sendRequest, ioe);
        }
        return mapDDPParticipantInstitution;
    }

    public static Collection<SurveyInfo> getFollowupSurveys(@NonNull DDPInstance instance) {
        SurveyInfo[] surveyInfos = null;
        String sendRequest = instance.getBaseUrl() + RoutePath.DDP_FOLLOW_UP_SURVEYS_PATH;
        try {
            surveyInfos = DDPRequestUtil.getResponseObject(SurveyInfo[].class, sendRequest, instance.getName(), instance.isHasAuth0Token());
            if (surveyInfos != null) {
                logger.info("Got " + surveyInfos.length + " SurveyInfo ");
            }
        }
        catch (Exception ioe) {
            throw new RuntimeException("Couldn't get followUp survey list from " + sendRequest, ioe);
        }
        return Arrays.asList(surveyInfos);
    }

    public static Result triggerFollowupSurvey(@NonNull DDPInstance instance, @NonNull SimpleFollowUpSurvey survey, @NonNull String surveyName) {
        String sendRequest = instance.getBaseUrl() + RoutePath.DDP_FOLLOW_UP_SURVEY_PATH + "/" + surveyName;
        Integer ddpResponse = null;
        try {
            ddpResponse = DDPRequestUtil.postRequest(sendRequest, survey, instance.getName(), instance.isHasAuth0Token());
        }
        catch (Exception e) {
            logger.error("Couldn't trigger survey for participant " + sendRequest, e);
            throw new SurveyNotCreated("Couldn't trigger survey for participant " + sendRequest);
        }
        if (ddpResponse == HttpStatusCodes.STATUS_CODE_OK) {
            logger.info("Triggered DDP to create " + surveyName + " survey for participant w/ ddpParticipantId " + survey.getParticipantId());
            return new Result(200);
        }
        return new Result(500, UserErrorMessages.SURVEY_NOT_CREATED);
    }

    public static List<ParticipantSurveyInfo> getFollowupSurveysStatus(@NonNull DDPInstance instance, @NonNull String surveyName) {
        List<ParticipantSurveyInfo> list = new ArrayList<>();
        ParticipantSurveyInfo[] participantSurveyInfos = null;
        String sendRequest = instance.getBaseUrl() + RoutePath.DDP_FOLLOW_UP_SURVEY_PATH + "/" + surveyName;
        try {
            participantSurveyInfos = DDPRequestUtil.getResponseObject(ParticipantSurveyInfo[].class, sendRequest, instance.getName(), instance.isHasAuth0Token());
            if (participantSurveyInfos != null) {
                logger.info("Got " + participantSurveyInfos.length + " ParticipantSurveyInfo ");
                for (ParticipantSurveyInfo info : participantSurveyInfos) {
                    list.add(info);
                }
            }
        }
        catch (Exception ioe) {
            throw new RuntimeException("Couldn't get followUp survey status list from " + sendRequest, ioe);
        }
        return list;
    }

    public static void savePDFsInBucket(@NonNull String baseURL, @NonNull String instanceName, @NonNull String ddpParticipantId, @NonNull boolean hasAuth0Token, @NonNull String pdfEndpoint,
                                        @NonNull long time, @NonNull String userId, @NonNull String reason) {
        String fileName = pdfEndpoint.replace("/", "").replace("pdf", "");
        String gcpCreds = TransactionWrapper.getSqlFromConfig(ApplicationConfigConstants.GOOGLE_PROJECT_CREDENTIALS);
        String gcpName = TransactionWrapper.getSqlFromConfig(ApplicationConfigConstants.GOOGLE_PROJECT_NAME);
        if (StringUtils.isNotBlank(gcpCreds) && StringUtils.isNotBlank(gcpName)) {
            String bucketName = gcpName + "_dsm_" + instanceName.toLowerCase();
            try {
                if (GoogleBucket.bucketExists(gcpCreds, gcpName, bucketName)) {
                    String pdfRequest = baseURL + RoutePath.DDP_PARTICIPANTS_PATH + "/" + ddpParticipantId + pdfEndpoint;
                    byte[] bytes = DDPRequestUtil.getPDFByteArray(pdfRequest, instanceName, hasAuth0Token);

                    GoogleBucket.uploadFile(gcpCreds, gcpName, bucketName, ddpParticipantId + "/readonly/" + ddpParticipantId + "_" + fileName + "_" + userId + "_" + reason + "_" + time + ".pdf",
                            new ByteArrayInputStream(bytes));
                }
            }
            catch (Exception e) {
                throw new RuntimeException("Couldn't save consent pdf in google bucket ", e);
            }
        }
    }

    public static void makePDF(@NonNull DDPInstance ddpInstance, @NonNull String ddpParticipantId, @NonNull String userId, @NonNull String reason) {
        DDPInstance instanceRole = DDPInstance.getDDPInstanceWithRole(ddpInstance.getName(), DBConstants.PDF_DOWNLOAD_RELEASE);
        makePDF(ddpInstance.isHasRole(), instanceRole.isHasRole(), ddpInstance.getBaseUrl(), ddpInstance.getName(), ddpParticipantId, ddpInstance.isHasAuth0Token(), userId, reason);
    }

    public static void makePDF(@NonNull boolean hasConsentEndpoints, @NonNull boolean hasReleaseEndpoints, @NonNull String baseUrl, @NonNull String instanceName, @NonNull String ddpParticipantId,
                               @NonNull boolean hasAuth0Token, @NonNull String userId, @NonNull String reason) {
        // save consent in bucket, if ddpInstance has endpoint
        long time = System.currentTimeMillis();
        if (hasConsentEndpoints) {
            try {
                DDPRequestUtil.savePDFsInBucket(baseUrl, instanceName, ddpParticipantId, hasAuth0Token, "/consentpdf", time, userId, reason);
            }
            catch (RuntimeException e) {
                logger.error("Couldn't download consent pdf ", e);
            }
        }
        // save release in bucket, if ddpInstance has endpoint
        if (hasReleaseEndpoints) {
            try {
                DDPRequestUtil.savePDFsInBucket(baseUrl, instanceName, ddpParticipantId, hasAuth0Token, "/releasepdf", time, userId, reason);
            }
            catch (RuntimeException e) {
                logger.error("Couldn't download consent pdf ", e);
            }
        }
    }
}
