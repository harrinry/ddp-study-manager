package org.broadinstitute.dsm.route;

import com.google.gson.Gson;
import org.broadinstitute.ddp.handlers.util.Result;
import org.broadinstitute.dsm.db.Drug;
import org.broadinstitute.dsm.security.RequestHandler;
import org.broadinstitute.dsm.statics.RoutePath;
import org.broadinstitute.dsm.statics.UserErrorMessages;
import org.broadinstitute.dsm.util.UserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Route;

public class DrugListingRoute extends RequestHandler implements Route {

    private static final Logger logger = LoggerFactory.getLogger(DrugListingRoute.class);

    @Override
    public Object processRequest(Request request, Response response, String userId) throws Exception {
        if (RoutePath.RequestMethod.GET.toString().equals(request.requestMethod())) {
                return Drug.getDrugListings();
        }
        if (RoutePath.RequestMethod.PATCH.toString().equals(request.requestMethod())) {
            if (UserUtil.checkUserAccess(null, userId, "drug_list_edit")) {
                String requestBody = request.body();
                Drug[] drugUpdateValues = new Gson().fromJson(requestBody, Drug[].class);
                Drug.updateDrugListing(drugUpdateValues);
                return new Result(200);
            }
            else {
                response.status(500);
                return new Result(500, UserErrorMessages.NO_RIGHTS);
            }
        }
        logger.error("Request method not known");
        return new Result(500, UserErrorMessages.CONTACT_DEVELOPER);
    }
}
