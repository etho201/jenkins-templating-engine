package org.boozallen.plugins.jte.utils

import hudson.model.TaskListener
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.SubmoduleConfig
import hudson.plugins.git.extensions.GitSCMExtension
import hudson.scm.SCM
import jenkins.plugins.git.GitSampleRepoRule
import jenkins.scm.api.SCMFileSystem
import org.boozallen.plugins.jte.Utils
import org.boozallen.plugins.jte.testcategories.InProgress
import org.boozallen.plugins.jte.testcategories.Unstable
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.flow.FlowExecution
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.ClassRule
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.jvnet.hudson.test.GroovyJenkinsRule
import org.jvnet.hudson.test.SingleFileSCM
import org.jvnet.hudson.test.WithoutJenkins
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification

class ScmSpec extends Specification {
    @Shared
    @ClassRule
    @SuppressWarnings('JUnitPublicField')
    public GroovyJenkinsRule groovyJenkinsRule = new GroovyJenkinsRule()

    @Shared
    private WorkflowJob project

    @Shared
    @ClassRule GitSampleRepoRule sampleRepo = new GitSampleRepoRule()

    @Shared
    SCM scm = null

    @Shared
    String cpsScriptPath = "cpsScript.groovy"

    @Shared
    String cpsScript = """
               import org.jenkinsci.plugins.workflow.cps.*
               import org.jenkinsci.plugins.workflow.job.*
              
"""
    @Shared
    private WorkflowJob scmWorkflowJob = null

    @Shared WorkflowJob stdWorkflowJob = null

    @Shared
    CpsScmFlowDefinition cpsScmFlowDefinition = null

    def setupSpec(){
        // initialize repository
        sampleRepo.init()


        sampleRepo.write(cpsScriptPath, cpsScript);
        sampleRepo.write("pipeline_config.groovy", """
        libraries{
            openshift{
                url = "whatever" 
            }
        }
        """)
        sampleRepo.git("add", "*")
        sampleRepo.git("commit", "--message=init")
        // create Governance Tier
        scm = new GitSCM(
                GitSCM.createRepoList(sampleRepo.toString(), null),
                Collections.singletonList(new BranchSpec("*/master")),
                false,
                Collections.<SubmoduleConfig>emptyList(),
                null,
                null,
                Collections.<GitSCMExtension>emptyList()
        )

        scmWorkflowJob = groovyJenkinsRule.jenkins.createProject(WorkflowJob, "scmWorkflowJob");
        cpsScmFlowDefinition = new CpsScmFlowDefinition(scm, cpsScriptPath)
        scmWorkflowJob.setDefinition(cpsScmFlowDefinition);

        stdWorkflowJob = groovyJenkinsRule.jenkins.createProject(WorkflowJob, "Utils.FileSystemWrapper.fsFrom !WorkflowMultiBranchProject,!CpsScmFlowDefinition");

        def cpsFlowDef = new CpsFlowDefinition(cpsScript, false)// false is needed to access CpsThread
        stdWorkflowJob.setDefinition(cpsFlowDef);

    }

    def setup(){}


    @Ignore //can't mock SCMFileSystem.of probably because it is Abstract
    @WithoutJenkins
    def "Utils.getSCMFileSystemOrNull(scm,job); SCMFileSystem.of Exception"(){
        given: "mocked job, listener, a valid printstream for 'logger'"

        WorkflowJob job = GroovyMock(WorkflowJob)
        TaskListener listener = GroovyMock(TaskListener)
        ByteArrayOutputStream bs = new ByteArrayOutputStream()
        PrintStream logger = new PrintStream(bs)
        String message = "SCMFileSystem.of Exception"

        GroovyMock(global: true, SCMFileSystem)
        SCMFileSystem.of(job, scm) >> { throw new Exception("message") }

        GroovySpy(Utils.class, global:true)
        _ * Utils.getCurrentJob() >> { return job }
        _ * Utils.getListener() >> {return listener}
        _ * Utils.getLogger() >> {return logger}

        when:"Utils.getSCMFileSystemOrNull is called with the project's job and scm"

        SCMFileSystem scmfs = Utils.getSCMFileSystemOrNull(scm, job)

        then:"it should throw internal exception and be logged"
        notThrown(Exception)
        null == scmfs
        bs.toString("UTF-8") == message
    }


    @WithoutJenkins
    def "Utils.getSCMFileSystemOrNull(scm,job); using Util.currentJob"(){
        given: "a workflowjob project with a valid scm"

        WorkflowJob job = null
        TaskListener listener = null
        PrintStream logger = null

        GroovySpy(Utils.class, global:true)
        _ * Utils.getCurrentJob() >> { return job }
        _ * Utils.getListener() >> {return listener}
        _ * Utils.getLogger() >> {return logger}

        when:"Utils.getSCMFileSystemOrNull is called with the project's job and scm"
        WorkflowRun build = groovyJenkinsRule.buildAndAssertSuccess(scmWorkflowJob);
        FlowExecution execution = build.execution
        listener = execution.owner.listener
        logger = listener.logger
        job = execution.owner.executable.parent

        SCMFileSystem scmfs = Utils.getSCMFileSystemOrNull(scm, Utils.getCurrentJob())

        then:"it should return a valid SCM filesystem"
        notThrown(Exception)
        null != scmfs
    }

    @WithoutJenkins
    def "Utils.createSCMFileSystemOrNull(scm,job); using Util.currentJob"(){
        given: "a workflowjob project with a valid scm; valid Utils CPS properties"

        WorkflowJob job = null
        TaskListener listener = null
        PrintStream logger = null

        GroovySpy(Utils.class, global:true)
        _ * Utils.getCurrentJob() >> { return job }
        _ * Utils.getListener() >> {return listener}
        _ * Utils.getLogger() >> {return logger}

        when:"Utils.createSCMFileSystemOrNull is called with the project's job and scm"
        WorkflowRun build = groovyJenkinsRule.buildAndAssertSuccess(scmWorkflowJob);
        FlowExecution execution = build.execution
        listener = execution.owner.listener
        logger = listener.logger
        job = execution.owner.executable.parent

        SCMFileSystem scmfs = Utils.createSCMFileSystemOrNull(scm, Utils.getCurrentJob(), job.parent)

        then:"it should return a valid SCM filesystem"
        notThrown(Exception)

        null != scmfs
    }

    @WithoutJenkins
    def "Utils.FileSystemWrapper.fsFrom(job, listener, logger) !WorkflowMultiBranchProject"(){

        WorkflowJob job = null
        TaskListener listener = null
        PrintStream logger = null

        GroovySpy(Utils.class, global:true)
        _ * Utils.getCurrentJob() >> { return job }
        _ * Utils.getListener() >> {return listener}
        _ * Utils.getLogger() >> {return logger}

        when:
        WorkflowRun build = groovyJenkinsRule.buildAndAssertSuccess(scmWorkflowJob);
        FlowExecution execution = build.execution
        listener = execution.owner.listener
        logger = listener.logger
        job = execution.owner.executable.parent

        SCMFileSystem scmfs = Utils.FileSystemWrapper.fsFrom(job, listener, logger)

        then:
        notThrown(Exception)

        null != scmfs
    }

    def "Utils.FileSystemWrapper.fsFrom(job, listener, logger) !WorkflowMultiBranchProject,!CpsScmFlowDefinition"(){

        when:
        WorkflowRun build = groovyJenkinsRule.buildAndAssertSuccess(stdWorkflowJob);
        FlowExecution execution = build.execution
        TaskListener listener = execution.owner.listener
        PrintStream logger = listener.logger
        WorkflowJob job = execution.owner.executable.parent

        SCMFileSystem scmfs = Utils.FileSystemWrapper.fsFrom(job, listener, logger)

        then:
        notThrown(Exception)

        null == scmfs
    }

    @Category([InProgress])
    @Ignore
    def "Utils.FileSystemWrapper.fsFrom(job, listener, logger) WorkflowMultiBranchProject"(){
        WorkflowMultiBranchProject wfmbp = groovyJenkinsRule.jenkins.createProject(WorkflowMultiBranchProject, "Utils.FileSystemWrapper.fsFrom !WorkflowMultiBranchProject");

        //wfmbp.
        def cpsFlowDef = new CpsScmFlowDefinition(scm, cpsScriptPath)// false is needed to access CpsThread
        wfmbp.setDefinition(cpsFlowDef);

        WorkflowJob job = null
        TaskListener listener = null
        PrintStream logger = null

        GroovySpy(Utils.class, global:true)
        _ * Utils.getCurrentJob() >> { return job }
        _ * Utils.getListener() >> {return listener}
        _ * Utils.getLogger() >> {return logger}

        when:
        WorkflowRun build = groovyJenkinsRule.buildAndAssertSuccess(wfmbp);
        FlowExecution execution = build.execution
        listener = execution.owner.listener
        logger = listener.logger
        job = execution.owner.executable.parent

        SCMFileSystem scmfs = Utils.FileSystemWrapper.fsFrom(job, listener, logger)

        then:
        notThrown(Exception)

        null != scmfs
    }


    def "Utils.getFileContents"(){

        given:
        WorkflowJob job = null
        TaskListener listener = null
        PrintStream logger = null

        GroovySpy(Utils.class, global:true)
        _ * Utils.getCurrentJob() >> { return job }
        _ * Utils.getListener() >> {return listener}
        _ * Utils.getLogger() >> {return logger}

        when:
        WorkflowRun build = groovyJenkinsRule.buildAndAssertSuccess(scmWorkflowJob);
        FlowExecution execution = build.execution
        listener = execution.owner.listener
        logger = listener.logger
        job = execution.owner.executable.parent

        String output = Utils.getFileContents(cpsScriptPath, scm, "[JTE]")

        then:
        notThrown(Exception)

        output == cpsScript
    }

    def "SCMFileSystem.of; using jenkinsRule"(){// testing that setup of scm is correct

        when:
        WorkflowRun build = groovyJenkinsRule.buildAndAssertSuccess(scmWorkflowJob);
        FlowExecution execution = build.execution
        WorkflowJob job = execution.owner.executable.parent

        SCMFileSystem scmfs = SCMFileSystem.of(job, scm);

        then:
        notThrown(Exception)

        SCMFileSystem.supports(scm)
        null != scmfs
    }

}