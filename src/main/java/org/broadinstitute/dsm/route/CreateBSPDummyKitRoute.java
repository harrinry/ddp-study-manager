package org.broadinstitute.dsm.route;

import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.ddp.handlers.util.Result;
import org.broadinstitute.dsm.db.DDPInstance;
import org.broadinstitute.dsm.db.KitRequestShipping;
import org.broadinstitute.dsm.db.dao.kit.BSPKitDao;
import org.broadinstitute.dsm.statics.RequestParameter;
import org.broadinstitute.dsm.util.DBUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Route;

public class CreateBSPDummyKitRoute implements Route {
    private static final String DUMMY_KIT_TYPE_NAME = "DUMMY_KIT_TYPE";
    private static final String DUMMY_REALM_NAME = "DUMMY_KIT_REALM";
    private static final String USER_ID = "74";
    private static final Logger logger = LoggerFactory.getLogger(CreateBSPDummyKitRoute.class);

    @Override
    public Object handle(Request request, Response response) throws Exception {
        logger.info("Got Mercury Test request");
        String kitLabel = request.params(RequestParameter.LABEL);
        if (StringUtils.isBlank(kitLabel)) {
            response.status(400);// return bad request
            logger.error("Bad request from Mercury! Should include a kitlabel");
            return new Result(400, "Please include a kit label as a path parameter");
        }
        logger.info("Found kitlabel " + kitLabel + " in Mercury request");
        int ddpInstanceId = (int) DBUtil.getBookmark(DUMMY_REALM_NAME);
        logger.info("Found ddp instance id for mock test " + ddpInstanceId);
        DDPInstance mockDdpInstance = DDPInstance.getDDPInstanceById(ddpInstanceId);
        if (mockDdpInstance != null) {
            logger.info("Found mockDdpInstance " + mockDdpInstance.getName());
            String mercuryKitRequestId = "MERCURY_" + KitRequestShipping.createRandom(20);
            int kitTypeId = (int) DBUtil.getBookmark(DUMMY_KIT_TYPE_NAME);
            logger.info("Found kit type for Mercury Dummy Endpoint " + kitTypeId);

            String ddpParticipantId = BSPKitDao.getRandomParticipantIdForStudy(mockDdpInstance.getDdpInstanceId());
            String participantCollaboratorId = KitRequestShipping.getCollaboratorParticipantId(mockDdpInstance.getBaseUrl(), mockDdpInstance.getDdpInstanceId(), mockDdpInstance.isMigratedDDP(),
                    mockDdpInstance.getCollaboratorIdPrefix(), ddpParticipantId, "", null);
            String collaboratorSampleId = BSPKitDao.getCollaboratorSampleId(kitTypeId, participantCollaboratorId, DUMMY_KIT_TYPE_NAME);
            if (ddpParticipantId != null) {
                //if instance not null
                String dsmKitRequestId = KitRequestShipping.writeRequest(mockDdpInstance.getDdpInstanceId(), mercuryKitRequestId, kitTypeId,
                        ddpParticipantId, participantCollaboratorId, collaboratorSampleId,
                        USER_ID, "", "", "", false, "");
                BSPKitDao.updateKitLabel(kitLabel, dsmKitRequestId);
            }
            logger.info("Returning 200 to Mercury");
            return new Result(200);
        }
        logger.error("Returning 500 to Mercury");
        return new Result(500);
    }


}
