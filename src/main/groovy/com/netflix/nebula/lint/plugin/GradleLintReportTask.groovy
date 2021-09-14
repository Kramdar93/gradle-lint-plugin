/*
 * Copyright 2015-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package com.netflix.nebula.lint.plugin

import com.netflix.nebula.interop.GradleKt
import com.netflix.nebula.lint.GradleLintPatchAction
import com.netflix.nebula.lint.StyledTextService
import com.netflix.nebula.lint.utils.DeprecationLoggerUtils
import org.codenarc.AnalysisContext
import org.codenarc.report.HtmlReportWriter
import org.codenarc.report.ReportWriter
import org.codenarc.report.TextReportWriter
import org.codenarc.report.XmlReportWriter
import org.codenarc.results.Results
import org.codenarc.rule.Violation
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.plugins.quality.CodeNarcReports
import org.gradle.api.plugins.quality.internal.CodeNarcReportsImpl
import org.gradle.api.reporting.Report
import org.gradle.api.reporting.Reporting
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.VerificationTask
import org.gradle.internal.reflect.Instantiator
import org.gradle.util.GradleVersion

import javax.inject.Inject

import static com.netflix.nebula.lint.StyledTextService.Styling.Bold

class GradleLintReportTask extends DefaultTask implements VerificationTask, Reporting<CodeNarcReports> {

    @Nested
    private final CodeNarcReportsImpl reports
    @Input
    boolean reportOnlyFixableViolations

    /**
     * Whether or not the build should break when the verifications performed by this task fail.
     */
    boolean ignoreFailures

    GradleLintReportTask() {
        CodeNarcReportsImpl codeNarcReports
        if (GradleVersion.version(project.gradle.gradleVersion).compareTo(GradleVersion.version('4.4.1')) > 0) {
            codeNarcReports = project.objects.newInstance(CodeNarcReportsImpl.class, this)
        } else {
            //TODO: remove this once we don't have customers in Gradle 4.1
            DeprecationLoggerUtils.whileDisabled() {
                codeNarcReports = instantiator.newInstance(CodeNarcReportsImpl, this)
            }
        }
        reports = codeNarcReports
        outputs.upToDateWhen { false }
        group = 'lint'
    }

    @TaskAction
    void generateReport() {
        if (reports.enabled) {
            def lintService = new LintService()
            def results = lintService.lint(project, false)
            filterOnlyFixableViolations(results)
            def violationCount = results.violations.size()
            def textOutput = new StyledTextService(getServices())

            textOutput.text('Generated a report containing information about ')
            textOutput.withStyle(Bold).text("$violationCount lint violation${violationCount == 1 ? '' : 's'}")
            textOutput.println(' in this project')

            reports.enabled.each { Report r ->
                ReportWriter writer = null

                if (GradleKt.versionCompareTo(project.gradle, '7.1') >= 0) {
                    switch (r.name) {
                        case 'xml': writer = new XmlReportWriter(outputFile: r.outputLocation.get().asFile); break
                        case 'html': writer = new HtmlReportWriter(outputFile: r.outputLocation.get().asFile); break
                        case 'text': writer = new TextReportWriter(outputFile: r.outputLocation.get().asFile); break
                    }
                } else {
                    switch (r.name) {
                        case 'xml': writer = new XmlReportWriter(outputFile: r.destination); break
                        case 'html': writer = new HtmlReportWriter(outputFile: r.destination); break
                        case 'text': writer = new TextReportWriter(outputFile: r.destination); break
                    }
                }

                writer.writeReport(new AnalysisContext(ruleSet: lintService.ruleSet(project)), results)
            }

            int errors = results.violations.count { Violation v -> v.rule.priority == 1 }
            if (errors > 0) {
                throw new GradleException("This build contains $errors critical lint violation${errors == 1 ? '' : 's'}")
            }
        }

    }

    @Inject
    Instantiator getInstantiator() {
        null // see http://gradle.1045684.n5.nabble.com/injecting-dependencies-into-task-instances-td5712637.html
    }

    /**
     * Returns the reports to be generated by this task.
     */
    @Override
    CodeNarcReports getReports() {
        reports
    }

    /**
     * Configures the reports to be generated by this task.
     */
    @Override
    CodeNarcReports reports(Closure closure) {
        reports.configure(closure)
    }

    CodeNarcReports reports(Action<? super CodeNarcReports> action) {
        return action.execute(reports)
    }

    void filterOnlyFixableViolations(Results results) {
        if (reportOnlyFixableViolations) {
            new GradleLintPatchAction(project).lintFinished(results.violations)
            List<Violation> toRemove = results.violations.findAll {
                it.fixes.size == 0 || it.fixes.any { it.reasonForNotFixing != null }
            }
            toRemove.each {
                results.removeViolation(it)
            }
        }
    }
}
