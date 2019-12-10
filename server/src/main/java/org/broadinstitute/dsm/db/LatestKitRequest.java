package org.broadinstitute.dsm.db;

import lombok.Data;
import org.broadinstitute.ddp.db.SimpleResult;
import org.broadinstitute.dsm.statics.DBConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.broadinstitute.ddp.db.TransactionWrapper.inTransaction;

@Data
public class LatestKitRequest {

    private static final Logger logger = LoggerFactory.getLogger(LatestKitRequest.class);

    public static final String SQL_SELECT_LATEST_KIT_REQUESTS = "SELECT site.ddp_instance_id, site.instance_name, site.base_url, " +
            "site.collaborator_id_prefix, site.auth0_token, site.es_participant_index, site.migrated_ddp, (SELECT req2.ddp_kit_request_id FROM ddp_kit_request req2 " +
            "WHERE req2.dsm_kit_request_id = (SELECT max(req.dsm_kit_request_id) FROM ddp_kit_request req " +
            "WHERE req.ddp_instance_id = site.ddp_instance_id AND req.ddp_kit_request_id NOT LIKE 'MIGRATED%' " +
            "AND req.ddp_kit_request_id not like 'UPLOADED%')) AS last_kit, (SELECT count(role.name) FROM ddp_instance realm, " +
            "ddp_instance_role inRol, instance_role role WHERE realm.ddp_instance_id = inRol.ddp_instance_id " +
            "AND inRol.instance_role_id = role.instance_role_id AND role.name = ? AND realm.ddp_instance_id = site.ddp_instance_id) AS 'has_role', " +
            "(SELECT count(role.name) FROM ddp_instance realm, ddp_instance_role inRol, instance_role role WHERE realm.ddp_instance_id = inRol.ddp_instance_id " +
            "AND inRol.instance_role_id = role.instance_role_id AND role.name = ? AND realm.ddp_instance_id = site.ddp_instance_id) AS 'has_second_role'," +
            "(SELECT count(role.name) FROM ddp_instance realm, ddp_instance_role inRol, instance_role role WHERE realm.ddp_instance_id = inRol.ddp_instance_id " +
            "AND inRol.instance_role_id = role.instance_role_id AND role.name = ? AND realm.ddp_instance_id = site.ddp_instance_id) AS 'has_third_role'" +
            "FROM ddp_instance site WHERE site.is_active = 1";

    public static final String MIGRATED_KIT_REQUEST = "MIGRATED_";

    private String latestDDPKitRequestID;
    private final String instanceID;
    private final String instanceName;
    private final String baseURL;
    private final String collaboratorIdPrefix;
    private final boolean hasAuth0Token;
    private final boolean consentDownloadEndpoints;
    private final boolean releaseDownloadEndpoints;
    private final boolean isMigrated;
    private final String participantIndexES;

    public LatestKitRequest(String latestDDPKitRequestID, String instanceID, String instanceName, String baseURL, String collaboratorIdPrefix,
                            boolean hasAuth0Token, boolean consentDownloadEndpoints, boolean releaseDownloadEndpoints, boolean isMigrated, String participantIndexES){
        this.latestDDPKitRequestID = latestDDPKitRequestID;
        this.instanceID = instanceID;
        this.instanceName = instanceName;
        this.baseURL = baseURL;
        this.collaboratorIdPrefix = collaboratorIdPrefix;
        this.hasAuth0Token = hasAuth0Token;
        this.consentDownloadEndpoints = consentDownloadEndpoints;
        this.releaseDownloadEndpoints = releaseDownloadEndpoints;
        this.isMigrated = isMigrated;
        this.participantIndexES = participantIndexES;
    }

    /**
     * Getting the latest KitRequestShipping for all portals from ddp_kit_request
     * @return List<LatestKitRequest>
     * @throws Exception
     */
    public static List<LatestKitRequest> getLatestKitRequests() {
        List<LatestKitRequest> latestKitRequests = new ArrayList<>();
        SimpleResult results = inTransaction((conn) -> {
            SimpleResult dbVals = new SimpleResult();
            try (PreparedStatement bspStatement = conn.prepareStatement(SQL_SELECT_LATEST_KIT_REQUESTS)) {
                bspStatement.setString(1, DBConstants.HAS_KIT_REQUEST_ENDPOINTS);
                bspStatement.setString(2, DBConstants.PDF_DOWNLOAD_CONSENT);
                bspStatement.setString(3, DBConstants.PDF_DOWNLOAD_RELEASE);
                try (ResultSet rs = bspStatement.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getBoolean(DBConstants.HAS_ROLE)) {
                            LatestKitRequest latestKitRequest = new LatestKitRequest(
                                    rs.getString(DBConstants.DDP_KIT_REQUEST_ID),
                                    rs.getString(DBConstants.DDP_INSTANCE_ID),
                                    rs.getString(DBConstants.INSTANCE_NAME),
                                    rs.getString(DBConstants.BASE_URL),
                                    rs.getString(DBConstants.COLLABORATOR_ID_PREFIX),
                                    rs.getBoolean(DBConstants.NEEDS_AUTH0_TOKEN),
                                    rs.getBoolean(DBConstants.HAS_SECOND_ROLE),
                                    rs.getBoolean(DBConstants.HAS_THIRD_ROLE),
                                    rs.getBoolean(DBConstants.MIGRATED_DDP),
                                    rs.getString(DBConstants.ES_PARTICIPANT_INDEX));
                            if (latestKitRequest.getLatestDDPKitRequestID() != null) {
                                logger.info("Found latestKitRequestID " + latestKitRequest.getLatestDDPKitRequestID() + " via " + latestKitRequest.getInstanceName());
                                if (latestKitRequest.getLatestDDPKitRequestID().startsWith(MIGRATED_KIT_REQUEST)) {
                                    //change the DDPKitRequest back to null (normal behaviour without migration (otherwise DDP will throw 500))
                                    latestKitRequest.setLatestDDPKitRequestID(null);
                                }
                            }
                            latestKitRequests.add(latestKitRequest);
                        }
                    }
                }
            }
            catch (SQLException ex) {
                dbVals.resultException = ex;
            }
            return dbVals;
        });

        if (results.resultException != null) {
            throw new RuntimeException("Error looking up the latestKitRequests ", results.resultException);
        }
        logger.info("Found " + latestKitRequests.size() + " ddp instance to look up");
        return latestKitRequests;
    }
}
