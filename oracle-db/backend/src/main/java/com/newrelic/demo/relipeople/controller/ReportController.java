package com.newrelic.demo.relipeople.controller;

import com.newrelic.api.agent.NewRelic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin
public class ReportController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/payroll")
    public ResponseEntity<List<Map<String, Object>>> payroll(
            @RequestParam(name = "limit", required = false, defaultValue = "200") int limit) {

        String sql = "WITH current_salaries AS ( "
                + "  SELECT sh.emp_id, "
                + "         sh.salary AS current_salary, "
                + "         ROW_NUMBER() OVER (PARTITION BY sh.emp_id ORDER BY sh.effective_date DESC) AS rn "
                + "  FROM SALARY_HISTORY sh "
                + ") "
                + "SELECT e.emp_id AS \"empId\", "
                + "       e.first_name || ' ' || e.last_name AS \"fullName\", "
                + "       d.dept_name AS \"deptName\", "
                + "       jg.job_title AS \"jobTitle\", "
                + "       cs.current_salary AS \"currentSalary\", "
                + "       AVG(cs.current_salary) OVER (PARTITION BY e.dept_id) AS \"deptAvgSalary\", "
                + "       COUNT(e.emp_id) OVER (PARTITION BY e.dept_id) AS \"deptHeadcount\", "
                + "       LAG(sh2.salary) OVER (PARTITION BY e.emp_id ORDER BY sh2.effective_date) AS \"previousSalary\" "
                + "FROM EMPLOYEES e "
                + "JOIN DEPARTMENTS d ON e.dept_id = d.dept_id "
                + "JOIN JOB_GRADES jg ON e.job_id = jg.job_id "
                + "JOIN current_salaries cs ON e.emp_id = cs.emp_id AND cs.rn = 1 "
                + "LEFT JOIN SALARY_HISTORY sh2 ON e.emp_id = sh2.emp_id "
                + "ORDER BY d.dept_name, e.last_name "
                + "FETCH FIRST ? ROWS ONLY";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, limit);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/departments")
    public ResponseEntity<List<Map<String, Object>>> departments() {
        String sql = "SELECT NVL(d.dept_name, 'ALL DEPARTMENTS') AS \"deptName\", "
                + "       NVL(l.city, 'All Cities') AS \"city\", "
                + "       NVL(l.state, '-') AS \"state\", "
                + "       COUNT(DISTINCT e.emp_id) AS \"headcount\", "
                + "       ROUND(AVG(cs.current_salary), 2) AS \"avgCurrentSalary\", "
                + "       SUM(cs.current_salary) AS \"totalPayroll\" "
                + "FROM DEPARTMENTS d "
                + "JOIN LOCATIONS l ON d.location_id = l.location_id "
                + "LEFT JOIN EMPLOYEES e ON e.dept_id = d.dept_id "
                + "LEFT JOIN ( "
                + "    SELECT emp_id, salary AS current_salary "
                + "    FROM SALARY_HISTORY sh1 "
                + "    WHERE sh1.effective_date = ( "
                + "        SELECT MAX(sh2.effective_date) FROM SALARY_HISTORY sh2 "
                + "        WHERE sh2.emp_id = sh1.emp_id "
                + "    ) "
                + ") cs ON e.emp_id = cs.emp_id "
                + "GROUP BY ROLLUP(d.dept_name, l.city, l.state) "
                + "ORDER BY GROUPING(d.dept_name), d.dept_name NULLS LAST";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/performance")
    public ResponseEntity<List<Map<String, Object>>> performance() {
        // Nulls pass through for rollup rows so JSP ${empty row.X} rollup detection works
        String sql = "SELECT TO_CHAR(pr.review_year) AS \"reviewYear\", "
                + "       d.dept_name AS \"deptName\", "
                + "       jg.job_title AS \"jobTitle\", "
                + "       COUNT(pr.review_id) AS \"reviewCount\", "
                + "       ROUND(AVG(pr.score), 2) AS \"avgScore\", "
                + "       MIN(pr.score) AS \"minScore\", "
                + "       MAX(pr.score) AS \"maxScore\", "
                + "       SUM(CASE WHEN pr.score >= 4 THEN 1 ELSE 0 END) AS \"highPerformers\" "
                + "FROM PERFORMANCE_REVIEWS pr "
                + "JOIN EMPLOYEES e ON pr.emp_id = e.emp_id "
                + "JOIN DEPARTMENTS d ON e.dept_id = d.dept_id "
                + "JOIN JOB_GRADES jg ON e.job_id = jg.job_id "
                + "GROUP BY ROLLUP(pr.review_year, d.dept_name, jg.job_title) "
                + "ORDER BY GROUPING(pr.review_year), pr.review_year DESC NULLS LAST, "
                + "         GROUPING(d.dept_name), d.dept_name NULLS LAST";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/employee-detail-audit")
    public ResponseEntity<List<Map<String, Object>>> employeeDetailAudit(
            @RequestParam(name = "limit", required = false, defaultValue = "75") int limit,
            @RequestParam(name = "strategy", required = false, defaultValue = "nplus1") String strategy) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        String selectedStrategy = strategy == null ? "nplus1" : strategy.trim().toLowerCase();
        if (!"eager".equals(selectedStrategy)) {
            selectedStrategy = "nplus1";
        }

        NewRelic.addCustomParameter("employeeAudit.strategy", selectedStrategy);
        NewRelic.addCustomParameter("employeeAudit.limit", boundedLimit);
        NewRelic.addCustomParameter("employeeAudit.eagerLoaded", "eager".equals(selectedStrategy));

        if ("eager".equals(selectedStrategy)) {
            return ResponseEntity.ok(employeeDetailAuditEager(boundedLimit));
        }

        return ResponseEntity.ok(employeeDetailAuditNPlusOne(boundedLimit));
    }

    private List<Map<String, Object>> employeeDetailAuditNPlusOne(int boundedLimit) {
        String employeeSql = "SELECT e.emp_id AS \"empId\", "
                + "       e.first_name || ' ' || e.last_name AS \"fullName\", "
                + "       d.dept_name AS \"deptName\", "
                + "       jg.job_title AS \"jobTitle\" "
                + "FROM EMPLOYEES e "
                + "JOIN DEPARTMENTS d ON e.dept_id = d.dept_id "
                + "JOIN JOB_GRADES jg ON e.job_id = jg.job_id "
                + "ORDER BY e.last_name, e.first_name "
                + "FETCH FIRST ? ROWS ONLY";

        String salarySql = "SELECT sh.salary AS \"currentSalary\" "
                + "FROM SALARY_HISTORY sh "
                + "WHERE sh.emp_id = ? "
                + "ORDER BY sh.effective_date DESC "
                + "FETCH FIRST 1 ROW ONLY";

        String leaveSql = "SELECT "
                + "       SUM(CASE WHEN lr.status = 'PENDING' THEN 1 ELSE 0 END) AS \"pendingLeaves\", "
                + "       SUM(CASE WHEN lr.status = 'APPROVED' THEN 1 ELSE 0 END) AS \"approvedLeaves\", "
                + "       SUM(CASE WHEN lr.status = 'DENIED' THEN 1 ELSE 0 END) AS \"deniedLeaves\" "
                + "FROM LEAVE_REQUESTS lr "
                + "WHERE lr.emp_id = ?";

        String reviewSql = "SELECT ROUND(AVG(pr.score), 2) AS \"avgReviewScore\", "
                + "       MAX(pr.review_year) AS \"latestReviewYear\" "
                + "FROM PERFORMANCE_REVIEWS pr "
                + "WHERE pr.emp_id = ?";

        List<Map<String, Object>> employees = jdbcTemplate.queryForList(employeeSql, boundedLimit);

        // Intentional N+1 demo: one employee query, then three detail lookups per row.
        List<Map<String, Object>> rows = employees.stream().map(employee -> {
            Object empId = employee.get("empId");
            Map<String, Object> result = new LinkedHashMap<>(employee);

            List<Map<String, Object>> salaryRows = jdbcTemplate.queryForList(salarySql, empId);
            result.put("currentSalary", salaryRows.isEmpty() ? null : salaryRows.get(0).get("currentSalary"));

            Map<String, Object> leaveSummary = jdbcTemplate.queryForMap(leaveSql, empId);
            result.put("pendingLeaves", defaultZero(leaveSummary.get("pendingLeaves")));
            result.put("approvedLeaves", defaultZero(leaveSummary.get("approvedLeaves")));
            result.put("deniedLeaves", defaultZero(leaveSummary.get("deniedLeaves")));

            Map<String, Object> reviewSummary = jdbcTemplate.queryForMap(reviewSql, empId);
            result.put("avgReviewScore", reviewSummary.get("avgReviewScore"));
            result.put("latestReviewYear", reviewSummary.get("latestReviewYear"));

            return result;
        }).toList();

        return rows;
    }

    private List<Map<String, Object>> employeeDetailAuditEager(int boundedLimit) {
        String sql = "WITH target_employees AS ( "
                + "  SELECT emp_id, full_name, dept_name, job_title, employee_order "
                + "  FROM ( "
                + "    SELECT e.emp_id, "
                + "           e.first_name || ' ' || e.last_name AS full_name, "
                + "           d.dept_name, "
                + "           jg.job_title, "
                + "           ROW_NUMBER() OVER (ORDER BY e.last_name, e.first_name) AS employee_order "
                + "    FROM EMPLOYEES e "
                + "    JOIN DEPARTMENTS d ON e.dept_id = d.dept_id "
                + "    JOIN JOB_GRADES jg ON e.job_id = jg.job_id "
                + "  ) "
                + "  WHERE employee_order <= ? "
                + "), ranked_salaries AS ( "
                + "  SELECT sh.emp_id, "
                + "         sh.salary, "
                + "         ROW_NUMBER() OVER (PARTITION BY sh.emp_id ORDER BY sh.effective_date DESC) AS rn "
                + "  FROM SALARY_HISTORY sh "
                + "  JOIN target_employees te ON te.emp_id = sh.emp_id "
                + "), leave_summary AS ( "
                + "  SELECT lr.emp_id, "
                + "         SUM(CASE WHEN lr.status = 'PENDING' THEN 1 ELSE 0 END) AS pending_leaves, "
                + "         SUM(CASE WHEN lr.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved_leaves, "
                + "         SUM(CASE WHEN lr.status = 'DENIED' THEN 1 ELSE 0 END) AS denied_leaves "
                + "  FROM LEAVE_REQUESTS lr "
                + "  JOIN target_employees te ON te.emp_id = lr.emp_id "
                + "  GROUP BY lr.emp_id "
                + "), review_summary AS ( "
                + "  SELECT pr.emp_id, "
                + "         ROUND(AVG(pr.score), 2) AS avg_review_score, "
                + "         MAX(pr.review_year) AS latest_review_year "
                + "  FROM PERFORMANCE_REVIEWS pr "
                + "  JOIN target_employees te ON te.emp_id = pr.emp_id "
                + "  GROUP BY pr.emp_id "
                + ") "
                + "SELECT te.emp_id AS \"empId\", "
                + "       te.full_name AS \"fullName\", "
                + "       te.dept_name AS \"deptName\", "
                + "       te.job_title AS \"jobTitle\", "
                + "       rs.salary AS \"currentSalary\", "
                + "       NVL(ls.pending_leaves, 0) AS \"pendingLeaves\", "
                + "       NVL(ls.approved_leaves, 0) AS \"approvedLeaves\", "
                + "       NVL(ls.denied_leaves, 0) AS \"deniedLeaves\", "
                + "       rv.avg_review_score AS \"avgReviewScore\", "
                + "       rv.latest_review_year AS \"latestReviewYear\" "
                + "FROM target_employees te "
                + "LEFT JOIN ranked_salaries rs ON rs.emp_id = te.emp_id AND rs.rn = 1 "
                + "LEFT JOIN leave_summary ls ON ls.emp_id = te.emp_id "
                + "LEFT JOIN review_summary rv ON rv.emp_id = te.emp_id "
                + "ORDER BY te.employee_order";

        return jdbcTemplate.queryForList(sql, boundedLimit);
    }

    @GetMapping("/leave-backlog")
    public ResponseEntity<List<Map<String, Object>>> leaveBacklog() {
        // Correlated subquery runs once per department — each fires a full scan of
        // LEAVE_REQUESTS + EMPLOYEES on unindexed FK dept_id.
        String sql = "SELECT d.dept_name AS \"deptName\", "
                + "       COUNT(DISTINCT e.emp_id) AS \"totalEmployees\", "
                + "       COUNT(lr.request_id) AS \"totalLeaves\", "
                + "       SUM(CASE WHEN lr.status = 'PENDING' THEN 1 ELSE 0 END) AS \"pendingCount\", "
                + "       ROUND(AVG(lr.end_date - lr.start_date), 1) AS \"avgLeaveDays\", "
                + "       (SELECT COUNT(*) "
                + "          FROM LEAVE_REQUESTS lr2 "
                + "          JOIN EMPLOYEES e2 ON lr2.emp_id = e2.emp_id "
                + "         WHERE e2.dept_id = d.dept_id "
                + "           AND lr2.status = 'PENDING') AS \"confirmedPending\" "
                + "FROM DEPARTMENTS d "
                + "LEFT JOIN EMPLOYEES e ON e.dept_id = d.dept_id "
                + "LEFT JOIN LEAVE_REQUESTS lr ON lr.emp_id = e.emp_id "
                + "GROUP BY d.dept_id, d.dept_name "
                + "ORDER BY COUNT(lr.request_id) DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/salary-progression")
    public ResponseEntity<List<Map<String, Object>>> salaryProgression() {
        // Full hash join of EMPLOYEES (50K) x SALARY_HISTORY (200K) on unindexed FK.
        // Oracle must aggregate all 200K rows before applying FETCH FIRST order.
        String sql = "SELECT e.first_name || ' ' || e.last_name AS \"employeeName\", "
                + "       d.dept_name AS \"deptName\", "
                + "       jg.job_title AS \"jobTitle\", "
                + "       COUNT(sh.salary_id) AS \"salaryChanges\", "
                + "       MIN(sh.salary) AS \"startingSalary\", "
                + "       MAX(sh.salary) AS \"currentSalary\", "
                + "       MAX(sh.salary) - MIN(sh.salary) AS \"totalGrowth\", "
                + "       ROUND((MAX(sh.salary) - MIN(sh.salary)) "
                + "             / NULLIF(MIN(sh.salary), 0) * 100, 1) AS \"growthPct\" "
                + "FROM EMPLOYEES e "
                + "JOIN SALARY_HISTORY sh ON sh.emp_id = e.emp_id "
                + "JOIN DEPARTMENTS d ON e.dept_id = d.dept_id "
                + "JOIN JOB_GRADES jg ON e.job_id = jg.job_id "
                + "GROUP BY e.emp_id, e.first_name, e.last_name, d.dept_name, jg.job_title "
                + "ORDER BY MAX(sh.salary) - MIN(sh.salary) DESC "
                + "FETCH FIRST 500 ROWS ONLY";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(rows);
    }

    private Object defaultZero(Object value) {
        return value == null ? 0 : value;
    }
}
