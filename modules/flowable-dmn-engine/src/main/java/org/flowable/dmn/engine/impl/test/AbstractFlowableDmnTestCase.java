/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.dmn.engine.impl.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flowable.dmn.api.DmnHistoryService;
import org.flowable.dmn.api.DmnManagementService;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.dmn.api.DmnRuleService;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.dmn.engine.DmnEngineConfiguration;
import org.flowable.dmn.engine.test.DmnTestHelper;
import org.junit.Assert;

import junit.framework.AssertionFailedError;

/**
 * @author Tom Baeyens
 * @author Joram Barrez
 */
public abstract class AbstractFlowableDmnTestCase extends AbstractDmnTestCase {

    private static final List<String> TABLENAMES_EXCLUDED_FROM_DB_CLEAN_CHECK = new ArrayList<>();

    static {
        TABLENAMES_EXCLUDED_FROM_DB_CLEAN_CHECK.add("ACT_DMN_DATABASECHANGELOG");
        TABLENAMES_EXCLUDED_FROM_DB_CLEAN_CHECK.add("ACT_DMN_DATABASECHANGELOGLOCK");
    }

    protected DmnEngine dmnEngine;

    protected String deploymentIdFromDeploymentAnnotation;
    protected List<String> deploymentIdsForAutoCleanup = new ArrayList<>();
    protected Throwable exception;

    protected DmnEngineConfiguration dmnEngineConfiguration;
    protected DmnManagementService managementService;
    protected DmnRepositoryService repositoryService;
    protected DmnRuleService ruleService;
    protected DmnHistoryService historyService;

    protected abstract void initializeDmnEngine();

    // Default: do nothing
    protected void closeDownDmnEngine() {
    }

    protected void nullifyServices() {
        dmnEngineConfiguration = null;
        managementService = null;
        repositoryService = null;
        ruleService = null;
        historyService = null;
    }

    @Override
    public void runBare() throws Throwable {
        initializeDmnEngine();
        if (repositoryService == null) {
            initializeServices();
        }

        try {

            deploymentIdFromDeploymentAnnotation = DmnTestHelper.annotationDeploymentSetUp(dmnEngine, getClass(), getName());

            super.runBare();

        } catch (AssertionFailedError e) {
            LOGGER.error(EMPTY_LINE);
            LOGGER.error("ASSERTION FAILED: {}", e, e);
            exception = e;
            throw e;

        } catch (Throwable e) {
            LOGGER.error(EMPTY_LINE);
            LOGGER.error("EXCEPTION: {}", e, e);
            exception = e;
            throw e;

        } finally {

            if (deploymentIdFromDeploymentAnnotation != null) {
                DmnTestHelper.annotationDeploymentTearDown(dmnEngine, deploymentIdFromDeploymentAnnotation, getClass(), getName());
                deploymentIdFromDeploymentAnnotation = null;
            }

            for (String autoDeletedDeploymentId : deploymentIdsForAutoCleanup) {
                repositoryService.deleteDeployment(autoDeletedDeploymentId);
            }
            deploymentIdsForAutoCleanup.clear();

            assertAndEnsureCleanDb();
            dmnEngineConfiguration.getClock().reset();

            // Can't do this in the teardown, as the teardown will be called as part of the super.runBare
            closeDownDmnEngine();
        }
    }

    /**
     * Each test is assumed to clean up all DB content it entered. After a test method executed, this method scans all tables to see if the DB is completely clean. It throws AssertionFailed in case
     * the DB is not clean. If the DB is not clean, it is cleaned by performing a create a drop.
     */
    protected void assertAndEnsureCleanDb() throws Throwable {
        LOGGER.debug("verifying that db is clean after test");
        Map<String, Long> tableCounts = managementService.getTableCount();
        StringBuilder outputMessage = new StringBuilder();
        for (String tableName : tableCounts.keySet()) {
            String tableNameWithoutPrefix = tableName.replace(dmnEngineConfiguration.getDatabaseTablePrefix(), "");
            if (!TABLENAMES_EXCLUDED_FROM_DB_CLEAN_CHECK.contains(tableNameWithoutPrefix)) {
                Long count = tableCounts.get(tableName);
                if (count != 0L) {
                    outputMessage.append("  ").append(tableName).append(": ").append(count).append(" record(s) ");
                }
            }
        }

        if (outputMessage.length() > 0) {
            outputMessage.insert(0, "DB NOT CLEAN: \n");
            LOGGER.error(EMPTY_LINE);
            LOGGER.error(outputMessage.toString());

            LOGGER.info("dropping and recreating db");

            if (exception != null) {
                throw exception;
            } else {
                Assert.fail(outputMessage.toString());
            }
        } else {
            LOGGER.info("database was clean");
        }
    }

    protected void initializeServices() {
        dmnEngineConfiguration = dmnEngine.getDmnEngineConfiguration();
        managementService = dmnEngine.getDmnManagementService();
        repositoryService = dmnEngine.getDmnRepositoryService();
        ruleService = dmnEngine.getDmnRuleService();
        historyService = dmnEngine.getDmnHistoryService();
    }

}
