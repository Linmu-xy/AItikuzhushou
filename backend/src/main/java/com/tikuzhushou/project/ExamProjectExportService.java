package com.tikuzhushou.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.document.ObjectStorageService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.util.Units;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Produces the reviewed project deliverables without changing the legacy question-bank export. */
@Service
public class ExamProjectExportService {
  public static final String PAPER_XLSX = "PAPER_XLSX";
  public static final String ANSWER_XLSX = "ANSWER_XLSX";
  public static final String APPROVAL_XLSX = "APPROVAL_XLSX";
  public static final String PAPER_DOCX = "PAPER_DOCX";
  public static final String ANSWER_DOCX = "ANSWER_DOCX";
  public static final String APPROVAL_DOCX = "APPROVAL_DOCX";
  public static final String PAPER_PDF = "PAPER_PDF";
  public static final String ANSWER_PDF = "ANSWER_PDF";
  public static final String APPROVAL_PDF = "APPROVAL_PDF";
  public static final String ATTACHMENTS_ZIP = "ATTACHMENTS_ZIP";
  private static final List<String> ALL_OUTPUTS = List.of(PAPER_XLSX, ANSWER_XLSX, APPROVAL_XLSX,
      PAPER_DOCX, ANSWER_DOCX, APPROVAL_DOCX, PAPER_PDF, ANSWER_PDF, APPROVAL_PDF, ATTACHMENTS_ZIP);
  /** Keep the phase 9 API default stable; the new Word/PDF formats are opt-in. */
  private static final List<String> DEFAULT_OUTPUTS = List.of(PAPER_XLSX, ANSWER_XLSX, APPROVAL_XLSX, ATTACHMENTS_ZIP);
  private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final String PDF_TYPE = "application/pdf";
  private static final String ZIP_TYPE = "application/zip";
  private static final Pattern OPTION_LABEL = Pattern.compile("(?iu)(?<![A-Za-z0-9])([A-HＡ-Ｈ])[.．、:：)）]\\s*");

  private final JdbcTemplate jdbc;
  @Autowired(required = false) private KnowledgeVisualService visuals;
  private final ObjectMapper json;
  private final ExamProjectService projects;
  private final ExamProjectQuestionReviewService questionReview;
  private final ObjectStorageService storage;
  private final KnowledgeBaseAccessService access;
  private final String pdfFontPath;

  public ExamProjectExportService(JdbcTemplate jdbc, ObjectMapper json, ExamProjectService projects,
      ExamProjectQuestionReviewService questionReview, ObjectStorageService storage,
      KnowledgeBaseAccessService access, @Value("${app.export.pdf-font-path:}") String pdfFontPath) {
    this.jdbc = jdbc;
    this.json = json;
    this.projects = projects;
    this.questionReview = questionReview;
    this.storage = storage;
    this.access = access;
    this.pdfFontPath = Objects.toString(pdfFontPath, "").trim();
  }

  @Transactional
  public ExportRunView create(UUID projectId, UUID generationRunId, CreateRequest request) {
    ExamProjectService.ProjectView project = projects.get(projectId);
    RunHeader run = loadRun(generationRunId);
    if (!projectId.equals(run.projectId())) throw new IllegalArgumentException("生成批次不属于当前项目");
    if (!run.evidenceSnapshotId().equals(run.snapshotId())) throw new IllegalArgumentException("生成批次的命题依据快照无效");
    ExamProjectQuestionReviewService.ExportReadiness readiness = questionReview.exportReadiness(projectId, generationRunId);
    if (!readiness.ready()) throw new IllegalArgumentException("当前项目还不能导出：" + String.join("；", readiness.blockers()));
    List<String> outputs = normalizeOutputs(request == null ? null : request.outputTypes());
    ExportRunView active = active(projectId, generationRunId);
    if (active != null) return active;
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exam_project_export_runs(id,project_id,generation_run_id,evidence_snapshot_id,status,output_types_json,requested_count,completed_count,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
        id, projectId, generationRunId, run.evidenceSnapshotId(), "QUEUED", write(outputs), outputs.size(), 0,
        access.currentUserId(), Timestamp.from(now), Timestamp.from(now));
    return get(id);
  }

  public List<ExportRunView> list(UUID projectId, UUID generationRunId) {
    projects.get(projectId);
    if (!loadRun(generationRunId).projectId().equals(projectId)) throw new IllegalArgumentException("生成批次不属于当前项目");
    return jdbc.query("select id,project_id,generation_run_id,evidence_snapshot_id,status,output_types_json,requested_count,completed_count,error_message,created_at,started_at,finished_at,updated_at from exam_project_export_runs where project_id=? and generation_run_id=? order by created_at desc",
        (rs, row) -> withArtifacts(mapRun(rs)), projectId, generationRunId);
  }

  /** Lightweight task-center listing without loading artifact metadata for every export. */
  public List<ExportRunView> listSummaries() {
    String sql = "select e.id,e.project_id,e.generation_run_id,e.evidence_snapshot_id,e.status,e.output_types_json," +
        "e.requested_count,e.completed_count,e.error_message,e.created_at,e.started_at,e.finished_at,e.updated_at " +
        "from exam_project_export_runs e join exam_projects p on p.id=e.project_id " +
        (access.admin() ? "" : "where p.owner_id=? ") + "order by e.updated_at desc limit 100";
    return access.admin() ? jdbc.query(sql, (rs, row) -> mapRun(rs))
        : jdbc.query(sql, (rs, row) -> mapRun(rs), access.currentUserId());
  }

  public ExportRunView get(UUID id) {
    ExportRunView value = jdbc.query("select id,project_id,generation_run_id,evidence_snapshot_id,status,output_types_json,requested_count,completed_count,error_message,created_at,started_at,finished_at,updated_at from exam_project_export_runs where id=?",
        (rs, row) -> mapRun(rs), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("项目导出任务不存在"));
    projects.get(value.projectId());
    return withArtifacts(value);
  }

  /** Called by the export worker after restoring the owner's security context. */
  public void execute(UUID exportId) throws Exception {
    if (jdbc.update("update exam_project_export_runs set status='RUNNING',started_at=coalesce(started_at,?),updated_at=? where id=? and status='QUEUED'",
        Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), exportId) != 1) return;
    try {
      ExportRunView run = systemGet(exportId);
      ExamProjectQuestionReviewService.ExportReadiness readiness = questionReview.exportReadiness(run.projectId(), run.generationRunId());
      if (!readiness.ready()) throw new IllegalArgumentException("导出前审核状态发生变化：" + String.join("；", readiness.blockers()));
      RunHeader header = loadRun(run.generationRunId());
      List<String> outputs = readStrings(run.outputTypesJson());
      for (String output : outputs) {
        if (hasArtifact(run.id(), output)) continue;
        Payload payload = build(output, header);
        try {
          store(run, output, payload);
        } finally {
          payload.close();
        }
        Integer completed = jdbc.queryForObject("select count(*) from exam_project_export_artifacts where export_run_id=?", Integer.class, run.id());
        jdbc.update("update exam_project_export_runs set completed_count=?,updated_at=? where id=?",
            completed == null ? 0 : completed, Timestamp.from(Instant.now()), run.id());
      }
      Instant now = Instant.now();
      jdbc.update("update exam_project_export_runs set status='DOWNLOAD_READY',completed_count=(select count(*) from exam_project_export_artifacts where export_run_id=?),finished_at=?,updated_at=? where id=?",
          run.id(), Timestamp.from(now), Timestamp.from(now), run.id());
    } catch (Exception error) {
      fail(exportId, rootMessage(error));
      throw error;
    }
  }

  public ExportRunView systemGet(UUID id) {
    return jdbc.query("select id,project_id,generation_run_id,evidence_snapshot_id,status,output_types_json,requested_count,completed_count,error_message,created_at,started_at,finished_at,updated_at from exam_project_export_runs where id=?",
        (rs, row) -> mapRun(rs), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("项目导出任务不存在"));
  }

  public List<UUID> recoverable() {
    jdbc.update("update exam_project_export_runs set status='QUEUED',updated_at=? where status='RUNNING'", Timestamp.from(Instant.now()));
    return jdbc.query("select id from exam_project_export_runs where status='QUEUED' order by created_at",
        (rs, row) -> rs.getObject(1, UUID.class));
  }

  public boolean claim(UUID id) {
    return jdbc.update("update exam_project_export_runs set status='RUNNING',started_at=coalesce(started_at,?),updated_at=? where id=? and status='QUEUED'",
        Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id) == 1;
  }

  public void fail(UUID id, String message) {
    jdbc.update("update exam_project_export_runs set status='FAILED',error_message=?,finished_at=?,updated_at=? where id=? and status in ('QUEUED','RUNNING')",
        shorten(message, 2000), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
  }

  public StoredArtifact artifact(UUID id) {
    List<StoredArtifact> rows = jdbc.query("select a.id,a.export_run_id,a.output_type,a.filename,a.media_type,a.storage_path,a.size_bytes,a.sha256,a.created_at,p.owner_id from exam_project_export_artifacts a join exam_project_export_runs r on r.id=a.export_run_id join exam_projects p on p.id=r.project_id where a.id=?",
        (rs, row) -> new StoredArtifact(rs.getObject("id", UUID.class), rs.getObject("export_run_id", UUID.class),
            rs.getString("output_type"), rs.getString("filename"), rs.getString("media_type"),
            rs.getString("storage_path"), rs.getLong("size_bytes"), rs.getString("sha256"),
            rs.getTimestamp("created_at").toInstant(), rs.getObject("owner_id", UUID.class)), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("项目导出文件不存在");
    StoredArtifact value = rows.getFirst();
    if (!access.admin() && !value.ownerId().equals(access.currentUserId())) throw new AccessDeniedException("无权下载其他用户的项目导出文件");
    return value;
  }

  public byte[] read(StoredArtifact artifact) throws Exception {
    Path file = storage.materialize(artifact.storagePath(), suffix(artifact.filename()));
    try { return Files.readAllBytes(file); }
    finally { storage.cleanupMaterialized(file); }
  }

  private ExportRunView active(UUID projectId, UUID generationRunId) {
    return jdbc.query("select id,project_id,generation_run_id,evidence_snapshot_id,status,output_types_json,requested_count,completed_count,error_message,created_at,started_at,finished_at,updated_at from exam_project_export_runs where project_id=? and generation_run_id=? and status in ('QUEUED','RUNNING') order by created_at desc limit 1",
        (rs, row) -> mapRun(rs), projectId, generationRunId).stream().findFirst().map(this::withArtifacts).orElse(null);
  }

  private ExportRunView withArtifacts(ExportRunView run) {
    List<ArtifactView> artifacts = jdbc.query("select id,export_run_id,output_type,filename,media_type,size_bytes,sha256,created_at from exam_project_export_artifacts where export_run_id=? order by created_at,output_type",
        (rs, row) -> new ArtifactView(rs.getObject("id", UUID.class), rs.getObject("export_run_id", UUID.class),
            rs.getString("output_type"), rs.getString("filename"), rs.getString("media_type"), rs.getLong("size_bytes"),
            rs.getString("sha256"), rs.getTimestamp("created_at").toInstant()), run.id());
    return run.withArtifacts(artifacts);
  }

  private ExportRunView mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ExportRunView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
        rs.getObject("generation_run_id", UUID.class), rs.getObject("evidence_snapshot_id", UUID.class),
        rs.getString("status"), rs.getString("output_types_json"), rs.getInt("requested_count"),
        rs.getInt("completed_count"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")), rs.getTimestamp("updated_at").toInstant(), List.of());
  }

  private RunHeader loadRun(UUID runId) {
    return jdbc.query("select r.id,r.project_id,r.id generation_run_id,r.evidence_snapshot_id,s.id snapshot_id,s.snapshot_version,s.snapshot_hash,s.sources_json,p.name project_name from exam_project_generation_runs r join exam_project_evidence_snapshots s on s.id=r.evidence_snapshot_id join exam_projects p on p.id=r.project_id where r.id=?",
        (rs, row) -> new RunHeader(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getObject("generation_run_id", UUID.class), rs.getObject("evidence_snapshot_id", UUID.class),
            rs.getObject("snapshot_id", UUID.class), rs.getInt("snapshot_version"), rs.getString("snapshot_hash"),
            rs.getString("sources_json"), rs.getString("project_name")), runId).stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("项目生成批次不存在"));
  }

  private Payload build(String output, RunHeader header) throws Exception {
    return switch (output) {
      case PAPER_XLSX -> workbookPayload(header, "paper");
      case ANSWER_XLSX -> workbookPayload(header, "answer");
      case APPROVAL_XLSX -> workbookPayload(header, "approval");
      case PAPER_DOCX -> richDocumentPayload(header, "paper", "docx");
      case ANSWER_DOCX -> richDocumentPayload(header, "answer", "docx");
      case APPROVAL_DOCX -> richDocumentPayload(header, "approval", "docx");
      case PAPER_PDF -> richDocumentPayload(header, "paper", "pdf");
      case ANSWER_PDF -> richDocumentPayload(header, "answer", "pdf");
      case APPROVAL_PDF -> richDocumentPayload(header, "approval", "pdf");
      case ATTACHMENTS_ZIP -> attachmentPayload(header);
      default -> throw new IllegalArgumentException("不支持的导出类型：" + output);
    };
  }

  private Payload richDocumentPayload(RunHeader header, String kind, String format) throws Exception {
    List<QuestionRow> rows = loadQuestions(header.runId());
    String prefix = switch (kind) { case "paper" -> "试卷"; case "answer" -> "答案与评分细则"; default -> "审批表"; };
    if ("docx".equals(format)) {
      byte[] bytes = buildDocx(header, kind, rows);
      validateDocx(bytes, header, kind, rows);
      return Payload.bytes(prefix + "_" + safeFileName(header.projectName()) + ".docx", DOCX_TYPE, bytes);
    }
    byte[] bytes = buildPdf(header, kind, rows);
    validatePdf(bytes, header, kind, rows);
    return Payload.bytes(prefix + "_" + safeFileName(header.projectName()) + ".pdf", PDF_TYPE, bytes);
  }

  private byte[] buildDocx(RunHeader header, String kind, List<QuestionRow> rows) throws Exception {
    Map<QuestionRow, Integer> numbers = exportNumbers(rows);
    try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      configureDocxPage(document);
      String title = switch (kind) {
        case "paper" -> "试卷";
        case "answer" -> "答案与评分细则";
        default -> "命题项目审批表";
      };
      XWPFParagraph titleParagraph = addDocxParagraph(document, title, 18, true, ParagraphAlignment.CENTER);
      var titleStyle = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory.newInstance();
      titleStyle.setStyleId("Title");
      titleStyle.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
      titleStyle.addNewName().setVal("Title");
      document.createStyles().addStyle(new org.apache.poi.xwpf.usermodel.XWPFStyle(titleStyle));
      titleParagraph.setStyle("Title");
      titleParagraph.setSpacingAfter(240);
      titleParagraph.setKeepNext(true);
      addDocxParagraph(document, "项目：" + header.projectName(), 12, false, ParagraphAlignment.LEFT).setKeepNext(true);

      if ("approval".equals(kind)) {
        addDocxParagraph(document, "生成批次：" + header.runId(), 9, false, ParagraphAlignment.LEFT);
        addDocxParagraph(document, "审批结论：□ 同意导出    □ 退回修改", 10, false, ParagraphAlignment.LEFT);
        addDocxParagraph(document, "审批人：____________________    审批日期：____________________", 10, false, ParagraphAlignment.LEFT);
        addDocxParagraph(document, "审批意见：", 10, true, ParagraphAlignment.LEFT);
        addDocxParagraph(document, "", 10, false, ParagraphAlignment.LEFT);
        addDocxTable(document, new String[] { "套次", "原题号", "导出题号", "题型", "难度", "分值", "状态", "版本", "审核人" },
            rows.stream().map(item -> List.of(item.variantLabel(), Integer.toString(item.sequenceNo()), Integer.toString(numbers.get(item)), typeName(item),
                difficultyName(item.difficulty()), Integer.toString(item.points()), item.status(), Integer.toString(item.questionVersion()),
                item.reviewerName())).toList());
      } else {
        boolean firstVariant = true;
        for (Map.Entry<Integer, List<QuestionRow>> entry : byVariant(rows).entrySet()) {
          String label = entry.getValue().isEmpty() ? Integer.toString(entry.getKey()) : entry.getValue().getFirst().variantLabel();
          XWPFParagraph variantHeading = addDocxParagraph(document, "第" + label + "套", 14, true, ParagraphAlignment.LEFT);
          variantHeading.setPageBreak(!firstVariant);
          variantHeading.setKeepNext(true);
          firstVariant = false;
          Map<String, List<QuestionRow>> typeGroups = byQuestionType(entry.getValue());
          String previousType = "";
          int sectionIndex = 0;
          for (QuestionRow item : entry.getValue()) {
            String typeKey = typeKey(item);
            if (!previousType.equals(typeKey)) {
              XWPFParagraph sectionHeading = addDocxParagraph(document, sectionTitle(++sectionIndex, typeGroups.get(typeKey)), 14, true, ParagraphAlignment.LEFT);
              sectionHeading.setSpacingBefore(200);
              sectionHeading.setKeepNext(true);
              previousType = typeKey;
            }
            List<String> options = docxOptions(item.question().get("options"));
            List<byte[]> images = stimulusImages(header.projectId(), item.question());
            XWPFParagraph questionHeading = addDocxParagraph(document, numbers.get(item) + "．（"
                + typeName(item) + "，" + item.points() + "分）", 12, true, ParagraphAlignment.LEFT);
            questionHeading.setSpacingBefore(240);
            questionHeading.setKeepNext(true);
            XWPFParagraph stem = addDocxParagraph(document, displayValue(item.question().get("stem")), 12, false, ParagraphAlignment.LEFT);
            stem.setKeepNext(!options.isEmpty() || !images.isEmpty());
            for (int imageIndex = 0; imageIndex < images.size(); imageIndex++) {
              byte[] stimulus = images.get(imageIndex);
              BufferedImage image = ImageIO.read(new ByteArrayInputStream(stimulus));
              if (image == null) throw new IllegalStateException("题目原图无法读取");
              double scale = Math.min(460d / image.getWidth(), 340d / image.getHeight());
              XWPFParagraph imageParagraph = document.createParagraph();
              imageParagraph.setAlignment(ParagraphAlignment.CENTER);
              imageParagraph.setSpacingAfter(120);
              imageParagraph.setKeepNext(imageIndex + 1 < images.size() || !options.isEmpty());
              XWPFRun imageRun = imageParagraph.createRun();
              imageRun.addPicture(new ByteArrayInputStream(stimulus), XWPFDocument.PICTURE_TYPE_PNG,
                  "题目配图.png", Units.toEMU(Math.max(1, (int) (image.getWidth() * scale))),
                  Units.toEMU(Math.max(1, (int) (image.getHeight() * scale))));
            }
            for (String option : options) {
              XWPFParagraph optionParagraph = addDocxParagraph(document, option, 12, false, ParagraphAlignment.LEFT);
              optionParagraph.setIndentationLeft(480);
              optionParagraph.setIndentationHanging(360);
              optionParagraph.setSpacingAfter(40);
              // A tab makes wrapped lines align with the option text instead of its label.
              var tabs = optionParagraph.getCTP().getPPr().addNewTabs();
              var tab = tabs.addNewTab();
              tab.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc.LEFT);
              tab.setPos(BigInteger.valueOf(480));
            }
            if ("answer".equals(kind)) {
              addDocxAnswerBlock(document, "答案", displayValue(item.question().get("answer")));
              addDocxAnswerBlock(document, "解析", displayValue(item.question().get("analysis")));
              addDocxAnswerBlock(document, "评分细则", docxScoringRubric(item.question()));
            }
          }
        }
      }
      document.write(out);
      return out.toByteArray();
    }
  }

  private void configureDocxPage(XWPFDocument document) {
    var body = document.getDocument().getBody();
    var section = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
    var pageSize = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
    pageSize.setW(BigInteger.valueOf(11906));
    pageSize.setH(BigInteger.valueOf(16838));
    var margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
    margins.setTop(BigInteger.valueOf(1440));
    margins.setBottom(BigInteger.valueOf(1440));
    margins.setLeft(BigInteger.valueOf(1417));
    margins.setRight(BigInteger.valueOf(1417));
  }

  private XWPFParagraph addDocxParagraph(XWPFDocument document, String value, int size, boolean bold,
      ParagraphAlignment alignment) {
    XWPFParagraph paragraph = document.createParagraph();
    paragraph.setAlignment(alignment);
    paragraph.setSpacingAfter(120);
    paragraph.setSpacingBetween(1.25);
    paragraph.getCTP().getPPr().addNewWidowControl().setVal(true);
    XWPFRun run = paragraph.createRun();
    run.setFontFamily("Times New Roman");
    run.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);
    run.setFontSize(size);
    run.setBold(bold);
    run.setColor("000000");
    String[] lines = shortText(Objects.toString(value, "")).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) run.addBreak();
      String[] pieces = lines[i].split("\t", -1);
      for (int j = 0; j < pieces.length; j++) {
        if (j > 0) run.addTab();
        run.setText(pieces[j]);
      }
    }
    return paragraph;
  }

  private void addDocxAnswerBlock(XWPFDocument document, String label, String value) {
    if (value.isBlank()) return;
    XWPFParagraph heading = addDocxParagraph(document, label + "：", 12, true, ParagraphAlignment.LEFT);
    heading.setSpacingBefore(120);
    heading.setSpacingAfter(40);
    heading.setKeepNext(true);
    for (String line : value.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      if (!line.isBlank()) addDocxParagraph(document, line.strip(), 12, false, ParagraphAlignment.LEFT);
    }
  }

  private String docxScoringRubric(Map<String, Object> question) {
    if (question.get("scoringItems") instanceof List<?> items && !items.isEmpty()) {
      List<String> criteria = new ArrayList<>();
      for (Object item : items) {
        if (item instanceof Map<?, ?> row && !displayValue(row.get("criterion")).isBlank()) {
          String points = displayValue(row.get("points"));
          criteria.add(displayValue(row.get("criterion")) + (points.isBlank() ? "" : "（" + points + "分）"));
        }
      }
      if (!criteria.isEmpty()) return String.join("\n", criteria);
    }
    return displayValue(question.get("scoringRubric")).replaceAll("[；;]\\s*", "\n");
  }

  /** Accept historical pipe-separated strings and current structured options without changing stored questions. */
  private List<String> docxOptions(Object value) {
    if (value == null) return List.of();
    if (value instanceof String raw) {
      String text = raw.strip();
      if (text.isEmpty()) return List.of();
      if (text.startsWith("{") || text.startsWith("[")) {
        try { return docxOptions(json.readValue(text, Object.class)); }
        catch (IOException ignored) { /* Keep malformed historical text visible rather than dropping it. */ }
      }
      var matcher = OPTION_LABEL.matcher(text);
      List<Integer> starts = new ArrayList<>();
      while (matcher.find()) starts.add(matcher.start());
      List<String> result = new ArrayList<>();
      // Split on labels, not every pipe/newline: those can belong to an option's actual content.
      if (!starts.isEmpty() && starts.getFirst() == 0) {
        for (int i = 0; i < starts.size(); i++) {
          int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
          String part = text.substring(starts.get(i), end).strip();
          // Remove only an unpaired separator between options, never the closing bar in |x| or final punctuation.
          if (i + 1 < starts.size() && (part.endsWith("|") || part.endsWith("｜"))
              && part.chars().filter(c -> c == '|' || c == '｜').count() % 2 == 1) {
            part = part.substring(0, part.length() - 1).stripTrailing();
          }
          result.add(docxOption("", part, i));
        }
      } else {
        for (String part : text.split("[|｜\\r\\n]+")) {
          if (!part.isBlank()) result.add(docxOption("", part, result.size()));
        }
      }
      return result;
    }
    List<String> result = new ArrayList<>();
    if (value instanceof Map<?, ?> map) {
      map.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> optionLabel(Objects.toString(entry.getKey(), ""))))
          .forEach(entry -> result.add(docxOption(Objects.toString(entry.getKey(), ""), displayValue(entry.getValue()), result.size())));
    } else if (value instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> map) {
          Object content = map.containsKey("text") ? map.get("text") : map.containsKey("content") ? map.get("content") : map.get("value");
          if (content != null) {
            Object label = map.containsKey("label") ? map.get("label") : map.get("key");
            result.add(docxOption(Objects.toString(label, ""), displayValue(content), result.size()));
          } else result.addAll(docxOptions(map));
        } else result.add(docxOption("", displayValue(entry), result.size()));
      }
    } else result.add(docxOption("", displayValue(value), 0));
    return result;
  }

  private String docxOption(String label, String content, int index) {
    String text = content.strip();
    var matcher = OPTION_LABEL.matcher(text);
    if (matcher.lookingAt()) {
      if (label.isBlank()) label = matcher.group(1);
      text = text.substring(matcher.end()).strip();
    }
    if (label.isBlank()) label = Character.toString('A' + index);
    return optionLabel(label) + ".\t" + text;
  }

  private String optionLabel(String label) {
    return java.text.Normalizer.normalize(label.strip(), java.text.Normalizer.Form.NFKC)
        .replaceAll("[.、:：)）]+$", "").toUpperCase(Locale.ROOT);
  }

  private void addDocxTable(XWPFDocument document, String[] headers, List<List<String>> values) {
    XWPFTable table = document.createTable(1, headers.length);
    XWPFTableRow header = table.getRow(0);
    for (int i = 0; i < headers.length; i++) setDocxCell(header.getCell(i), headers[i], true);
    for (List<String> value : values) {
      XWPFTableRow row = table.createRow();
      for (int i = 0; i < headers.length; i++) setDocxCell(row.getCell(i), i < value.size() ? value.get(i) : "", false);
    }
  }

  private void setDocxCell(XWPFTableCell cell, String value, boolean header) {
    cell.setColor(header ? "D9EDE8" : "FFFFFF");
    cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
    cell.setText(shortText(Objects.toString(value, "")));
    for (XWPFParagraph paragraph : cell.getParagraphs()) {
      paragraph.setSpacingAfter(0);
      for (XWPFRun run : paragraph.getRuns()) {
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(8);
        run.setBold(header);
      }
    }
  }

  private void validateDocx(byte[] bytes, RunHeader header, String kind, List<QuestionRow> rows) throws Exception {
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      StringBuilder text = new StringBuilder();
      for (XWPFParagraph paragraph : document.getParagraphs()) text.append(paragraph.getText()).append('\n');
      for (XWPFTable table : document.getTables()) {
        for (XWPFTableRow row : table.getRows()) {
          for (XWPFTableCell cell : row.getTableCells()) text.append(cell.getText()).append('\n');
        }
      }
      requireExportText(text.toString(), header.projectName(), "Word 文档缺少项目名称");
      if ("approval".equals(kind)) requireExportText(text.toString(), "审批结论", "Word 审批表缺少审批栏");
      else if (rows.stream().findFirst().isPresent()) {
        String stem = displayValue(rows.getFirst().question().get("stem"));
        if (!stem.isBlank()) requireExportText(text.toString(), validationNeedle(stem), "Word 文档缺少首道题目内容");
      }
      if (document.getParagraphs().isEmpty() && document.getTables().isEmpty()) throw new IllegalStateException("Word 文档为空");
    }
  }

  private byte[] buildPdf(RunHeader header, String kind, List<QuestionRow> rows) throws Exception {
    Map<QuestionRow, Integer> numbers = exportNumbers(rows);
    Path font = resolvePdfFont();
    try (PDDocument document = new PDDocument(); InputStream fontInput = Files.newInputStream(font);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDFont pdfFont = PDType0Font.load(document, fontInput);
      PdfCanvas canvas = new PdfCanvas(document, pdfFont);
      try {
        String title = switch (kind) {
          case "paper" -> "试卷";
          case "answer" -> "答案与评分细则";
          default -> "命题项目审批表";
        };
        canvas.heading(title);
        canvas.paragraph("项目：" + header.projectName(), 10);
        if ("approval".equals(kind)) {
          canvas.paragraph("生成批次：" + header.runId(), 9);
          canvas.paragraph("审批结论：□ 同意导出    □ 退回修改", 10);
          canvas.paragraph("审批人：____________________    审批日期：____________________", 10);
          canvas.paragraph("审批意见：", 10);
          for (QuestionRow item : rows) {
            canvas.heading(item.variantLabel() + " - 原题号 " + item.sequenceNo() + "，导出题号 " + numbers.get(item));
            canvas.paragraph("题型：" + typeName(item) + "｜难度：" + difficultyName(item.difficulty())
                + "｜分值：" + item.points() + "｜状态：" + item.status(), 9);
            canvas.paragraph("版本：" + item.questionVersion() + "｜审核人：" + item.reviewerName(), 9);
            canvas.paragraph("审核意见：" + item.reviewComment(), 9);
          }
        } else {
          boolean firstVariant = true;
          for (Map.Entry<Integer, List<QuestionRow>> entry : byVariant(rows).entrySet()) {
            String label = entry.getValue().isEmpty() ? Integer.toString(entry.getKey()) : entry.getValue().getFirst().variantLabel();
            if (!firstVariant) canvas.newPage();
            firstVariant = false;
            canvas.heading("第" + label + "套");
            Map<String, List<QuestionRow>> typeGroups = byQuestionType(entry.getValue());
            String previousType = "";
            int sectionIndex = 0;
            for (QuestionRow item : entry.getValue()) {
              String typeKey = typeKey(item);
              if (!previousType.equals(typeKey)) {
                canvas.ensureSpace(80);
                canvas.heading(sectionTitle(++sectionIndex, typeGroups.get(typeKey)));
                previousType = typeKey;
              }
              canvas.ensureSpace(45);
              canvas.heading(numbers.get(item) + "．（" + typeName(item) + "，" + item.points() + "分）");
              canvas.paragraph(displayValue(item.question().get("stem")), 10);
              for (byte[] stimulus : stimulusImages(header.projectId(), item.question())) canvas.image(stimulus);
              for (String option : docxOptions(item.question().get("options"))) canvas.paragraph(option, 10);
              if ("answer".equals(kind)) {
                canvas.paragraph("答案：" + displayValue(item.question().get("answer")), 10);
                canvas.paragraph("解析：" + displayValue(item.question().get("analysis")), 10);
                canvas.paragraph("评分细则：" + docxScoringRubric(item.question()), 10);
              }
            }
          }
        }
      } finally {
        canvas.close();
      }
      document.save(out);
      return out.toByteArray();
    }
  }

  private Path resolvePdfFont() {
    List<String> candidates = new ArrayList<>();
    if (!pdfFontPath.isBlank()) candidates.add(pdfFontPath);
    candidates.add("C:\\Windows\\Fonts\\simhei.ttf");
    candidates.add("C:\\Windows\\Fonts\\Noto Sans SC.ttf");
    candidates.add("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf");
    candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttf");
    for (String candidate : candidates) {
      Path path = Path.of(candidate);
      if (Files.isRegularFile(path)) return path;
    }
    throw new IllegalStateException("未找到可嵌入的中文 PDF 字体，请设置 APP_EXPORT_PDF_FONT_PATH");
  }

  private void validatePdf(byte[] bytes, RunHeader header, String kind, List<QuestionRow> rows) throws Exception {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.getNumberOfPages() < 1) throw new IllegalStateException("PDF 文档没有页面");
      String text = new PDFTextStripper().getText(document);
      requireExportText(text, header.projectName(), "PDF 文档缺少项目名称");
      if ("approval".equals(kind)) requireExportText(text, "审批结论", "PDF 审批表缺少审批栏");
      else if (rows.stream().findFirst().isPresent()) {
        String stem = displayValue(rows.getFirst().question().get("stem"));
        if (!stem.isBlank()) requireExportText(text, validationNeedle(stem), "PDF 文档缺少首道题目内容");
      }
    }
  }

  private void requireExportText(String text, String expected, String message) {
    String actual = Objects.toString(text, "").replaceAll("\\s+", "");
    String needle = Objects.toString(expected, "").replaceAll("\\s+", "");
    if (!actual.contains(needle)) throw new IllegalStateException(message);
  }

  private String validationNeedle(String value) { return shorten(value.replaceAll("\\s+", " ").trim(), 80); }

  private String displayValue(Object value) {
    if (value == null) return "";
    if (value instanceof Map<?, ?> || value instanceof List<?>) return shortText(write(value));
    return shortText(Objects.toString(value, ""));
  }

  private List<byte[]> stimulusImages(UUID projectId, Map<String, Object> question) {
    if (!(question.get("stimuli") instanceof List<?> list) || list.isEmpty()) return List.of();
    if (visuals == null) throw new IllegalStateException("导出图像题缺少原图服务");
    List<byte[]> images = new ArrayList<>();
    for (Object value : list.stream().limit(2).toList()) {
      if (!(value instanceof Map<?, ?> entry)) throw new IllegalStateException("题目配图参数无效");
      UUID documentId = UUID.fromString(String.valueOf(entry.get("documentId")));
      int page = ((Number) entry.get("page")).intValue();
      images.add(visuals.page(projectId, documentId, page, number(entry.get("x"), 0), number(entry.get("y"), 0),
          number(entry.get("width"), 100), number(entry.get("height"), 100)));
    }
    return images;
  }

  private int number(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private Payload workbookPayload(RunHeader header, String kind) throws Exception {
    List<QuestionRow> rows = loadQuestions(header.runId());
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Styles styles = new Styles(workbook);
      if ("paper".equals(kind)) writePaper(workbook, styles, header, rows);
      else if ("answer".equals(kind)) writeAnswer(workbook, styles, header, rows);
      else writeApproval(workbook, styles, header, rows);
      workbook.write(out);
      String prefix = switch (kind) { case "paper" -> "试卷"; case "answer" -> "答案与评分细则"; default -> "审批表"; };
      return Payload.bytes(prefix + "_" + safeFileName(header.projectName()) + ".xlsx", XLSX_TYPE, out.toByteArray());
    }
  }

  private void writePaper(XSSFWorkbook workbook, Styles styles, RunHeader header, List<QuestionRow> rows) {
    Map<QuestionRow, Integer> numbers = exportNumbers(rows);
    for (Map.Entry<Integer, List<QuestionRow>> entry : byVariant(rows).entrySet()) {
      String label = entry.getValue().getFirst().variantLabel();
      Sheet sheet = workbook.createSheet(shortenSheet("试卷-第" + label + "套"));
      title(sheet, styles, "试卷（第" + label + "套）", header.projectName(), "本试卷仅包含题目，不含答案和评分细则");
      row(sheet, 3, styles.header(), "题号", "题型", "难度", "分值", "题目", "选项");
      int index = 4;
      XSSFDrawing drawing = null;
      Map<String, List<QuestionRow>> typeGroups = byQuestionType(entry.getValue());
      String previousType = "";
      int sectionIndex = 0;
      for (QuestionRow item : entry.getValue()) {
        String typeKey = typeKey(item);
        if (!previousType.equals(typeKey)) {
          addExcelSection(sheet, index++, 6, styles.header(), sectionTitle(++sectionIndex, typeGroups.get(typeKey)));
          previousType = typeKey;
        }
        Row row = sheet.createRow(index++);
        cell(row, 0, numbers.get(item)); cell(row, 1, typeName(item)); cell(row, 2, difficultyName(item.difficulty()));
        cell(row, 3, item.points()); cell(row, 4, text(item.question().get("stem")));
        cell(row, 5, String.join("\n", docxOptions(item.question().get("options"))).replace('\t', ' '));
        style(row, styles.body());
        for (byte[] stimulus : stimulusImages(header.projectId(), item.question())) {
          Row imageRow = sheet.createRow(index++);
          imageRow.setHeightInPoints(230);
          if (drawing == null) drawing = ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).createDrawingPatriarch();
          int picture = workbook.addPicture(stimulus, Workbook.PICTURE_TYPE_PNG);
          XSSFClientAnchor anchor = new XSSFClientAnchor();
          anchor.setCol1(4); anchor.setCol2(6);
          anchor.setRow1(imageRow.getRowNum()); anchor.setRow2(imageRow.getRowNum() + 1);
          drawing.createPicture(anchor, picture);
        }
      }
      finishSheet(sheet, new int[] { 10, 18, 10, 10, 70, 70 }, 4);
    }
  }

  private void writeAnswer(XSSFWorkbook workbook, Styles styles, RunHeader header, List<QuestionRow> rows) {
    Map<QuestionRow, Integer> numbers = exportNumbers(rows);
    for (Map.Entry<Integer, List<QuestionRow>> entry : byVariant(rows).entrySet()) {
      String label = entry.getValue().getFirst().variantLabel();
      Sheet sheet = workbook.createSheet(shortenSheet("答案-第" + label + "套"));
      title(sheet, styles, "答案与评分细则（第" + label + "套）", header.projectName(), "教师审核后交付");
      row(sheet, 3, styles.header(), "题号", "题型", "难度", "分值", "答案", "解析", "评分细则", "题目版本", "审核人");
      int index = 4;
      Map<String, List<QuestionRow>> typeGroups = byQuestionType(entry.getValue());
      String previousType = "";
      int sectionIndex = 0;
      for (QuestionRow item : entry.getValue()) {
        String typeKey = typeKey(item);
        if (!previousType.equals(typeKey)) {
          addExcelSection(sheet, index++, 9, styles.header(), sectionTitle(++sectionIndex, typeGroups.get(typeKey)));
          previousType = typeKey;
        }
        Row row = sheet.createRow(index++);
        cell(row, 0, numbers.get(item)); cell(row, 1, typeName(item)); cell(row, 2, difficultyName(item.difficulty()));
        cell(row, 3, item.points()); cell(row, 4, text(item.question().get("answer"))); cell(row, 5, text(item.question().get("analysis")));
        cell(row, 6, docxScoringRubric(item.question())); cell(row, 7, item.questionVersion());
        cell(row, 8, item.reviewerName()); style(row, styles.body());
      }
      finishSheet(sheet, new int[] { 10, 18, 10, 10, 22, 62, 62, 12, 18 }, 4);
    }
  }

  private void writeApproval(XSSFWorkbook workbook, Styles styles, RunHeader header, List<QuestionRow> rows) {
    Map<QuestionRow, Integer> numbers = exportNumbers(rows);
    Sheet sheet = workbook.createSheet("审批表");
    title(sheet, styles, "命题项目审批表", header.projectName(), "全部题目已由教师人工审核通过");
    row(sheet, 3, styles.header(), "项目", header.projectName());
    row(sheet, 4, styles.header(), "生成批次", header.runId().toString());
    row(sheet, 5, styles.header(), "题目审核", "已完成");
    row(sheet, 6, styles.header(), "审批结论", "□ 同意导出　　□ 退回修改");
    row(sheet, 7, styles.header(), "审批人", "");
    row(sheet, 8, styles.header(), "审批日期", "");
    row(sheet, 9, styles.header(), "审批意见", "");
    row(sheet, 11, styles.header(), "套次", "原题号", "题型", "难度", "分值", "状态", "版本", "审核人", "审核时间", "审核意见", "导出题号");
    int index = 12;
    for (QuestionRow item : rows) {
      Row value = sheet.createRow(index++);
      cell(value, 0, item.variantLabel()); cell(value, 1, item.sequenceNo()); cell(value, 2, typeName(item));
      cell(value, 3, difficultyName(item.difficulty())); cell(value, 4, item.points()); cell(value, 5, item.status());
      cell(value, 6, item.questionVersion()); cell(value, 7, item.reviewerName());
      cell(value, 8, item.reviewedAt() == null ? "" : item.reviewedAt().toString());
      cell(value, 9, item.reviewComment()); cell(value, 10, numbers.get(item)); style(value, styles.body());
    }
    finishSheet(sheet, new int[] { 10, 10, 18, 10, 10, 14, 10, 18, 25, 40, 12 }, 12);
  }

  private void addExcelSection(Sheet sheet, int index, int columns, CellStyle style, String title) {
    row(sheet, index, style, title);
    sheet.getRow(index).setHeightInPoints(26);
    sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(index, index, 0, columns - 1));
  }

  private Payload attachmentPayload(RunHeader header) throws Exception {
    Path zip = Files.createTempFile("tiku-project-attachments-", ".zip");
    List<AttachmentRow> rows = loadAttachments(header.projectId());
    Set<String> names = new HashSet<>();
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
      String manifest = "命题项目：" + header.projectName() + "\n证据快照：V" + header.snapshotVersion() + "\n快照哈希：" + header.snapshotHash() + "\n\n";
      output.putNextEntry(new ZipEntry("资料清单.txt")); output.write(manifest.getBytes(StandardCharsets.UTF_8)); output.closeEntry();
      List<Map<String, Object>> manifestRows = new ArrayList<>();
      for (AttachmentRow row : rows) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sourceId", row.sourceId()); record.put("sourceRole", row.sourceRole()); record.put("name", row.name());
        record.put("sourceType", row.sourceType()); record.put("versionRef", row.versionRef()); record.put("status", row.status());
        Path input = null;
        try {
          input = storage.materialize(row.storagePath(), suffix(row.name()));
          if (Files.exists(input)) {
            String entryName = uniqueZipName(names, "附件/" + safeFileName(row.sourceRole()) + "/" + safeFileName(row.name()));
            output.putNextEntry(new ZipEntry(entryName)); Files.copy(input, output); output.closeEntry();
            record.put("included", true); record.put("archivePath", entryName);
          } else record.put("included", false);
        } catch (Exception error) {
          record.put("included", false); record.put("error", shorten(rootMessage(error), 300));
        } finally { storage.cleanupMaterialized(input); }
        manifestRows.add(record);
      }
      output.putNextEntry(new ZipEntry("manifest.json")); output.write(write(manifestRows).getBytes(StandardCharsets.UTF_8)); output.closeEntry();
    } catch (Exception error) {
      Files.deleteIfExists(zip); throw error;
    }
    return Payload.file("图纸与附件_" + safeFileName(header.projectName()) + ".zip", ZIP_TYPE, zip);
  }

  private List<AttachmentRow> loadAttachments(UUID projectId) {
    return jdbc.query("select s.source_document_id,s.cad_material_id,s.source_type,s.source_role,s.version_ref,coalesce(d.original_name,m.original_name) source_name,coalesce(d.media_type,m.media_type) media_type,coalesce(d.storage_key,m.storage_key) storage_path,coalesce(d.status,m.status) source_status from exam_project_sources s left join source_documents d on d.id=s.source_document_id left join cad_materials m on m.id=s.cad_material_id where s.project_id=? and s.enabled=true order by s.source_role,source_name",
        (rs, row) -> new AttachmentRow(rs.getObject("source_document_id", UUID.class) != null ? rs.getObject("source_document_id", UUID.class) : rs.getObject("cad_material_id", UUID.class),
            rs.getString("source_type"), rs.getString("source_role"), rs.getString("version_ref"), rs.getString("source_name"),
            rs.getString("media_type"), rs.getString("storage_path"), rs.getString("source_status")), projectId);
  }

  private List<QuestionRow> loadQuestions(UUID runId) {
    return jdbc.query("select i.variant_no,i.variant_label,i.sequence_no,i.question_type,i.type_label,i.difficulty,i.points,i.status,i.question_json,i.question_version,i.reviewer_id,i.review_comment,i.reviewed_at,coalesce(u.username,'') reviewer_name from exam_project_generation_items i left join app_users u on u.id=i.reviewer_id where i.run_id=? and i.status in ('APPROVED','APPROVED_WITH_RISK') order by i.variant_no,i.sequence_no",
        (rs, row) -> new QuestionRow(rs.getInt("variant_no"), rs.getString("variant_label"), rs.getInt("sequence_no"),
            rs.getString("question_type"), rs.getString("type_label"), rs.getString("difficulty"), rs.getInt("points"),
            rs.getString("status"), readMap(rs.getString("question_json")), rs.getInt("question_version"),
            rs.getString("reviewer_name"), Objects.toString(rs.getString("review_comment"), ""), instant(rs.getTimestamp("reviewed_at"))), runId);
  }

  private Map<Integer, List<QuestionRow>> byVariant(List<QuestionRow> rows) {
    Map<Integer, List<QuestionRow>> result = new LinkedHashMap<>();
    rows.stream().sorted(java.util.Comparator.comparingInt(QuestionRow::variantNo)
        .thenComparingInt(item -> QuestionTypeOrder.rank(item.questionType(), item.typeLabel()))
        .thenComparing(this::typeKey).thenComparingInt(QuestionRow::sequenceNo))
        .forEach(row -> result.computeIfAbsent(row.variantNo(), ignored -> new ArrayList<>()).add(row));
    return result;
  }

  private Map<QuestionRow, Integer> exportNumbers(List<QuestionRow> rows) {
    Map<QuestionRow, Integer> result = new LinkedHashMap<>();
    for (List<QuestionRow> variant : byVariant(rows).values()) {
      for (int index = 0; index < variant.size(); index++) result.put(variant.get(index), index + 1);
    }
    return result;
  }

  private String typeKey(QuestionRow row) { return QuestionTypeOrder.key(row.questionType(), row.typeLabel()); }
  private String typeName(QuestionRow row) { return QuestionTypeOrder.label(row.questionType(), row.typeLabel()); }

  private Map<String, List<QuestionRow>> byQuestionType(List<QuestionRow> rows) {
    Map<String, List<QuestionRow>> result = new LinkedHashMap<>();
    for (QuestionRow row : rows) result.computeIfAbsent(typeKey(row), ignored -> new ArrayList<>()).add(row);
    return result;
  }

  private String sectionTitle(int index, List<QuestionRow> rows) {
    int points = rows.stream().mapToInt(QuestionRow::points).sum();
    String uniform = rows.stream().map(QuestionRow::points).distinct().count() == 1
        ? "，每题" + rows.getFirst().points() + "分" : "";
    return sectionNumber(index) + "、" + typeName(rows.getFirst()) + "（共" + rows.size() + "题" + uniform + "，共" + points + "分）";
  }

  private String sectionNumber(int number) {
    String[] digits = { "", "一", "二", "三", "四", "五", "六", "七", "八", "九" };
    if (number < 10) return digits[number];
    if (number < 100) return (number / 10 == 1 ? "" : digits[number / 10]) + "十" + digits[number % 10];
    return Integer.toString(number);
  }

  private void store(ExportRunView run, String output, Payload payload) throws Exception {
    UUID artifactId = UUID.randomUUID();
    String key = "exam-project-exports/" + run.id() + "/" + artifactId + suffix(payload.filename());
    long size = payload.size();
    String sha256 = payload.sha256();
    String path;
    try (InputStream input = payload.open()) { path = storage.put(key, input, size, payload.mediaType()); }
    jdbc.update("insert into exam_project_export_artifacts(id,export_run_id,output_type,filename,media_type,storage_path,size_bytes,sha256,created_at) values(?,?,?,?,?,?,?,?,?)",
        artifactId, run.id(), output, payload.filename(), payload.mediaType(), path, size, sha256, Timestamp.from(Instant.now()));
  }

  private boolean hasArtifact(UUID runId, String output) {
    Integer count = jdbc.queryForObject("select count(*) from exam_project_export_artifacts where export_run_id=? and output_type=?", Integer.class, runId, output);
    return count != null && count > 0;
  }

  private List<String> normalizeOutputs(List<String> values) {
    List<String> selected = values == null || values.isEmpty() ? DEFAULT_OUTPUTS : values;
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : selected) {
      String normalized = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
      if (!ALL_OUTPUTS.contains(normalized)) throw new IllegalArgumentException("导出类型无效：" + value);
      result.add(normalized);
    }
    if (result.isEmpty()) throw new IllegalArgumentException("至少选择一种导出内容");
    return List.copyOf(result);
  }

  private String uniqueZipName(Set<String> names, String proposed) {
    String value = proposed;
    int counter = 2;
    while (!names.add(value)) {
      int dot = proposed.lastIndexOf('.');
      value = (dot > 0 ? proposed.substring(0, dot) : proposed) + "_" + counter++ + (dot > 0 ? proposed.substring(dot) : "");
    }
    return value;
  }

  private void title(Sheet sheet, Styles styles, String title, String project, String note) {
    row(sheet, 0, styles.title(), title);
    row(sheet, 1, styles.meta(), "项目：" + project);
    row(sheet, 2, styles.meta(), note);
  }

  private void row(Sheet sheet, int index, CellStyle style, String... values) {
    Row row = sheet.createRow(index);
    for (int i = 0; i < values.length; i++) cell(row, i, values[i]);
    style(row, style);
  }

  private void cell(Row row, int index, String value) { row.createCell(index).setCellValue(shortText(value)); }
  private void cell(Row row, int index, int value) { row.createCell(index).setCellValue(value); }
  private void style(Row row, CellStyle style) { for (Cell cell : row) cell.setCellStyle(style); }

  private void finishSheet(Sheet sheet, int[] widths, int freezeRows) {
    sheet.createFreezePane(0, freezeRows);
    for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, Math.min(255 * 256, widths[i] * 256));
    sheet.setAutobreaks(true);
    sheet.getPrintSetup().setLandscape(widths.length > 6);
    sheet.getPrintSetup().setFitWidth((short) 1);
    sheet.setFitToPage(true);
  }

  private String difficultyName(String value) { return "EASY".equals(value) ? "简单" : "HARD".equals(value) ? "困难" : "中等"; }
  private String text(Object value) { return shortText(Objects.toString(value, "")); }
  private String shortText(String value) { String safe = Objects.toString(value, ""); return safe.substring(0, Math.min(32_000, safe.length())); }
  private String safeFileName(String value) { String safe = Objects.toString(value, "项目").replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_"); return safe.isBlank() ? "项目" : safe.substring(0, Math.min(100, safe.length())); }
  private String shorten(String value, int max) { String safe = Objects.toString(value, ""); return safe.substring(0, Math.min(max, safe.length())); }
  private String shortenSheet(String value) { return shorten(value.replaceAll("[\\[\\]:*?/\\\\]", "_"), 31); }
  private String suffix(String name) { int dot = Objects.toString(name, "").lastIndexOf('.'); return dot >= 0 ? name.substring(dot) : ".bin"; }
  private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
  private String rootMessage(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return Objects.toString(value.getMessage(), value.getClass().getSimpleName()); }
  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("项目导出清单写入失败", error); } }
  private Map<String, Object> readMap(String raw) { try { return json.readValue(Objects.toString(raw, "{}"), new TypeReference<>() { }); } catch (Exception ignored) { return Map.of(); } }
  private List<String> readStrings(String raw) { try { return json.readValue(Objects.toString(raw, "[]"), new TypeReference<>() { }); } catch (Exception error) { throw new IllegalStateException("项目导出任务数据损坏", error); } }
  private List<Map<String, Object>> readMapList(String raw) { try { return json.readValue(Objects.toString(raw, "[]"), new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }

  private static String sha256(InputStream input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[1024 * 1024]; int read;
    while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
    return HexFormatHolder.format(digest.digest());
  }

  private record RunHeader(UUID runId, UUID projectId, UUID generationRunId, UUID evidenceSnapshotId, UUID snapshotId,
      int snapshotVersion, String snapshotHash, String sourcesJson, String projectName) { }
  private record QuestionRow(int variantNo, String variantLabel, int sequenceNo, String questionType, String typeLabel,
      String difficulty, int points, String status, Map<String, Object> question, int questionVersion,
      String reviewerName, String reviewComment, Instant reviewedAt) { }
  private record AttachmentRow(UUID sourceId, String sourceType, String sourceRole, String versionRef, String name,
      String mediaType, String storagePath, String status) { }

  private static final class Payload {
    private final String filename; private final String mediaType; private final byte[] bytes; private final Path file;
    private Payload(String filename, String mediaType, byte[] bytes, Path file) { this.filename = filename; this.mediaType = mediaType; this.bytes = bytes; this.file = file; }
    static Payload bytes(String filename, String mediaType, byte[] bytes) { return new Payload(filename, mediaType, bytes, null); }
    static Payload file(String filename, String mediaType, Path file) { return new Payload(filename, mediaType, null, file); }
    InputStream open() throws IOException { return bytes != null ? new ByteArrayInputStream(bytes) : Files.newInputStream(file); }
    long size() throws IOException { return bytes != null ? bytes.length : Files.size(file); }
    String sha256() throws Exception { try (InputStream input = open()) { return ExamProjectExportService.sha256(input); } }
    String filename() { return filename; } String mediaType() { return mediaType; }
    void close() throws IOException { if (file != null) Files.deleteIfExists(file); }
  }

  private static final class PdfCanvas {
    private static final float MARGIN = 42;
    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private final PDDocument document;
    private final PDFont font;
    private PDPageContentStream stream;
    private float y;

    PdfCanvas(PDDocument document, PDFont font) throws IOException {
      this.document = document;
      this.font = font;
      newPage();
    }

    void heading(String value) throws IOException {
      paragraph(value, 13);
      y -= 4;
    }

    void paragraph(String value, float size) throws IOException {
      String normalized = Objects.toString(value, "").replace('\t', ' ')
          .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", " ");
      String[] blocks = normalized.split("\\R", -1);
      for (String block : blocks) {
        if (block.isBlank()) {
          ensureSpace(size);
          y -= size + 3;
          continue;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < block.length(); i++) {
          String character = block.substring(i, i + 1);
          if (line.length() > 0 && width(line + character, size) > WIDTH - (2 * MARGIN)) {
            showLine(line.toString(), size);
            line.setLength(0);
          }
          line.append(character);
        }
        if (line.length() > 0) showLine(line.toString(), size);
      }
    }

    void image(byte[] png) throws IOException {
      BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
      if (source == null) throw new IOException("题目原图无法读取");
      float scale = Math.min((WIDTH - 2 * MARGIN) / source.getWidth(), 300f / source.getHeight());
      float drawWidth = source.getWidth() * scale;
      float drawHeight = source.getHeight() * scale;
      if (y < MARGIN + drawHeight) newPage();
      PDImageXObject image = PDImageXObject.createFromByteArray(document, png, "题目配图");
      stream.drawImage(image, MARGIN, y - drawHeight, drawWidth, drawHeight);
      y -= drawHeight + 10;
    }

    void close() throws IOException {
      if (stream != null) stream.close();
      stream = null;
    }

    private void showLine(String value, float size) throws IOException {
      ensureSpace(size);
      stream.beginText();
      stream.setFont(font, size);
      stream.newLineAtOffset(MARGIN, y);
      stream.showText(value);
      stream.endText();
      y -= size + 4;
    }

    private float width(CharSequence value, float size) throws IOException {
      return font.getStringWidth(value.toString()) / 1000f * size;
    }

    private void ensureSpace(float size) throws IOException {
      if (y < MARGIN + size + 4) newPage();
    }

    private void newPage() throws IOException {
      if (stream != null) stream.close();
      document.addPage(new PDPage(PDRectangle.A4));
      stream = new PDPageContentStream(document, document.getPage(document.getNumberOfPages() - 1));
      y = HEIGHT - MARGIN;
    }
  }

  private static final class Styles {
    private final CellStyle title; private final CellStyle meta; private final CellStyle header; private final CellStyle body;
    Styles(XSSFWorkbook workbook) {
      Font titleFont = workbook.createFont(); titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 16);
      title = workbook.createCellStyle(); title.setFont(titleFont); title.setAlignment(HorizontalAlignment.CENTER); title.setVerticalAlignment(VerticalAlignment.CENTER);
      Font headerFont = workbook.createFont(); headerFont.setBold(true); header = workbook.createCellStyle(); header.setFont(headerFont); header.setFillForegroundColor((short) 22); header.setFillPattern(FillPatternType.SOLID_FOREGROUND); header.setAlignment(HorizontalAlignment.CENTER); header.setVerticalAlignment(VerticalAlignment.CENTER); header.setBorderBottom(BorderStyle.THIN);
      meta = workbook.createCellStyle(); meta.setAlignment(HorizontalAlignment.LEFT); meta.setVerticalAlignment(VerticalAlignment.CENTER);
      body = workbook.createCellStyle(); body.setWrapText(true); body.setVerticalAlignment(VerticalAlignment.TOP); body.setBorderBottom(BorderStyle.THIN);
    }
    CellStyle title() { return title; } CellStyle meta() { return meta; } CellStyle header() { return header; } CellStyle body() { return body; }
  }

  private static final class HexFormatHolder {
    private static String format(byte[] bytes) { StringBuilder result = new StringBuilder(bytes.length * 2); for (byte value : bytes) result.append(String.format("%02x", value)); return result.toString(); }
  }

  public record CreateRequest(List<String> outputTypes) { }
  public record ExportRunView(UUID id, UUID projectId, UUID generationRunId, UUID evidenceSnapshotId, String status,
      String outputTypesJson, int requestedCount, int completedCount, String errorMessage, Instant createdAt,
      Instant startedAt, Instant finishedAt, Instant updatedAt, List<ArtifactView> artifacts) {
    ExportRunView withArtifacts(List<ArtifactView> value) { return new ExportRunView(id, projectId, generationRunId, evidenceSnapshotId, status, outputTypesJson, requestedCount, completedCount, errorMessage, createdAt, startedAt, finishedAt, updatedAt, value); }
  }
  public record ArtifactView(UUID id, UUID exportRunId, String outputType, String filename, String mediaType,
      long sizeBytes, String sha256, Instant createdAt) { }
  public record StoredArtifact(UUID id, UUID exportRunId, String outputType, String filename, String mediaType,
      String storagePath, long sizeBytes, String sha256, Instant createdAt, UUID ownerId) { }
}
