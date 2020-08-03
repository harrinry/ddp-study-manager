package org.broadinstitute.dsm.careevolve;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.broadinstitute.dsm.db.DDPInstance;
import org.broadinstitute.dsm.exception.CareEvolveException;
import org.broadinstitute.dsm.model.ParticipantWrapper;
import org.broadinstitute.dsm.statics.DBConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places orders with CareEvolve for COVID-19 virology
 */
public class Covid19OrderRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(Covid19OrderRegistrar.class);

    private final String endpoint;

    private final String careEvolveAccount;

    /**
     * Create a new one that uses the given endpoint
     * for placing orders
     */
    public Covid19OrderRegistrar(String endpoint, String careEvolveAccount) {
        this.endpoint = endpoint;
        this.careEvolveAccount  = careEvolveAccount;
    }

    public OrderResponse orderTest(Authentication auth,String participantHruid, String kitLabel,
                                   String kitId) throws CareEvolveException {

        DDPInstance instance = DDPInstance.getDDPInstanceWithRole("testboston", DBConstants.HAS_KIT_REQUEST_ENDPOINTS);

        Map<String, String> queryConditions = new HashMap<>();
        queryConditions.put("ES", " AND profile.hruid = '" + participantHruid +"'");
        List<ParticipantWrapper> participants = ParticipantWrapper.getFilteredList(instance, queryConditions);


        if (participants.size() == 1) {
            ParticipantWrapper participant = participants.iterator().next();
            Map data = participant.getData();
            if (data != null) {
                Map<String, String> address = (Map) data.get("address");
                if (address != null) {
                    Address careEvolveAddress = toCareEvolveAddress(address);
                    logger.info("foo");

                    Map<String, String> profile = (Map) participant.getData().get("profile");
                    String patientId = profile.get("guid");

                    String firstName = profile.get("firstName");
                    String lastName = profile.get("lastName");
                    // todo move provider to config file
                    Provider provider = new Provider("Lisa", "Cosimi", "1154436111");

                    List<AOE> aoes = AOE.forTestBoston(null, kitId);
                    Patient testPatient = new Patient(patientId, firstName, lastName, "1901-01-01", "Other", "Other",
                            "other", careEvolveAddress);

                    Message message = new Message(new Order(careEvolveAccount, testPatient, kitLabel,provider, aoes), kitId);

                    try {
                        return orderTest(auth, message);
                    } catch (IOException e) {
                        throw new CareEvolveException("Could not order test for " + patientId, e);
                    }
                } else {
                    throw new CareEvolveException("No address for " + participantHruid + ".  Cannot register order.");
                }
            } else {
                throw new CareEvolveException("No participant data for " + participantHruid + ".  Cannot register order.");
            }
        } else {
            throw new CareEvolveException("No particiant data found for " + participantHruid + ".  Cannot register order.");
        }
    }

    private Address toCareEvolveAddress(Map<String, String> esAddress) {
        return new Address(esAddress.get("street1"),
                esAddress.get("street2"),
                esAddress.get("city"),
                esAddress.get("state"),
                esAddress.get("zip"));
    }

    /**
     * Order a test using the given auth and message details.
     * Returns an {@link OrderResponse} when the order has been
     * placed successfully.  Otherwise, an exception is thrown.
     */
    private OrderResponse orderTest(Authentication auth, Message message) throws IOException {
        logger.info("About to send {} as {} to {}", message.getName(), endpoint);
        AuthenticatedMessage careEvolveMessage = new AuthenticatedMessage(auth, message);

        String json = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(careEvolveMessage);

        Response response = Request.Post(endpoint).bodyString(json, ContentType.APPLICATION_JSON).execute();
        HttpResponse httpResponse = response.returnResponse();

        String responseString = EntityUtils.toString(httpResponse.getEntity());

        if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            OrderResponse orderResponse = new Gson().fromJson(IOUtils.toString(httpResponse.getEntity().getContent()), OrderResponse.class);
            return orderResponse;
        } else {
            logger.error("Order {} returned {} with {}", message.getName(), httpResponse.getStatusLine().getStatusCode(), responseString);
            throw new CareEvolveException("CareEvolve returned " + httpResponse.getStatusLine().getStatusCode() + " with " + responseString);
        }
    }
}