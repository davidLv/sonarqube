/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.computation.task.projectanalysis.issue;

import com.google.common.collect.Iterators;
import java.util.Collection;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.issue.Issue;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.Severity;
import org.sonar.api.utils.Duration;
import org.sonar.core.issue.DefaultIssue;
import org.sonar.core.issue.tracking.Input;
import org.sonar.scanner.protocol.Constants;
import org.sonar.scanner.protocol.output.ScannerReport;
import org.sonar.scanner.protocol.output.ScannerReport.TextRange;
import org.sonar.server.computation.task.projectanalysis.batch.BatchReportReaderRule;
import org.sonar.server.computation.task.projectanalysis.component.Component;
import org.sonar.server.computation.task.projectanalysis.component.ReportComponent;
import org.sonar.server.computation.task.projectanalysis.component.TreeRootHolderRule;
import org.sonar.server.computation.task.projectanalysis.issue.commonrule.CommonRuleEngine;
import org.sonar.server.computation.task.projectanalysis.issue.filter.IssueFilter;
import org.sonar.server.computation.task.projectanalysis.qualityprofile.ActiveRule;
import org.sonar.server.computation.task.projectanalysis.qualityprofile.ActiveRulesHolderRule;
import org.sonar.server.computation.task.projectanalysis.source.SourceLinesHashRepository;
import org.sonar.server.rule.CommonRuleKeys;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TrackerRawInputFactoryTest {

  private static int FILE_REF = 2;

  private static ReportComponent PROJECT = ReportComponent.builder(Component.Type.PROJECT, 1).build();
  private static ReportComponent FILE = ReportComponent.builder(Component.Type.FILE, FILE_REF).build();

  @Rule
  public TreeRootHolderRule treeRootHolder = new TreeRootHolderRule().setRoot(PROJECT);
  @Rule
  public BatchReportReaderRule reportReader = new BatchReportReaderRule();
  @Rule
  public ActiveRulesHolderRule activeRulesHolder = new ActiveRulesHolderRule();
  @Rule
  public RuleRepositoryRule ruleRepository = new RuleRepositoryRule();

  private SourceLinesHashRepository sourceLinesHash = mock(SourceLinesHashRepository.class);
  private CommonRuleEngine commonRuleEngine = mock(CommonRuleEngine.class);
  private IssueFilter issueFilter = mock(IssueFilter.class);
  private TrackerRawInputFactory underTest = new TrackerRawInputFactory(treeRootHolder, reportReader, sourceLinesHash,
    commonRuleEngine, issueFilter, ruleRepository, activeRulesHolder);

  @Test
  public void load_source_hash_sequences() {
    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    Input<DefaultIssue> input = underTest.create(FILE);

    assertThat(input.getLineHashSequence()).isNotNull();
    assertThat(input.getLineHashSequence().getHashForLine(1)).isEqualTo("line");
    assertThat(input.getLineHashSequence().getHashForLine(2)).isEmpty();
    assertThat(input.getLineHashSequence().getHashForLine(3)).isEmpty();

    assertThat(input.getBlockHashSequence()).isNotNull();
  }

  @Test
  public void load_source_hash_sequences_only_on_files() {
    Input<DefaultIssue> input = underTest.create(PROJECT);

    assertThat(input.getLineHashSequence()).isNotNull();
    assertThat(input.getBlockHashSequence()).isNotNull();
  }

  @Test
  public void load_issues_from_report() {
    RuleKey ruleKey = RuleKey.of("java", "S001");
    markRuleAsActive(ruleKey);
    when(issueFilter.accept(any(), eq(FILE))).thenReturn(true);

    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    ScannerReport.Issue reportIssue = ScannerReport.Issue.newBuilder()
      .setTextRange(TextRange.newBuilder().setStartLine(2).build())
      .setMsg("the message")
      .setRuleRepository(ruleKey.repository())
      .setRuleKey(ruleKey.rule())
      .setSeverity(Constants.Severity.BLOCKER)
      .setGap(3.14)
      .build();
    reportReader.putIssues(FILE.getReportAttributes().getRef(), singletonList(reportIssue));
    Input<DefaultIssue> input = underTest.create(FILE);

    Collection<DefaultIssue> issues = input.getIssues();
    assertThat(issues).hasSize(1);
    DefaultIssue issue = Iterators.getOnlyElement(issues.iterator());

    // fields set by analysis report
    assertThat(issue.ruleKey()).isEqualTo(ruleKey);
    assertThat(issue.severity()).isEqualTo(Severity.BLOCKER);
    assertThat(issue.line()).isEqualTo(2);
    assertThat(issue.effortToFix()).isEqualTo(3.14);
    assertThat(issue.gap()).isEqualTo(3.14);
    assertThat(issue.message()).isEqualTo("the message");

    // fields set by compute engine
    assertThat(issue.checksum()).isEqualTo(input.getLineHashSequence().getHashForLine(2));
    assertThat(issue.tags()).isEmpty();
    assertInitializedIssue(issue);
    assertThat(issue.debt()).isNull();
  }

  @Test
  public void load_external_issues_from_report() {
    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    ScannerReport.ExternalIssue reportIssue = ScannerReport.ExternalIssue.newBuilder()
      .setTextRange(TextRange.newBuilder().setStartLine(2).build())
      .setMsg("the message")
      .setRuleRepository("eslint")
      .setRuleKey("S001")
      .setSeverity(Constants.Severity.BLOCKER)
      .setEffort(20l)
      .build();
    reportReader.putExternalIssues(FILE.getReportAttributes().getRef(), asList(reportIssue));
    Input<DefaultIssue> input = underTest.create(FILE);

    Collection<DefaultIssue> issues = input.getIssues();
    assertThat(issues).hasSize(1);
    DefaultIssue issue = Iterators.getOnlyElement(issues.iterator());

    // fields set by analysis report
    assertThat(issue.ruleKey()).isEqualTo(RuleKey.of("external_eslint", "S001"));
    assertThat(issue.severity()).isEqualTo(Severity.BLOCKER);
    assertThat(issue.line()).isEqualTo(2);
    assertThat(issue.effort()).isEqualTo(Duration.create(20l));
    assertThat(issue.message()).isEqualTo("the message");

    // fields set by compute engine
    assertThat(issue.checksum()).isEqualTo(input.getLineHashSequence().getHashForLine(2));
    assertThat(issue.tags()).isEmpty();
    assertInitializedExternalIssue(issue);
  }

  @Test
  public void load_external_issues_from_report_with_default_effort() {
    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    ScannerReport.ExternalIssue reportIssue = ScannerReport.ExternalIssue.newBuilder()
      .setTextRange(TextRange.newBuilder().setStartLine(2).build())
      .setMsg("the message")
      .setRuleRepository("eslint")
      .setRuleKey("S001")
      .setSeverity(Constants.Severity.BLOCKER)
      .build();
    reportReader.putExternalIssues(FILE.getReportAttributes().getRef(), asList(reportIssue));
    Input<DefaultIssue> input = underTest.create(FILE);

    Collection<DefaultIssue> issues = input.getIssues();
    assertThat(issues).hasSize(1);
    DefaultIssue issue = Iterators.getOnlyElement(issues.iterator());

    // fields set by analysis report
    assertThat(issue.ruleKey()).isEqualTo(RuleKey.of("external_eslint", "S001"));
    assertThat(issue.severity()).isEqualTo(Severity.BLOCKER);
    assertThat(issue.line()).isEqualTo(2);
    assertThat(issue.effort()).isEqualTo(Duration.create(0l));
    assertThat(issue.message()).isEqualTo("the message");

    // fields set by compute engine
    assertThat(issue.checksum()).isEqualTo(input.getLineHashSequence().getHashForLine(2));
    assertThat(issue.tags()).isEmpty();
    assertInitializedExternalIssue(issue);
  }

  @Test
  public void excludes_issues_on_inactive_rules() {
    RuleKey ruleKey = RuleKey.of("java", "S001");
    when(issueFilter.accept(any(), eq(FILE))).thenReturn(true);

    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    ScannerReport.Issue reportIssue = ScannerReport.Issue.newBuilder()
      .setTextRange(TextRange.newBuilder().setStartLine(2).build())
      .setMsg("the message")
      .setRuleRepository(ruleKey.repository())
      .setRuleKey(ruleKey.rule())
      .setSeverity(Constants.Severity.BLOCKER)
      .setGap(3.14)
      .build();
    reportReader.putIssues(FILE.getReportAttributes().getRef(), singletonList(reportIssue));
    Input<DefaultIssue> input = underTest.create(FILE);

    Collection<DefaultIssue> issues = input.getIssues();
    assertThat(issues).isEmpty();
  }

  @Test
  public void filter_excludes_issues_from_report() {
    RuleKey ruleKey = RuleKey.of("java", "S001");
    markRuleAsActive(ruleKey);
    when(issueFilter.accept(any(), eq(FILE))).thenReturn(false);
    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    ScannerReport.Issue reportIssue = ScannerReport.Issue.newBuilder()
      .setTextRange(TextRange.newBuilder().setStartLine(2).build())
      .setMsg("the message")
      .setRuleRepository(ruleKey.repository())
      .setRuleKey(ruleKey.rule())
      .setSeverity(Constants.Severity.BLOCKER)
      .setGap(3.14)
      .build();
    reportReader.putIssues(FILE.getReportAttributes().getRef(), singletonList(reportIssue));
    Input<DefaultIssue> input = underTest.create(FILE);

    Collection<DefaultIssue> issues = input.getIssues();
    assertThat(issues).isEmpty();
  }

  @Test
  public void exclude_issues_on_common_rules() {
    RuleKey ruleKey = RuleKey.of(CommonRuleKeys.commonRepositoryForLang("java"), "S001");
    markRuleAsActive(ruleKey);
    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    ScannerReport.Issue reportIssue = ScannerReport.Issue.newBuilder()
      .setMsg("the message")
      .setRuleRepository(ruleKey.repository())
      .setRuleKey(ruleKey.rule())
      .setSeverity(Constants.Severity.BLOCKER)
      .build();
    reportReader.putIssues(FILE.getReportAttributes().getRef(), singletonList(reportIssue));

    Input<DefaultIssue> input = underTest.create(FILE);

    assertThat(input.getIssues()).isEmpty();
  }

  @Test
  public void load_issues_of_compute_engine_common_rules() {
    RuleKey ruleKey = RuleKey.of(CommonRuleKeys.commonRepositoryForLang("java"), "InsufficientCoverage");
    markRuleAsActive(ruleKey);
    when(issueFilter.accept(any(), eq(FILE))).thenReturn(true);
    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    DefaultIssue ceIssue = new DefaultIssue()
      .setRuleKey(ruleKey)
      .setMessage("not enough coverage")
      .setGap(10.0);
    when(commonRuleEngine.process(FILE)).thenReturn(singletonList(ceIssue));

    Input<DefaultIssue> input = underTest.create(FILE);

    assertThat(input.getIssues()).containsOnly(ceIssue);
    assertInitializedIssue(input.getIssues().iterator().next());
  }

  @Test
  public void filter_exclude_issues_on_common_rule() {
    RuleKey ruleKey = RuleKey.of(CommonRuleKeys.commonRepositoryForLang("java"), "InsufficientCoverage");
    markRuleAsActive(ruleKey);
    when(issueFilter.accept(any(), eq(FILE))).thenReturn(false);
    when(sourceLinesHash.getLineHashesMatchingDBVersion(FILE)).thenReturn(Collections.singletonList("line"));
    DefaultIssue ceIssue = new DefaultIssue()
      .setRuleKey(ruleKey)
      .setMessage("not enough coverage")
      .setGap(10.0);
    when(commonRuleEngine.process(FILE)).thenReturn(singletonList(ceIssue));

    Input<DefaultIssue> input = underTest.create(FILE);

    assertThat(input.getIssues()).isEmpty();
  }

  private void assertInitializedIssue(DefaultIssue issue) {
    assertThat(issue.projectKey()).isEqualTo(PROJECT.getPublicKey());
    assertThat(issue.componentKey()).isEqualTo(FILE.getPublicKey());
    assertThat(issue.componentUuid()).isEqualTo(FILE.getUuid());
    assertThat(issue.resolution()).isNull();
    assertThat(issue.status()).isEqualTo(Issue.STATUS_OPEN);
    assertThat(issue.key()).isNull();
    assertThat(issue.authorLogin()).isNull();
    assertThat(issue.effort()).isNull();
    assertThat(issue.effortInMinutes()).isNull();
    assertThat(issue.debt()).isNull();
  }

  private void assertInitializedExternalIssue(DefaultIssue issue) {
    assertThat(issue.projectKey()).isEqualTo(PROJECT.getPublicKey());
    assertThat(issue.componentKey()).isEqualTo(FILE.getPublicKey());
    assertThat(issue.componentUuid()).isEqualTo(FILE.getUuid());
    assertThat(issue.resolution()).isNull();
    assertThat(issue.status()).isEqualTo(Issue.STATUS_OPEN);
    assertThat(issue.key()).isNull();
    assertThat(issue.authorLogin()).isNull();
  }

  private void markRuleAsActive(RuleKey ruleKey) {
    activeRulesHolder.put(new ActiveRule(ruleKey, Severity.CRITICAL, emptyMap(), 1_000L, null));
  }
}
