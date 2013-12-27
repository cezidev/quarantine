package org.jenkinsci.plugins.quarantine;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.CheckPoint;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.tasks.Publisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.TestResultAction.Data;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.util.DescribableList;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QuarantinableJUnitResultArchiver extends JUnitResultArchiver {

   private static final CheckPoint CHECKPOINT = new CheckPoint("JUnit result archiving");

   @DataBoundConstructor
   public QuarantinableJUnitResultArchiver(String testResults, boolean keepLongStdio,
         DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> testDataPublishers) {
      super(testResults, keepLongStdio, testDataPublishers);
   }

   /**
    * Because build results can only be made worse, we can't just run another
    * recorder straight after the JUnitResultArchiver. So we clone-and-own the
    * {@link JUnitResultArchiver#perform(AbstractBuild, Launcher, BuildListener)}
    * method here so we can inspect the quarantine before making the PASS/FAIL
    * decision
    *
    * The build is only failed if there are failing tests that have not been put
    * in quarantine
    */
   @Override
   public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException,
         IOException {
      listener.getLogger().println(hudson.tasks.junit.Messages.JUnitResultArchiver_Recording());
      TestResultAction action;

      final String testResults = build.getEnvironment(listener).expand(this.getTestResults());

      try {
         TestResult result = parse(testResults, build, launcher, listener);

         try {
            action = new TestResultAction(build, result, listener);
         } catch (NullPointerException npe) {
            throw new AbortException(hudson.tasks.junit.Messages.JUnitResultArchiver_BadXML(testResults));
         }
         result.freeze(action);
         if (result.getPassCount() == 0 && result.getFailCount() == 0)
            throw new AbortException(hudson.tasks.junit.Messages.JUnitResultArchiver_ResultIsEmpty());

         // TODO: Move into JUnitParser [BUG 3123310]
         List<Data> data = new ArrayList<Data>();
         if (getTestDataPublishers() != null) {
            for (TestDataPublisher tdp : getTestDataPublishers()) {
               Data d = tdp.getTestData(build, launcher, listener, result);
               if (d != null) {
                  data.add(d);
               }
            }
         }

         action.setData(data);

         CHECKPOINT.block();

      } catch (AbortException e) {
         if (build.getResult() == Result.FAILURE)
            // most likely a build failed before it gets to the test phase.
            // don't report confusing error message.
            return true;

         listener.getLogger().println(e.getMessage());
         build.setResult(Result.FAILURE);
         return true;
      } catch (IOException e) {
         e.printStackTrace(listener.error("Failed to archive test reports"));
         build.setResult(Result.FAILURE);
         return true;
      }

      build.getActions().add(action);
      CHECKPOINT.report();

      if (action.getResult().getFailCount() > 0) {
         int quarantined = 0;
         for (CaseResult result : action.getResult().getFailedTests()) {
            QuarantineTestAction quarantineAction = result.getTestAction(QuarantineTestAction.class);
            if (quarantineAction != null) {
               if (quarantineAction.isQuarantined()) {
                  listener.getLogger().println("[Quarantine]: " + result.getFullName() + " failed but is quarantined");
                  quarantined++;
               }
            }
         }

         int remaining = action.getResult().getFailCount() - quarantined;
         listener.getLogger().println("[Quarantine]: " + remaining + " unquarantined failures remaining");

         if (remaining > 0)
            build.setResult(Result.UNSTABLE);
      }

      return true;
   }

   @Extension
   public static class DescriptorImpl extends JUnitResultArchiver.DescriptorImpl {
      public String getDisplayName() {
         return Messages.QuarantinableJUnitResultArchiver_DisplayName();
      }

      @Override
      public Publisher newInstance(StaplerRequest req, JSONObject formData)
            throws hudson.model.Descriptor.FormException {
         String testResults = formData.getString("testResults");
         boolean keepLongStdio = formData.getBoolean("keepLongStdio");
         DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> testDataPublishers = new DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>>(
               Saveable.NOOP);
         try {
            testDataPublishers.rebuild(req, formData, TestDataPublisher.all());
         } catch (IOException e) {
            throw new FormException(e, null);
         }
         return new QuarantinableJUnitResultArchiver(testResults, keepLongStdio, testDataPublishers);
      }

   }
}
