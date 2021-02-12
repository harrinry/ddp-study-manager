package org.broadinstitute.dsm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.typesafe.config.Config;
import org.broadinstitute.dsm.db.EditParticipantMessage;
import org.broadinstitute.dsm.db.User;
import org.broadinstitute.dsm.pubsub.EditParticipantMessagePublisher;
import org.broadinstitute.dsm.pubsub.PubSubResultMessageSubscription;
import org.broadinstitute.dsm.route.EditParticipantPublisherRoute;
import org.broadinstitute.dsm.statics.DBConstants;
import org.broadinstitute.dsm.util.UserUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class EditParticipantTest extends TestHelper {

    public static final String GCP_PATH_TO_PUBSUB_PROJECT_ID = "pubsub.projectId";
    public static final String GCP_PATH_TO_DSS_TO_DSM_SUB = "pubsub.dss_to_dsm_subscription";
    public static final String GCP_PATH_TO_DSM_TO_DSS_TOPIC = "pubsub.dsm_to_dss_topic";
    public static final String TEST_PAYLOAD = "{\"participantGuid\":\"TEST\",\"studyGuid\":\"testboston\",\"data\":{\"firstName\":\"test\"}}";
    public static final String UNIT_TESTER_EMAIL = "unitTesterEmail";

    public Config cfg;
    String projectId;
    String dsmToDssSubscriptionId;
    String topicId;
    String messageData;
    int userId;

    @Before
    public void first() {
        setupDB();
        cfg = TestHelper.cfg;
        projectId = cfg.getString(GCP_PATH_TO_PUBSUB_PROJECT_ID);
        dsmToDssSubscriptionId = cfg.getString(GCP_PATH_TO_DSS_TO_DSM_SUB);
        topicId = cfg.getString(GCP_PATH_TO_DSM_TO_DSS_TOPIC);
        messageData = TEST_PAYLOAD;
        userId = User.getUser(cfg.getString(UNIT_TESTER_EMAIL)).getUserId();
    }

    @Test
    public void testEditParticipantFeature() {

        String realm = null;

        if (UserUtil.checkUserAccess(realm, Integer.toString(userId), "mr_view") ||
                UserUtil.checkUserAccess(realm, Integer.toString(userId), "pt_list_view")) {

            try {
                PubSubResultMessageSubscription.dssToDsmSubscriber(projectId, dsmToDssSubscriptionId);
            } catch (Exception e) {
                e.printStackTrace();
            }

            JsonObject messageJsonObject = new Gson().fromJson(messageData, JsonObject.class);

            String data = messageJsonObject.get("data").getAsJsonObject().toString();

            Map<String, String> attributeMap = EditParticipantPublisherRoute.getStringStringMap(Integer.toString(userId), messageJsonObject);

            try {
                EditParticipantMessagePublisher.publishMessage(data, attributeMap, projectId, topicId);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                TimeUnit.SECONDS.sleep(15);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            EditParticipantMessage messageWithStatus = EditParticipantMessage.getMessageWithStatus(userId);

            int messageId = messageWithStatus.getMessageId();
            String receivedStatus = messageWithStatus.getMessageStatus();
            String receivedMessage = messageWithStatus.getReceived_message();

            JsonObject receivedMessageJsonObject = new Gson().fromJson(receivedMessage, JsonObject.class);

            Assert.assertEquals(DBConstants.MESSAGE_RECEIVED_STATUS, receivedStatus);

            Assert.assertEquals(messageJsonObject.get("participantGuid"), receivedMessageJsonObject.get("participantGuid"));
            Assert.assertEquals(messageJsonObject.get("studyGuid"), receivedMessageJsonObject.get("studyGuid"));
            Assert.assertEquals(messageJsonObject.get("data").getAsJsonObject().get("firstName"), receivedMessageJsonObject.get("firstName"));
            Assert.assertEquals(userId, receivedMessageJsonObject.get("userId").getAsInt());

            EditParticipantMessage.updateMessageStatusById(messageId, DBConstants.MESSAGE_SENT_BACK_STATUS);

            messageWithStatus = EditParticipantMessage.getMessageWithStatus(userId);

            String status = messageWithStatus.getMessageStatus();

            Assert.assertEquals(DBConstants.MESSAGE_SENT_BACK_STATUS, status);

        }
    }

    @After
    public void last() {
        EditParticipantMessage.deleteMessage(userId);
    }
}