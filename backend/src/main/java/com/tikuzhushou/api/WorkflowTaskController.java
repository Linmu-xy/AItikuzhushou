package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.workflow.WorkflowTaskService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflow-tasks")
public class WorkflowTaskController {
  private final WorkflowTaskService tasks;
  private final AdminAuditService audit;
  public WorkflowTaskController(WorkflowTaskService tasks, AdminAuditService audit) {
    this.tasks = tasks; this.audit = audit;
  }
  @GetMapping("/{id}") WorkflowTaskService.Task get(@PathVariable UUID id) { return tasks.get(id); }
  @GetMapping List<WorkflowTaskService.Task> list(@RequestParam(defaultValue = "50") int limit) { return tasks.list(limit); }
  @PostMapping("/{id}/cancel") WorkflowTaskService.Task cancel(@PathVariable UUID id) {
    WorkflowTaskService.Task cancelled = tasks.cancel(id);
    audit.record(AdminAuditService.WORKFLOW_TASK_CANCEL, AdminAuditService.TARGET_TASK, id, null,
        Map.of("taskType", cancelled.taskType()));
    return cancelled;
  }
}
