/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.github_branch_source;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.LogTaskListener;
import jenkins.branch.BranchProjectFactory;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.github.*;
import org.kohsuke.github.GHEventPayload.Push;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.PUSH;

/**
 * This subscriber manages {@link GHEvent} PULL_REQUEST.
 */
@Extension
public class PushGHEventSubscriber extends AbstractGHEventSubscriber {
    private static final Logger LOGGER = Logger.getLogger(PushGHEventSubscriber.class.getName());

    /**
     * @return set with only PULL_REQUEST event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PUSH);
    }

    protected boolean doUpdateFromEvent(String payload, JSONObject json, WorkflowMultiBranchProject owner, GitHubSCMSource source) {

        LOGGER.log(Level.INFO, "Doing update from a push event: {0}", new Object[] { payload });

        GitHub github;
        Push push;
        GHRepository repository;

        try {
            github = source.getGitHub();
            push = getPush(payload, github);
            repository = push.getRepository();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IOException during push webhook update: {0}", e);
            return false;
        }

        String name = push.getRef();
        String sha1 = push.getHead();

        if (name.startsWith("refs/heads/")) {
            name = name.substring("refs/heads/".length());
        }

        Set<String> originBranchesWithPR = new HashSet<>();
        TaskListener listener = new LogTaskListener(LOGGER, Level.INFO);

        SCMRevision revision;

        try {
            revision = source.generateRevisionForBranch(name, sha1, repository, originBranchesWithPR, listener);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IOException during push webhook generate revision: {0}", new Object[] { e });
            return false;
        }

        if (revision == null) {
            LOGGER.log(Level.INFO, "Skipping branch {0} -> {1} because source told us to", new Object[] { name, sha1 });
            return false;
        }

        LOGGER.log(Level.FINE, "About to schedule build: {0} {1} {2}", new Object[] { owner.getName(), revision, name });
        return scheduleBuild(owner, revision, name);
    }
}
