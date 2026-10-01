package cq.server

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID

/** Authored input and output examples for every dispatchable role mode, written once as values of the generated model types.
  * They follow one small project (a `greet` command line tool that mishandles blank names) through each kind of child, so the
  * results of earlier children appear as the prior result of later ones. Update them whenever the model changes: a changed type
  * stops compilation, and the catalog tests validate every serialized example against its generated schema. */
object AgentExamples {
  private def uuid(number: Int): UUID = UUID.fromString(f"00000000-0000-4000-8000-$number%012d")
  private val Project = ProjectId(uuid(1))
  private val Operator = Actor("operator", SessionId(uuid(2)), Role.Human)
  private val Governor = Actor("CQ governor", SessionId(uuid(3)), Role.Governor)
  private val ReceivedAt = 1790000000000L
  private val Base = GitCommit("3f2a9c1d5e7b8a6f4c2d1e0f9a8b7c6d5e4f3a2b")
  private val Candidate = GitCommit("a81c4e2f6b9d0c3e5f7a1b2c4d6e8f0a9b7c5d3e")
  private val Limits = HostLimits(10000, 3600000, 2000, 1000, 3000, 2097152)
  private val UnitCheck = ValidationCheck("unit", List("python3", "-m", "pytest", "-q"), 600000, 1048576, 1, 0)
  private val Requirements = "Demonstrate each acceptance criterion with a failing-then-passing test. Do not change the output for valid names."

  private def id(ledger: Ledger, number: Long): ItemId = ItemId(Project, ledger, number)
  private def view(id: ItemId, revision: Long, author: Actor, title: String, body: String, labels: Set[String], content: Content, refs: List[ItemRef]): ItemView =
    ItemView(Item(id, Revision(revision), ItemDraft(title, body, labels, false, content, Nil), ReceivedAt, ReceivedAt + revision * 60000,
      Provenance(author, ReceivedAt + revision * 60000, RequestId(uuid(100 + id.number.toInt)))), refs)
  private def revision(view: ItemView): ItemRevision = ItemRevision(view.item.id, view.item.revision)
  private def request(number: Int, work: DispatchWork, members: List[ItemView], guidance: List[ItemView], artifacts: List[ResolvedArtifact],
    previous: Option[ArtifactId]): DispatchRequest =
    DispatchRequest(RequestId(uuid(200 + number)), work, Harness.Claude, members.map(revision), guidance.map(revision), artifacts.map(_.metadata.id),
      previous, Fence(ClaimId(uuid(300 + number)), number.toLong), Limits)
  private def artifact(number: Int, attempt: AttemptId, kind: ArtifactKind, mediaType: String, body: String): ResolvedArtifact = {
    val bytes = body.getBytes(UTF_8)
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
    ResolvedArtifact(ArtifactMetadata(Project, ArtifactId(uuid(400 + number)), attempt, kind, mediaType, sha256, bytes.length,
      body.codePointCount(0, body.length), Actor("CQ host collector", Governor.session, Role.Collector), ReceivedAt), body)
  }
  private val NoEvidence = RetainedEvidence(Nil, Nil)

  private val Defect = view(id(Ledger.Defects, 7), 1, Operator, "greet prints an empty greeting for a blank name",
    "Reported from the release smoke test.", Set("cli"),
    Content.Defect(DefectStatus.Open, Severity.Medium, "`greet ''` prints 'Hello, !' and exits with status 0.",
      "A blank name is rejected with an error on stderr and a non-zero exit status.", "Run `greet ''` in a shell.", None, Nil), Nil)
  private val Research = view(id(Ledger.Researches, 3), 1, Governor, "Exit status for a rejected argument",
    "Needed before the fix for the blank-name defect is planned.", Set("cli"),
    Content.Research(ResearchStatus.Open, "Which exit status do argparse-based tools use when they reject an argument value?", Nil, None, None),
    List(ItemRef(Relation.DerivedFrom, Defect.item.id)))
  private val Hypothesis = view(id(Ledger.Hypothesis, 4), 1, Governor, "An argparse type callback rejects blank names without changing valid output",
    "", Set("cli"),
    Content.Hypothesis(HypothesisStatus.Investigating, "Validating the name in an argparse type callback makes `greet ''` exit with status 2 and leaves `greet Ada` unchanged.",
      "The explorer found that main() formats args.name without validating it.", Nil, None),
    List(ItemRef(Relation.DerivedFrom, Defect.item.id)))
  private val Goal = view(id(Ledger.Goals, 2), 2, Governor, "greet rejects blank names",
    "Derived from the blank-name defect and its research.", Set("cli"),
    Content.Goal(GoalStatus.Open, "greet refuses a blank name instead of printing an empty greeting.",
      List("A blank or whitespace-only name exits with status 2 and prints an error on stderr.", "A valid name prints the same greeting as before."),
      "The greet command line entry point and its tests."),
    List(ItemRef(Relation.DerivedFrom, Defect.item.id)))
  private val Decision = view(id(Ledger.Decisions, 1), 1, Operator, "Keep argparse as the argument parser",
    "", Set("cli"),
    Content.Decision(DecisionStatus.Adopted, "Validate arguments with argparse; do not add a parsing dependency.",
      "The tool has one positional argument and ships without third-party dependencies.", List("Adopt click")), Nil)
  private val Memory = view(id(Ledger.Memories, 1), 1, Governor, "greet has no third-party dependencies",
    "", Set("cli"),
    Content.Memory(MemoryStatus.Current, "greet ships without third-party dependencies; its command line is parsed with argparse from the standard library.",
      "Any change to how greet parses or validates its arguments.",
      List(Evidence("pyproject.toml declares an empty dependency list.", EvidenceOrigin.ModelDeclared, List(Citation.File("pyproject.toml", Some(Base.value)))))),
    List(ItemRef(Relation.DerivedFrom, Decision.item.id)))
  private val TaskDraft = ItemDraft("Reject blank names in greet", "Validate the name argument before formatting the greeting.", Set("cli"), false,
    Content.Task(TaskStatus.Ready, List("`greet ''` and `greet '   '` exit with status 2 and print an error on stderr.",
      "`greet Ada` still prints 'Hello, Ada!' and exits with status 0.",
      "Each criterion above is demonstrated with a failing-then-passing test."), None, Nil), Nil)
  private val MilestoneDraft = ItemDraft("greet input validation", "", Set("cli"), false,
    Content.Milestone(MilestoneStatus.Open, "greet rejects invalid names with a clear error and keeps its output for valid ones."), Nil)
  private val Task = ItemView(Item(id(Ledger.Tasks, 12), Revision(1), TaskDraft, ReceivedAt + 600000, ReceivedAt + 600000,
    Provenance(Governor, ReceivedAt + 600000, RequestId(uuid(112)))),
    List(ItemRef(Relation.DerivedFrom, Goal.item.id), ItemRef(Relation.PartOf, id(Ledger.Milestones, 1))))

  private val InvestigateRequest = request(1, DispatchWork.Explorer(ExplorerMode.Investigate), List(Defect), Nil, Nil, None)
  private val InvestigateReport = ChildReport.Evidence(List(EvidenceMember(Defect.item.id, EvidenceDisposition.Findings,
    "main() passes the parsed name to the greeting format without validating it, so an empty string produces 'Hello, !' and a successful exit.",
    List(Evidence("src/greeter/cli.py formats args.name directly; the parser declares the positional argument without a type callback or a check.",
      EvidenceOrigin.ModelDeclared, List(Citation.File("src/greeter/cli.py", Some(Base.value)))),
      Evidence("tests/test_cli.py covers only non-blank names.", EvidenceOrigin.ModelDeclared, List(Citation.File("tests/test_cli.py", Some(Base.value))))),
    List("Whether a whitespace-only name is affected in the same way was not observed; reading the source suggests it is."),
    List("Run `greet '   '` and record its output and exit status."))))

  private val ResearchRequest = request(2, DispatchWork.Explorer(ExplorerMode.Research), List(Research), List(Defect), Nil, None)
  private val ResearchReport = ChildReport.Evidence(List(EvidenceMember(Research.item.id, EvidenceDisposition.Findings,
    "argparse reports a rejected argument through ArgumentParser.error(), which prints a usage message to stderr and exits with status 2.",
    List(Evidence("The argparse documentation states that error() prints a usage message including the message to the standard error and terminates the program with a status code of 2.",
      EvidenceOrigin.ModelDeclared, List(Citation.Url("https://docs.python.org/3/library/argparse.html#exiting-methods")))),
    Nil, Nil)))
  private val ResearchResult = ChildResult(AttemptId(uuid(502)), ResearchRequest, Base, None, ResearchReport, Nil, NoEvidence)

  private val PlanRequest = request(3, DispatchWork.Planner(), List(Goal), List(Defect, Research, Memory), Nil, None)
  private val PlanReport = ChildReport.Plan(
    List(PlanMember(Goal.item.id, PlanDisposition.Proposed, "One task covers the goal: validate the name argument and test both the rejected and the accepted case.")),
    Some(LedgerProposal(List(ProposedMutation.Create(MilestoneDraft), ProposedMutation.Produce(Goal.item.id, List(TaskDraft), Some(MilestoneRef.Created(0)))),
      "Produce the implementation task for the blank-name goal under a new milestone")),
    Nil)
  private val PlanResult = ChildResult(AttemptId(uuid(503)), PlanRequest, Base, None, PlanReport, Nil, NoEvidence)

  private val ImplementRequest = request(4, DispatchWork.Worker(WorkerMode.Implement), List(Task), List(Decision), Nil, None)
  private val ImplementReport = ChildReport.Work(List(WorkMember(Task.item.id, WorkDisposition.CandidateReady,
    "Added a non-blank type callback for the name argument and tests for the empty and the valid name. The new empty-name test failed before the change and passes after it.",
    List(".work/evidence/pytest-before.log", ".work/evidence/pytest-after.log"))))
  private val ImplementResultId = ArtifactId(uuid(604))
  private val ImplementResult = ChildResult(AttemptId(uuid(504)), ImplementRequest, Base, Some(Candidate), ImplementReport,
    List(ValidationEvidence(UnitCheck.name, ValidationState.Passed, ArtifactId(uuid(704)), Nil)),
    RetainedEvidence(List(EvidenceFile(".work/evidence/pytest-before.log", ArtifactId(uuid(705)), 1423, false),
      EvidenceFile(".work/evidence/pytest-after.log", ArtifactId(uuid(706)), 512, false)), Nil))

  private val ProbeLog = artifact(5, AttemptId(uuid(501)), ArtifactKind.Evidence, "text/plain", "$ greet ''\nHello, !\n$ echo $?\n0\n")
  private val ProbeRequest = request(5, DispatchWork.Worker(WorkerMode.Probe), List(Hypothesis), List(Defect), List(ProbeLog), None)
  private val ProbeReport = ChildReport.Evidence(List(EvidenceMember(Hypothesis.item.id, EvidenceDisposition.Findings,
    "With a type callback that raises ArgumentTypeError for a blank value, `greet ''` and `greet '   '` exited with status 2 and `greet Ada` printed 'Hello, Ada!'.",
    List(Evidence("probe/blank_name_probe.py runs the three invocations against the patched parser and records their output and exit status.",
      EvidenceOrigin.ModelDeclared, List(Citation.File("probe/blank_name_probe.py", None), Citation.File(".work/evidence/probe.log", None)))),
    List("The probe patched the parser in place; packaging and the installed entry point were not exercised."),
    Nil)))

  private val CandidateReviewRequest = request(7, DispatchWork.Reviewer(ReviewerMode.Candidate), List(Task), Nil, Nil, Some(ImplementResultId))
  private val CandidateReviewReport = ChildReport.Review(List(ReviewMember(Task.item.id, ReviewVerdict.ChangesRequested,
    List("The candidate rejects an empty name but accepts a whitespace-only one: the callback in src/greeter/cli.py compares the value with '' instead of stripping it, so the first acceptance criterion is not met for `greet '   '`.",
      "tests/test_cli.py has no whitespace-only case; the passing host check 'unit' therefore does not cover that part of the criterion."))),
    None)
  private val CandidateReviewResultId = ArtifactId(uuid(607))
  private val CandidateReviewResult = ChildResult(AttemptId(uuid(507)), CandidateReviewRequest, Candidate, Some(Candidate), CandidateReviewReport,
    List(ValidationEvidence(UnitCheck.name, ValidationState.Passed, ArtifactId(uuid(707)), Nil)), NoEvidence)

  private val ResolveRequest = request(6, DispatchWork.Worker(WorkerMode.ResolveConflict), List(Task), List(Decision), Nil, Some(CandidateReviewResultId))
  private val ResolveReport = ChildReport.Work(List(WorkMember(Task.item.id, WorkDisposition.CandidateReady,
    "Applied the review corrections: the callback now strips the value before rejecting it, and a whitespace-only test was added. It failed before the correction and passes after it.",
    List(".work/evidence/pytest-whitespace.log"))))

  private val PlanReviewRequest = request(8, DispatchWork.Reviewer(ReviewerMode.Plan), List(Goal), Nil, Nil, Some(ArtifactId(uuid(603))))
  private val PlanReviewReport = ChildReport.Review(List(ReviewMember(Goal.item.id, ReviewVerdict.Accepted,
    List("The single Produce operation changes the goal once, assigns its task to the milestone created in the same proposal, and the task carries both goal criteria and the operator's failing-then-passing requirement."))),
    None)

  private val AuditRequest = request(9, DispatchWork.Reviewer(ReviewerMode.Audit), List(Research), Nil, Nil, Some(ArtifactId(uuid(602))))
  private val AuditReport = ChildReport.Review(List(ReviewMember(Research.item.id, ReviewVerdict.Accepted,
    List("The cited documentation supports the stated exit status; the conclusion does not go beyond that source."))),
    None)

  private def execution(request: DispatchRequest, members: List[ItemView], guidance: List[ItemView], artifacts: List[ResolvedArtifact],
    previous: Option[ChildResult], requirements: Option[String], base: GitCommit): ChildExecutionInput =
    ChildExecutionInput(ChildInput(Project, request, members, guidance, artifacts, previous, requirements), base, List(UnitCheck))

  /** The document a child of this role mode receives on its standard input. */
  def input(work: DispatchWork): ChildExecutionInput = work match {
    case DispatchWork.Explorer(ExplorerMode.Investigate) => execution(InvestigateRequest, List(Defect), Nil, Nil, None, None, Base)
    case DispatchWork.Explorer(ExplorerMode.Research) => execution(ResearchRequest, List(Research), List(Defect), Nil, None, None, Base)
    case _: DispatchWork.Planner => execution(PlanRequest, List(Goal), List(Defect, Research, Memory), Nil, None, Some(Requirements), Base)
    case DispatchWork.Worker(WorkerMode.Implement) => execution(ImplementRequest, List(Task), List(Decision), Nil, None, Some(Requirements), Base)
    case DispatchWork.Worker(WorkerMode.Probe) => execution(ProbeRequest, List(Hypothesis), List(Defect), List(ProbeLog), None, Some(Requirements), Base)
    case DispatchWork.Worker(WorkerMode.ResolveConflict) =>
      execution(ResolveRequest, List(Task), List(Decision), Nil, Some(CandidateReviewResult), Some(Requirements), Candidate)
    case DispatchWork.Reviewer(ReviewerMode.Candidate) => execution(CandidateReviewRequest, List(Task), Nil, Nil, Some(ImplementResult), None, Candidate)
    case DispatchWork.Reviewer(ReviewerMode.Plan) => execution(PlanReviewRequest, List(Goal), Nil, Nil, Some(PlanResult), None, Base)
    case DispatchWork.Reviewer(ReviewerMode.Audit) => execution(AuditRequest, List(Research), Nil, Nil, Some(ResearchResult), None, Base)
  }

  /** The report a child of this role mode returns for `input(work)`. */
  def output(work: DispatchWork): ChildReport = work match {
    case DispatchWork.Explorer(ExplorerMode.Investigate) => InvestigateReport
    case DispatchWork.Explorer(ExplorerMode.Research) => ResearchReport
    case _: DispatchWork.Planner => PlanReport
    case DispatchWork.Worker(WorkerMode.Implement) => ImplementReport
    case DispatchWork.Worker(WorkerMode.Probe) => ProbeReport
    case DispatchWork.Worker(WorkerMode.ResolveConflict) => ResolveReport
    case DispatchWork.Reviewer(ReviewerMode.Candidate) => CandidateReviewReport
    case DispatchWork.Reviewer(ReviewerMode.Plan) => PlanReviewReport
    case DispatchWork.Reviewer(ReviewerMode.Audit) => AuditReport
  }
}
