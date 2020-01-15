package org.broadinstitute.dsm.db;

import lombok.Data;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.ddp.db.SimpleResult;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.dsm.model.ddp.DDPParticipant;
import org.broadinstitute.dsm.statics.ApplicationConfigConstants;
import org.broadinstitute.dsm.statics.DBConstants;
import org.broadinstitute.dsm.statics.RoutePath;
import org.broadinstitute.dsm.util.DDPRequestUtil;
import org.broadinstitute.dsm.util.ElasticSearchUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

import static org.broadinstitute.ddp.db.TransactionWrapper.inTransaction;

@Data
public class ParticipantExit {

    private static final Logger logger = LoggerFactory.getLogger(ParticipantExit.class);

    private static final String SQL_SELECT_EXITED_PT = "SELECT realm.instance_name, ex.ddp_participant_id, u.name, ex.exit_date, " +
            "ex.in_ddp FROM ddp_participant_exit ex, ddp_instance realm, access_user u WHERE ex.ddp_instance_id = realm.ddp_instance_id " +
            "AND ex.exit_by = u.user_id AND realm.instance_name = ?";

    private final String realm;
    private final String participantId;
    private final String user;
    private final long exitDate;
    private final boolean inDDP;

    private String shortId;
    private String legacyShortId;

    public ParticipantExit(String realm, String participantId, String user, long exitDate, boolean inDDP) {
        this.realm = realm;
        this.participantId = participantId;
        this.user = user;
        this.exitDate = exitDate;
        this.inDDP = inDDP;
    }

    public static Map<String, ParticipantExit> getExitedParticipants(@NonNull String realm) {
        Map<String, ParticipantExit> exitedParticipants = new HashMap<>();
        SimpleResult results = inTransaction((conn) -> {
            SimpleResult dbVals = new SimpleResult();
            try (PreparedStatement stmt = conn.prepareStatement(SQL_SELECT_EXITED_PT)) {
                stmt.setString(1, realm);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String ddpParticipantId = rs.getString(DBConstants.DDP_PARTICIPANT_ID);
                        exitedParticipants.put(ddpParticipantId, new ParticipantExit(rs.getString(DBConstants.INSTANCE_NAME),
                                ddpParticipantId,
                                rs.getString(DBConstants.NAME), rs.getLong(DBConstants.EXIT_DATE),
                                rs.getBoolean(DBConstants.IN_DDP)));
                    }
                }
            }
            catch (Exception ex) {
                dbVals.resultException = ex;
            }
            return dbVals;
        });

        if (results.resultException != null) {
            logger.error("Couldn't get exited participants for " + realm, results.resultException);
        }
        else {
            addParticipantInformation(realm, exitedParticipants.values());
        }
        return exitedParticipants;
    }

    private static void addParticipantInformation(@NonNull String realm, @NonNull Collection<ParticipantExit> exitedParticipants) {
        DDPInstance instance = DDPInstance.getDDPInstanceWithRole(realm, DBConstants.HAS_MEDICAL_RECORD_INFORMATION_IN_DB);
        if (!instance.isHasRole()) {
            if (StringUtils.isNotBlank(instance.getParticipantIndexES())) {
                Map<String, Map<String, Object>> participantsESData = ElasticSearchUtil.getDDPParticipantsFromES(realm, instance.getParticipantIndexES());
                for (ParticipantExit exitParticipant : exitedParticipants) {
                    DDPParticipant ddpParticipant = ElasticSearchUtil.getParticipantAsDDPParticipant(participantsESData, exitParticipant.getParticipantId());
                    if (ddpParticipant != null) {
                        exitParticipant.setShortId(ddpParticipant.getShortId());
                        exitParticipant.setLegacyShortId(ddpParticipant.getLegacyShortId());
                    }
                }
            }
            else {
                for (ParticipantExit exitParticipant : exitedParticipants) {
                    if (exitParticipant.isInDDP()) {
                        String sendRequest = instance.getBaseUrl() + RoutePath.DDP_PARTICIPANTS_PATH + "/" + exitParticipant.getParticipantId();
                        try {
                            DDPParticipant ddpParticipant = DDPRequestUtil.getResponseObject(DDPParticipant.class, sendRequest, realm, instance.isHasAuth0Token());
                            if (ddpParticipant != null) {
                                exitParticipant.setShortId(ddpParticipant.getShortId());
                                exitParticipant.setLegacyShortId(ddpParticipant.getLegacyShortId());
                            }
                        }
                        catch (Exception ioe) {
                            logger.error("Couldn't get shortId of withdrawn participant from " + sendRequest, ioe);
                        }
                    }
                    else {
                        logger.info("Participant w/ id " + exitParticipant.getParticipantId() + " is flagged as deleted in the ddp");
                    }
                }
            }
        }
    }

    public static void exitParticipant(@NonNull String ddpParticipantId, @NonNull long currentTime, @NonNull String userId,
                                       @NonNull DDPInstance instance, boolean inDDP) {
        SimpleResult results = inTransaction((conn) -> {
            SimpleResult dbVals = new SimpleResult();
            try (PreparedStatement stmt = conn.prepareStatement(TransactionWrapper.getSqlFromConfig(ApplicationConfigConstants.INSERT_EXITED_PARTICIPANT))) {
                stmt.setString(1, instance.getDdpInstanceId());
                stmt.setString(2, ddpParticipantId);
                stmt.setLong(3, currentTime);
                stmt.setString(4, userId);
                stmt.setBoolean(5, inDDP);
                int result = stmt.executeUpdate();
                if (result == 1) {
                    logger.info("Exited participant w/ ddpParticipantId " + ddpParticipantId + " from " + instance.getName());
                }
                else {
                    throw new RuntimeException("Something is wrong w/ ddpParticipantId " + ddpParticipantId);
                }
            }
            catch (Exception ex) {
                dbVals.resultException = ex;
            }
            return dbVals;
        });

        if (results.resultException != null) {
            logger.error("Couldn't exit participant w/ ddpParticipantId " + ddpParticipantId, results.resultException);
        }
    }
}
