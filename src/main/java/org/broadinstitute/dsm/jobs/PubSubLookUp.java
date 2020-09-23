package org.broadinstitute.dsm.jobs;

import com.google.gson.Gson;
import com.google.pubsub.v1.PubsubMessage;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.ddp.db.SimpleResult;
import org.broadinstitute.dsm.model.KitDDPNotification;
import org.broadinstitute.dsm.model.birch.DSMTestResult;
import org.broadinstitute.dsm.model.birch.TestBostonResult;
import org.broadinstitute.dsm.statics.DBConstants;
import org.broadinstitute.dsm.util.EventUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;

import static org.broadinstitute.ddp.db.TransactionWrapper.inTransaction;

public class PubSubLookUp {
    private static final Logger logger = LoggerFactory.getLogger(PubSubLookUp.class);
    private static String SELECT_LATEST_RESULT_QUERY = "SELECT kit.test_result FROM ddp_kit_request req LEFT JOIN  ddp_kit kit ON (kit.dsm_kit_request_id = req.dsm_kit_request_id) " +
            "WHERE kit.kit_label = ?"; //todo pegah change this for prod to kit_labe
    private static String UPDATE_TEST_RESULT = "UPDATE ddp_kit SET  test_result = ? WHERE dsm_kit_id <> 0 and  dsm_kit_request_id  in (select  dsm_kit_request_id from (select * from ddp_kit as something where something.kit_label = ? ) as t  )";

    public static void processCovidTestResults(PubsubMessage message) {
        String data = message.getData().toStringUtf8();
        TestBostonResult testBostonResult = new Gson().fromJson(data, TestBostonResult.class);
        logger.info("Processing test results for " + testBostonResult.getSampleId());
        if (shouldWriteResultIntoDB(testBostonResult)) {
            writeResultsIntoDB(testBostonResult);
            tellPepperAboutTheNewResults(testBostonResult);// notify pepper if we update DB
        }
    }

    private static boolean shouldWriteResultIntoDB(TestBostonResult testBostonResult) {
        logger.info("checking to see if we  should write the new test result");
        DSMTestResult[] dsmTestResultArray = getLatestKitTestResults(testBostonResult);
        if (dsmTestResultArray == null || dsmTestResultArray.length == 0) {
            return true;
        }
        DSMTestResult dsmTestResult = null;
        dsmTestResult = dsmTestResultArray[dsmTestResultArray.length - 1];

        //corrected result -> assuming corrected results are always changed
        if (dsmTestResult != null && !testBostonResult.isCorrected() && dsmTestResult.isCorrected()) {
            return false;//should we error?
        }
        //duplicate result
        if (testBostonResult.isCorrected() == dsmTestResult.isCorrected()
                && StringUtils.isNotBlank(dsmTestResult.getResult()) && dsmTestResult.getResult().equals(testBostonResult.getResult())
                && StringUtils.isNotBlank(dsmTestResult.getTimeCompleted()) && dsmTestResult.getTimeCompleted().equals(testBostonResult.getTimeCompleted())) {
            return false;
        }
        // weird result
        if (!testBostonResult.isCorrected()
                && StringUtils.isNotBlank(dsmTestResult.getResult()) && !dsmTestResult.getResult().equals(testBostonResult.getResult())) {

            throw new RuntimeException("A new result for sample id " + testBostonResult.getSampleId() + " that doesn't match the previous one. Date of the new result: " + testBostonResult.getTimeCompleted());
        }
        return true;
    }

    public static void tellPepperAboutTheNewResults(TestBostonResult testBostonResult) {
        logger.info("Going to Notify Pepper");
        String query = "select eve.event_name, eve.event_type, request.ddp_participant_id, request.dsm_kit_request_id, realm.ddp_instance_id, realm.instance_name, realm.base_url, realm.auth0_token,   realm.notification_recipients,   realm.migrated_ddp,   kit.receive_date,   kit.scan_date   from   ddp_kit_request request,   " +
                "ddp_kit kit,   event_type eve,   ddp_instance realm   where request.dsm_kit_request_id = kit.dsm_kit_request_id   and request.ddp_instance_id = realm.ddp_instance_id   " +
                " and (eve.ddp_instance_id = request.ddp_instance_id   and eve.kit_type_id = request.kit_type_id)   and eve.event_type = \"RESULT\" " + // that's the change from the original query
                " and kit.kit_label = ?"; // that's the change from the original query

        KitDDPNotification kitDDPNotification = KitDDPNotification.getKitDDPNotification(query, testBostonResult.getSampleId(), 1);
        if (kitDDPNotification != null) {
            EventUtil.triggerDDPWithTestResult(kitDDPNotification, testBostonResult);
            logger.info("Notified Pepper with test result notification");
        }
        else {
            throw new RuntimeException("kitDDPNotification was null for kitLabel "+testBostonResult.getOrderMessageId());
        }
    }

    private static DSMTestResult[] getLatestKitTestResults(TestBostonResult testBostonResult) {
        String kitLabel = testBostonResult.getSampleId();
        SimpleResult results = inTransaction((conn) -> {
            SimpleResult dbVals = new SimpleResult();
            try (PreparedStatement stmt = conn.prepareStatement(SELECT_LATEST_RESULT_QUERY)) {
                stmt.setString(1, kitLabel);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String resultArray = rs.getString(DBConstants.KIT_TEST_RESULT);
                        DSMTestResult[] dsmTestResultsArray = new Gson().fromJson(resultArray, DSMTestResult[].class);
                        dbVals.resultValue = dsmTestResultsArray;
                    }
                }
                catch (SQLException ex) {
                    dbVals.resultException = ex;
                }
            }
            catch (SQLException ex) {
                dbVals.resultException = ex;
            }
            return dbVals;
        });

        if (results.resultException != null) {
            throw new RuntimeException("", results.resultException);
        }
        DSMTestResult[] dsmTestResult = (DSMTestResult[]) results.resultValue;
        return dsmTestResult;
    }

    public static void writeResultsIntoDB(TestBostonResult testBostonResult) {
        DSMTestResult[] dsmTestResultArray = getLatestKitTestResults(testBostonResult);
        DSMTestResult[] array = null;
        DSMTestResult newDsmTestResult = new DSMTestResult(testBostonResult.getResult(), testBostonResult.getTimeCompleted(), testBostonResult.isCorrected());
        if (dsmTestResultArray == null) {
            array = new DSMTestResult[] { newDsmTestResult };
        }
        else {
            array = Arrays.copyOf(dsmTestResultArray, dsmTestResultArray.length + 1); //create new array from old array and allocate one more element
            array[array.length - 1] = newDsmTestResult;
        }
        String finalArray = new Gson().toJson(array);
        SimpleResult results = inTransaction((conn) -> {
            SimpleResult dbVals = new SimpleResult();
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_TEST_RESULT)) {
                if (StringUtils.isNotBlank(testBostonResult.getResult())
                        && StringUtils.isNotBlank(testBostonResult.getTimeCompleted())
                        && StringUtils.isNotBlank(testBostonResult.getSampleId())) {
                    stmt.setString(1, finalArray);
                    stmt.setString(2, testBostonResult.getSampleId());
                    int result = stmt.executeUpdate();
                    if (result != 1) {
                        throw new RuntimeException("Error updating test result for kit label" + testBostonResult.getSampleId() + ", returned " + result + " rows");
                    }
                    else {
                        logger.info("Updated test result for kit with kit label " + testBostonResult.getSampleId() + " to " + testBostonResult.getResult());
                    }
                }
            }
            catch (SQLException ex) {
                dbVals.resultException = ex;
            }
            return dbVals;
        });

        if (results.resultException != null) {
            throw new RuntimeException("Couldn't update the test results for kit label" + testBostonResult.getSampleId(), results.resultException);
        }
        else {
            logger.info("Updated test result for kit with external id " + testBostonResult.getSampleId());
        }

    }
}