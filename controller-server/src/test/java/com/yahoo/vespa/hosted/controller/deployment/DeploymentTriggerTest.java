// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.config.provision.Environment;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.api.identifiers.TenantId;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobType;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 * @author mpolden
 */
public class DeploymentTriggerTest {

    @Test
    public void testTriggerFailing() {
        DeploymentTester tester = new DeploymentTester();
        Application app = tester.createApplication("app1", "tenant1", 1, 1L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("default")
                .environment(Environment.prod)
                .region("us-west-1")
                .build();

        Version version = new Version(5, 1);
        tester.updateVersionStatus(version);
        tester.upgrader().maintain();

        // Deploy completely once
        tester.notifyJobCompletion(DeploymentJobs.JobType.component, app, true);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.systemTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.stagingTest);
        tester.deployAndNotify(app, applicationPackage, true, JobType.productionUsWest1);

        // New version is released
        version = new Version(5, 2);
        tester.updateVersionStatus(version);
        tester.upgrader().maintain();

        tester.deployAndNotify(app, applicationPackage, false, JobType.systemTest);
        assertEquals("Retried immediately", 1, tester.buildSystem().jobs().size());

        tester.buildSystem().takeJobsToRun();
        assertEquals("Job removed", 0, tester.buildSystem().jobs().size());
        tester.clock().advance(Duration.ofHours(2));
        tester.failureRedeployer().maintain();
        assertEquals("Retried job", 1, tester.buildSystem().jobs().size());
        assertEquals(JobType.systemTest.id(), tester.buildSystem().jobs().get(0).jobName());

        tester.buildSystem().takeJobsToRun();
        assertEquals("Job removed", 0, tester.buildSystem().jobs().size());
        tester.clock().advance(Duration.ofHours(12).plus(Duration.ofSeconds(1)));
        tester.failureRedeployer().maintain();
        assertEquals("Retried from the beginning", 1, tester.buildSystem().jobs().size());
        assertEquals(JobType.component.id(), tester.buildSystem().jobs().get(0).jobName());
    }

    @Test
    public void deploymentSpecDecidesTriggerOrder() {
        DeploymentTester tester = new DeploymentTester();
        BuildSystem buildSystem = tester.buildSystem();
        TenantId tenant = tester.controllerTester().createTenant("tenant1", "domain1", 1L);
        Application application = tester.controllerTester().createApplication(tenant, "app1", "default", 1L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("corp-us-east-1")
                .region("us-central-1")
                .region("us-west-1")
                .build();

        // Component job finishes
        tester.notifyJobCompletion(JobType.component, application, true);

        // Application is deployed to all test environments and declared zones
        tester.deployAndNotify(application, applicationPackage, true, JobType.systemTest);
        tester.deployAndNotify(application, applicationPackage, true, JobType.stagingTest);
        tester.deployAndNotify(application, applicationPackage, true, JobType.productionCorpUsEast1);
        tester.deployAndNotify(application, applicationPackage, true, JobType.productionUsCentral1);
        tester.deployAndNotify(application, applicationPackage, true, JobType.productionUsWest1);
        assertTrue("All jobs consumed", buildSystem.jobs().isEmpty());
    }

    @Test
    public void deploymentsSpecWithDelays() {
        DeploymentTester tester = new DeploymentTester();
        BuildSystem buildSystem = tester.buildSystem();
        Application application = tester.createApplication("app1", "tenant1", 1, 1L);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .delay(Duration.ofSeconds(30))
                .region("us-west-1")
                .delay(Duration.ofMinutes(1))
                .delay(Duration.ofMinutes(2)) // Multiple delays are summed up
                .region("us-central-1")
                .delay(Duration.ofMinutes(10)) // Delays after last region are valid, but have no effect
                .build();

        // Component job finishes
        tester.notifyJobCompletion(JobType.component, application, true);

        // Test jobs pass
        tester.deployAndNotify(application, applicationPackage, true, JobType.systemTest);
        tester.clock().advance(Duration.ofSeconds(1)); // Make staging test sort as the last successful job
        tester.deployAndNotify(application, applicationPackage, true, JobType.stagingTest);
        assertTrue("No more jobs triggered at this time", buildSystem.jobs().isEmpty());

        // 30 seconds pass, us-west-1 is triggered
        tester.clock().advance(Duration.ofSeconds(30));
        tester.deploymentTrigger().triggerDelayed();

        // Consume us-west-1 job without reporting completion
        assertEquals(1, buildSystem.jobs().size());
        assertEquals(JobType.productionUsWest1.id(), buildSystem.jobs().get(0).jobName());
        buildSystem.takeJobsToRun();

        // 3 minutes pass, delayed trigger does nothing as us-west-1 is still in progress
        tester.clock().advance(Duration.ofMinutes(3));
        tester.deploymentTrigger().triggerDelayed();
        assertTrue("No more jobs triggered at this time", buildSystem.jobs().isEmpty());

        // us-west-1 completes
        tester.deploy(JobType.productionUsWest1, application, applicationPackage);
        tester.notifyJobCompletion(JobType.productionUsWest1, application, true);

        // Delayed trigger does nothing as not enough time has passed after us-west-1 completion
        tester.deploymentTrigger().triggerDelayed();
        assertTrue("No more jobs triggered at this time", buildSystem.jobs().isEmpty());

        // 3 minutes pass, us-central-1 is triggered
        tester.clock().advance(Duration.ofMinutes(3));
        tester.deploymentTrigger().triggerDelayed();
        tester.deployAndNotify(application, applicationPackage, true, JobType.productionUsCentral1);
        assertTrue("All jobs consumed", buildSystem.jobs().isEmpty());

        // Delayed trigger job runs again, with nothing to trigger
        tester.clock().advance(Duration.ofMinutes(10));
        tester.deploymentTrigger().triggerDelayed();
        assertTrue("All jobs consumed", buildSystem.jobs().isEmpty());
    }

    @Test
    public void deploymentSpecWithParallelDeployments() {
        DeploymentTester tester = new DeploymentTester();
        Application application = tester.createApplication("app1", "tenant1", 1, 1L);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-central-1")
                .parallel("us-west-1", "us-east-3")
                .region("eu-west-1")
                .build();

        // Component job finishes
        tester.notifyJobCompletion(JobType.component, application, true);

        // Test jobs pass
        tester.deployAndNotify(application, applicationPackage, true, JobType.systemTest);
        tester.deployAndNotify(application, applicationPackage, true, JobType.stagingTest);

        // Deploys in first region
        assertEquals(1, tester.buildSystem().jobs().size());
        tester.deployAndNotify(application, applicationPackage, true, JobType.productionUsCentral1);

        // Deploys in two regions in parallel
        assertEquals(2, tester.buildSystem().jobs().size());
        assertEquals(JobType.productionUsEast3.id(), tester.buildSystem().jobs().get(0).jobName());
        assertEquals(JobType.productionUsWest1.id(), tester.buildSystem().jobs().get(1).jobName());
        tester.buildSystem().takeJobsToRun();

        tester.deploy(JobType.productionUsWest1, application, applicationPackage, false);
        tester.notifyJobCompletion(JobType.productionUsWest1, application, true);
        assertTrue("No more jobs triggered at this time", tester.buildSystem().jobs().isEmpty());

        tester.deploy(JobType.productionUsEast3, application, applicationPackage, false);
        tester.notifyJobCompletion(JobType.productionUsEast3, application, true);

        // Last region completes
        assertEquals(1, tester.buildSystem().jobs().size());
        tester.deployAndNotify(application, applicationPackage, true, JobType.productionEuWest1);
        assertTrue("All jobs consumed", tester.buildSystem().jobs().isEmpty());
    }

    @Test
    public void parallelDeploymentCompletesOutOfOrder() {
        DeploymentTester tester = new DeploymentTester();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .parallel("us-east-3", "us-west-1")
                .build();

        Application app = tester.createApplication("app1", "tenant1", 1, 11L);
        tester.notifyJobCompletion(DeploymentJobs.JobType.component, app, true);

        // Test environments pass
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.systemTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.stagingTest);

        // Parallel deployment
        tester.deploy(DeploymentJobs.JobType.productionUsWest1, app, applicationPackage);
        tester.deploy(DeploymentJobs.JobType.productionUsEast3, app, applicationPackage);

        // Last declared job completes first
        tester.notifyJobCompletion(DeploymentJobs.JobType.productionUsWest1, app, true);
        assertTrue("Change is present as not all jobs are complete",
                   tester.applications().require(app.id()).deploying().isPresent());

        // All jobs complete
        tester.notifyJobCompletion(DeploymentJobs.JobType.productionUsEast3, app, true);
        assertFalse("Change has been deployed",
                    tester.applications().require(app.id()).deploying().isPresent());
    }

    @Test
    public void testSuccessfulDeploymentApplicationPackageChanged() {
        DeploymentTester tester = new DeploymentTester();
        BuildSystem buildSystem = tester.buildSystem();
        TenantId tenant = tester.controllerTester().createTenant("tenant1", "domain1", 1L);
        Application application = tester.controllerTester().createApplication(tenant, "app1", "default", 1L);
        ApplicationPackage previousApplicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("corp-us-east-1")
                .region("us-central-1")
                .region("us-west-1")
                .build();
        ApplicationPackage newApplicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("corp-us-east-1")
                .region("us-central-1")
                .region("us-west-1")
                .region("ap-northeast-1")
                .build();

        // Component job finishes
        tester.notifyJobCompletion(JobType.component, application, true);

        // Application is deployed to all test environments and declared zones
        tester.deployAndNotify(application, newApplicationPackage, true, JobType.systemTest);
        tester.deploy(JobType.stagingTest, application, previousApplicationPackage, true);
        tester.deployAndNotify(application, newApplicationPackage, true, JobType.stagingTest);
        tester.deployAndNotify(application, newApplicationPackage, true, JobType.productionCorpUsEast1);
        tester.deployAndNotify(application, newApplicationPackage, true, JobType.productionUsCentral1);
        tester.deployAndNotify(application, newApplicationPackage, true, JobType.productionUsWest1);
        tester.deployAndNotify(application, newApplicationPackage, true, JobType.productionApNortheast1);
        assertTrue("All jobs consumed", buildSystem.jobs().isEmpty());
    }

    @Test
    public void testHandleMultipleNotificationsFromLastJob() {
        DeploymentTester tester = new DeploymentTester();
        BuildSystem buildSystem = tester.buildSystem();
        TenantId tenant = tester.controllerTester().createTenant("tenant1", "domain1", 1L);
        Application application = tester.controllerTester().createApplication(tenant, "app1", "default", 1L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("corp-us-east-1")
                .build();

        // Component job finishes
        tester.notifyJobCompletion(JobType.component, application, true);

        // Application is deployed to all test environments and declared zones
        tester.deployAndNotify(application, applicationPackage, true, JobType.systemTest);
        tester.deployAndNotify(application, applicationPackage, true, JobType.stagingTest);
        tester.deployAndNotify(application, applicationPackage, true, JobType.productionCorpUsEast1);

        // Extra notification for last job
        tester.notifyJobCompletion(JobType.productionCorpUsEast1, application, true);
        assertFalse("Change has been deployed",
                    tester.applications().require(application.id()).deploying().isPresent());
        assertTrue("All jobs consumed", buildSystem.jobs().isEmpty());
    }
}
