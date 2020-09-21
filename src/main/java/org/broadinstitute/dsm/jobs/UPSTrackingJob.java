package org.broadinstitute.dsm.jobs;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.ddp.db.SimpleResult;
import org.broadinstitute.dsm.DSMServer;
import org.broadinstitute.dsm.careevolve.Covid19OrderRegistrar;
import org.broadinstitute.dsm.db.DDPInstance;
import org.broadinstitute.dsm.db.DdpKit;
import org.broadinstitute.dsm.model.KitDDPNotification;
import org.broadinstitute.dsm.model.ups.*;
import org.broadinstitute.dsm.statics.DBConstants;
import org.broadinstitute.dsm.util.DDPRequestUtil;
import org.broadinstitute.dsm.util.EventUtil;
import org.broadinstitute.dsm.util.externalShipper.GBFRequestUtil;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static org.broadinstitute.ddp.db.TransactionWrapper.inTransaction;

public class UPSTrackingJob implements Job {

    private static final Logger logger = LoggerFactory.getLogger(UPSTrackingJob.class);
    private static final String SQL_SELECT_KITS = "SELECT * FROM ddp_kit kit LEFT JOIN ddp_kit_request req " +
            "ON (kit.dsm_kit_request_id = req.dsm_kit_request_id) WHERE req.ddp_instance_id = ?";

    private static final String SQL_UPDATE_UPS_TRACKING_STATUS = "UPDATE ddp_kit SET ups_tracking_status = ?, ups_tracking_date = ? " +
            "WHERE dsm_kit_id <> 0 and dsm_kit_id in ( SELECT dsm_kit_id FROM ( SELECT * from ddp_kit) as something WHERE something.tracking_to_id = ? );";
    private static final String SQL_UPDATE_UPS_RETURN_STATUS = "UPDATE ddp_kit SET ups_return_status = ?, ups_return_date = ? " +
            "WHERE dsm_kit_id <> 0 and dsm_kit_id in ( SELECT dsm_kit_id FROM ( SELECT * from ddp_kit) as something WHERE something.tracking_return_id= ? );";

    private static final String SQL_SELECT_KIT_FOR_NOTIFICATION_EXTERNAL_SHIPPER = "select  eve.*,   request.ddp_participant_id,   request.ddp_label,   request.dsm_kit_request_id, realm.ddp_instance_id, realm.instance_name, realm.base_url, realm.auth0_token, realm.notification_recipients, realm.migrated_ddp, kit.receive_date, kit.scan_date" +
            "        from ddp_kit_request request, ddp_kit kit, event_type eve, ddp_instance realm where request.dsm_kit_request_id = kit.dsm_kit_request_id and request.ddp_instance_id = realm.ddp_instance_id" +
            "        and (eve.ddp_instance_id = request.ddp_instance_id and eve.kit_type_id = request.kit_type_id) and eve.event_type = ? ";

    static String DELIVERED = "DELIVERED";
    static String RECEIVED = "RECEIVED";

    private static String SELECT_BY_TRACKING_NUMBER = "and kit.tracking_to_id = ?";
    private static String SELECT_BY_RETURN_NUMBER = "and kit.tracking_return_id = ?";
    private static Covid19OrderRegistrar orderRegistrar;

    private static final String OUT_FOR_DELIVERY = "O";
    private static final String PICKUP = "P";
    private static final String DELIVERY = "D";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        logger.info("Starting the UPS lookup job");
        List<DDPInstance> ddpInstanceList = DDPInstance.getDDPInstanceListWithRole("ups_tracking");
        for (DDPInstance ddpInstance : ddpInstanceList) {
            if (ddpInstance.isHasRole()) {
                logger.info("tracking ups ids for " + ddpInstance.getName());
                if (ddpInstance != null) {
                    Map<String, Set<DdpKit>> ids = getResultSet(ddpInstance.getDdpInstanceId());
                    orderRegistrar = new Covid19OrderRegistrar(DSMServer.careEvolveOrderEndpoint, DSMServer.careEvolveAccount, DSMServer.provider);
                    Set<DdpKit> kits = ids.get("shipping");
                    logger.info("checking tracking status for " + kits.size() + " tracking numbers");
                    for (DdpKit kit : kits) {
                        lookUpKit(kit, false);
                    }
                    kits = ids.get("return");
                    logger.info("checking return status for " + kits.size() + " tracking numbers");
                    for (DdpKit kit : kits) {
                        lookUpKit(kit, true);
                    }
                }
            }
        }
    }

    public static void lookUpKit(DdpKit kit, boolean isReturn) {
        String trackingId;
        if (!isReturn) {
            trackingId = kit.getTrackingToId();
        }
        else {
            trackingId = kit.getTrackingReturnId();
        }
        String transId = NanoIdUtils.randomNanoId(
                NanoIdUtils.DEFAULT_NUMBER_GENERATOR, "1234567890QWERTYUIOPASDFGHJKLZXCVBNM".toCharArray(), 32);
        //        String inquiryNumber = "7798339175";
        String inquiryNumber = trackingId.trim();
        String transSrc = "TestTracking";
        String sendRequest = DSMServer.UPS_ENDPOINT + inquiryNumber;
        Map<String, String> headers = new HashMap<>();
        headers.put("transId", transId);
        headers.put("transSrc", transSrc);
        headers.put("Username", DSMServer.UPS_USERNAME);
        headers.put("Password", DSMServer.UPS_PASSWORD);
        headers.put("AccessLicenseNumber", DSMServer.UPS_ACCESSKEY);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        try {
                UPSTrackingResponse response = DDPRequestUtil.getResponseObjectWithCustomHeader(UPSTrackingResponse.class, sendRequest, "UPS Tracking Test " + inquiryNumber, headers);
            logger.info("got response back from UPS: " + response);
            String type = kit.getUpsTrackingStatus();
            if (StringUtils.isNotBlank(type)) {// get only type from it
                type = type.substring(0, type.indexOf(' '));
            }
            if (response.getErrors() == null) {
                updateStatus(trackingId, type, response, isReturn, kit);
            }
            else {
                logError(trackingId, response.getErrors());
            }
        }
        catch (IOException e) {
            throw new RuntimeException("couldn't get response from ups tracking ", e);
        }
    }

    private static void logError(String trackingId, UPSError[] errors) {
        String errorString = "";
        for (UPSError error : errors) {
            errorString += "Got Error: " + error.getCode() + " " + error.getMessage() + " For Tracking Number " + trackingId;
        }
        logger.error(errorString);
    }

    private static void updateStatus(String trackingId, String oldType, UPSTrackingResponse response, boolean isReturn, DdpKit kit) {
        UPSShipment[] shipment = response.getTrackResponse().getShipment();
        if (shipment != null && shipment.length > 0) {
            UPSPackage[] upsPackages = shipment[0].getUpsPackageArray();
            if (upsPackages != null && upsPackages.length > 0) {
                UPSPackage upsPackage = upsPackages[0];
                UPSActivity activity = upsPackage.getActivity()[0];
                UPSStatus status = activity.getStatus();
                String statusType = status.getType();
                String statusDescription = status.getDescription();
                String date = activity.getDate() + " " + activity.getTime();
                if (!isReturn) {
                    updateTrackingInfo(statusType, oldType, statusDescription, trackingId, date, SQL_UPDATE_UPS_TRACKING_STATUS, isReturn, kit);
                }
                else {
                    updateTrackingInfo(statusType, oldType, statusDescription, trackingId, date, SQL_UPDATE_UPS_RETURN_STATUS, isReturn, kit);
                }
            }
        }
    }

    private static void updateTrackingInfo(String statusType, String oldType, String statusDescription, String trackingId, String date, String query, boolean isReturn, DdpKit kit) {
        SimpleResult result = inTransaction((conn) -> {
            SimpleResult dbVals = new SimpleResult();
            String upsUpdate = statusType + " " + statusDescription;
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, upsUpdate);
                stmt.setString(2, date);
                stmt.setString(3, trackingId);
                int r = stmt.executeUpdate();
                if (r != 2) {//number of subkits
                    throw new RuntimeException("Update query for UPS tracking updated " + r + " rows!");
                }
                else {
                    if (!isReturn) {
                        //if delivered notif pepper
                        if (statusType.equals(OUT_FOR_DELIVERY) && !(OUT_FOR_DELIVERY.equals(oldType))) {
                            KitDDPNotification kitDDPNotification = KitDDPNotification.getKitDDPNotification(SQL_SELECT_KIT_FOR_NOTIFICATION_EXTERNAL_SHIPPER + SELECT_BY_TRACKING_NUMBER, new String[] { DELIVERED, trackingId }, 2);//todo change this to the number of subkits but for now 2 for test boston works
                            if (kitDDPNotification != null) {
                                logger.info("Triggering DDP for delivered kit with external order number: " + kit.getExternalOrderNumber());
                                EventUtil.triggerDDP(kitDDPNotification);

                            }
                            else {
                                logger.error("delivered kitDDPNotification was null for " + kit.getExternalOrderNumber());
                            }
                        }

                    }
                    else {
                        //if picked up place order
                        if (statusType.equals(PICKUP) && !(PICKUP.equals(oldType))) {
                            Instant now = Instant.now();
                            orderRegistrar.orderTest(DSMServer.careEvolveAuth, kit.getHRUID(), kit.getKitLabel(), kit.getExternalOrderNumber(), now);
                        }
                        //if delivered notify pepper for received
                        else if (statusType.equals(DELIVERY) && !(DELIVERY.equals(oldType))) {
//                            GBFRequestUtil.updateReceivedDateForKit(kit.getDsmKitRequestId());
                            KitDDPNotification kitDDPNotification = KitDDPNotification.getKitDDPNotification(SQL_SELECT_KIT_FOR_NOTIFICATION_EXTERNAL_SHIPPER + SELECT_BY_RETURN_NUMBER, new String[] { RECEIVED, trackingId }, 2);//todo change this to the number of subkits but for now 2 for test boston works
                            if (kitDDPNotification != null) {
                                logger.info("Triggering DDP for received kit with external order number: " + kit.getExternalOrderNumber());
                                EventUtil.triggerDDP(kitDDPNotification);

                            }
                            else {
                                logger.error("received kitDDPNotification was null for " + kit.getExternalOrderNumber());
                            }
                        }
                    }
                    logger.info("Updated status of tracking number " + trackingId + " to " + upsUpdate);
                }

            }
            catch (Exception e) {
                dbVals.resultException = e;
            }
            return dbVals;
        });
        if (result.resultException != null) {
            throw new RuntimeException(result.resultException);
        }
    }

    public static void testMethod() {

    }


    public static Map<String, Set<DdpKit>> getResultSet(String realm) {
        SimpleResult result = inTransaction((conn) -> {
            SimpleResult dbVals = new SimpleResult();
            try (PreparedStatement stmt = conn.prepareStatement(SQL_SELECT_KITS)) {
                stmt.setString(1, realm);
                try (ResultSet rs = stmt.executeQuery()) {
                    Map<String, Set<DdpKit>> results = getIdsFromResultSet(rs);
                    dbVals.resultValue = results;
                    return dbVals;
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            catch (Exception e) {
                dbVals.resultException = e;
            }
            return dbVals;
        });
        if (result.resultException != null) {
            throw new RuntimeException(result.resultException);
        }
        return (Map<String, Set<DdpKit>>) result.resultValue;
    }

    public static Map<String, Set<DdpKit>> getIdsFromResultSet(ResultSet rs) {
        Set<DdpKit> returnTrackingIds = new HashSet<>();
        Set<DdpKit> trackingIds = new HashSet<>();
        try {
            while (rs.next()) {
                DdpKit kit = new DdpKit(
                        rs.getString(DBConstants.DSM_KIT_REQUEST_ID),
                        rs.getString("kit." + DBConstants.KIT_LABEL),
                        rs.getString("kit." + DBConstants.DSM_TRACKING_TO),
                        rs.getString("kit." + DBConstants.TRACKING_RETURN_ID),
                        rs.getString("kit." + DBConstants.ERROR),
                        rs.getString("kit." + DBConstants.MESSAGE),
                        rs.getString("kit." + DBConstants.DSM_RECEIVE_DATE),
                        rs.getString("kit." + DBConstants.UPS_TRACKING_STATUS),
                        rs.getString("kit." + DBConstants.UPS_TRACKING_DATE),
                        rs.getString("kit." + DBConstants.UPS_RETURN_STATUS),
                        rs.getString("kit." + DBConstants.UPS_RETURN_DATE),
                        rs.getString("req." + DBConstants.BSP_COLLABORATOR_PARTICIPANT_ID),
                        rs.getString("req." + DBConstants.EXTERNAL_ORDER_NUMBER)
                );
                String type;
                if (StringUtils.isNotBlank(kit.getTrackingToId())) {
                    type = kit.getUpsTrackingStatus();
                    if (StringUtils.isNotBlank(type)) {// get only type from it
                        type = type.substring(0, type.indexOf(' '));
                    }
                    if (!"D".equals(type)) {//don't include delivered ones
                        trackingIds.add(kit);
                    }
                }
                if (StringUtils.isNotBlank(kit.getTrackingReturnId())) {
                    type = kit.getUpsReturnStatus();
                    if (StringUtils.isNotBlank(type)) {
                        type = type.substring(0, type.indexOf(' '));
                    }
                    if (!"D".equals(type)) {
                        returnTrackingIds.add(kit);
                    }
                }
            }
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        Map<String, Set<DdpKit>> results = new HashMap<>();
        results.put("shipping", trackingIds);
        results.put("return", returnTrackingIds);
        return results;
    }


}