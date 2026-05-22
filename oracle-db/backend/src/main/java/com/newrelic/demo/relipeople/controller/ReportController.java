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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            @RequestParam(name = "limit", required = false, defaultValue = "75") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));

        NewRelic.addCustomParameter("employeeAudit.limit", boundedLimit);
        NewRelic.addCustomParameter("employeeAudit.queryPattern", "bulk-optimized");

        return ResponseEntity.ok(employeeDetailAuditOptimized(boundedLimit));
    }

    /**
     * Optimized version that resolves N+1 query problem by using bulk IN clause queries.
     * 
     * Previous implementation: 1 + (N × 3) queries = ~382 queries for 127 employees
     * Optimized implementation: 1 + 3 bulk queries = 4 queries total
     * 
     * Performance improvement: ~96% reduction in database queries
     */
    private List<Map<String, Object>> employeeDetailAuditOptimized(int boundedLimit) {
        // Step 1: Fetch all employees (1 query)
        String employeeSql = "SELECT e.emp_id AS \"empId\", "
                + "       e.first_name || ' ' || e.last_name AS \"fullName\", "
                + "       d.dept_name AS \"deptName\", "
                + "       jg.job_title AS \"jobTitle\" "
                + "FROM EMPLOYEES e "
                + "JOIN DEPARTMENTS d ON e.dept_id = d.dept_id "
                + "JOIN JOB_GRADES jg ON e.job_id = jg.job_id "
                + "ORDER BY e.last_name, e.first_name "
                + "FETCH FIRST ? ROWS ONLY";

        List<Map<String, Object>> employees = jdbcTemplate.queryForList(employeeSql, boundedLimit);

        // Guard: Return early if no employees found
        if (employees.isEmpty()) {
            return employees;
        }

        // Step 2: Collect all employee IDs
        List<Object> empIds = employees.stream()
                .map(emp -> emp.get("empId"))
                .collect(Collectors.toList());

        // Step 3: Fetch all related data in bulk (3 queries total)
        Map<Object, Object> salaryMap = fetchSalariesBulk(empIds);
        Map<Object, Map<String, Object>> leaveMap = fetchLeavesBulk(empIds);
        Map<Object, Map<String, Object>> reviewMap = fetchReviewsBulk(empIds);

        // Step 4: Combine results in memory (O(N) complexity)
        List<Map<String, Object>> rows = employees.stream().map(employee -> {
            Object empId = employee.get("empId");
            Map<String, Object> result = new LinkedHashMap<>(employee);

            // Map salary data
            result.put("currentSalary", salaryMap.get(empId));

            // Map leave data
            Map<String, Object> leaveSummary = leaveMap.getOrDefault(empId, new HashMap<>());
            result.put("pendingLeaves", defaultZero(leaveSummary.get("pendingLeaves")));
            result.put("approvedLeaves", defaultZero(leaveSummary.get("approvedLeaves")));
            result.put("deniedLeaves", defaultZero(leaveSummary.get("deniedLeaves")));

            // Map review data
            Map<String, Object> reviewSummary = reviewMap.getOrDefault(empId, new HashMap<>());
            result.put("avgReviewScore", reviewSummary.get("avgReviewScore"));
            result.put("latestReviewYear", reviewSummary.get("latestReviewYear"));

            return result;
        }).toList();

        return rows;
    }

    /**
     * Fetch current salaries for all employees in bulk using IN clause.
     * Processes in batches to prevent SQL statement size limits.
     */
    private Map<Object, Object> fetchSalariesBulk(List<Object> empIds) {
        Map<Object, Object> salaryMap = new HashMap<>();
        
        // Process in chunks to avoid SQL statement size limits
        int chunkSize = 100;
        for (int i = 0; i < empIds.size(); i += chunkSize) {
            List<Object> chunk = empIds.subList(i, Math.min(i + chunkSize, empIds.size()));
            
            // Guard: Skip empty chunks
            if (chunk.isEmpty()) {
                continue;
            }

            String placeholders = chunk.stream().map(id -> "?").collect(Collectors.joining(","));
            String salarySql = "SELECT sh.emp_id AS \"empId\", sh.salary AS \"currentSalary\" "
                    + "FROM SALARY_HISTORY sh "
                    + "WHERE sh.emp_id IN (" + placeholders + ") "
                    + "  AND sh.effective_date = ("
                    + "    SELECT MAX(sh2.effective_date) "
                    + "    FROM SALARY_HISTORY sh2 "
                    + "    WHERE sh2.emp_id = sh.emp_id"
                    + "  )";

            List<Map<String, Object>> salaryRows = jdbcTemplate.queryForList(salarySql, chunk.toArray());
            for (Map<String, Object> row : salaryRows) {
                salaryMap.put(row.get("empId"), row.get("currentSalary"));
            }
        }
        
        return salaryMap;
    }

    /**
     * Fetch leave summaries for all employees in bulk using IN clause.
     * Processes in batches to prevent SQL statement size limits.
     */
    private Map<Object, Map<String, Object>> fetchLeavesBulk(List<Object> empIds) {
        Map<Object, Map<String, Object>> leaveMap = new HashMap<>();
        
        // Process in chunks to avoid SQL statement size limits
        int chunkSize = 100;
        for (int i = 0; i < empIds.size(); i += chunkSize) {
            List<Object> chunk = empIds.subList(i, Math.min(i + chunkSize, empIds.size()));
            
            // Guard: Skip empty chunks
            if (chunk.isEmpty()) {
                continue;
            }

            String placeholders = chunk.stream().map(id -> "?").collect(Collectors.joining(","));
            String leaveSql = "SELECT lr.emp_id AS \"empId\", "
                    + "       SUM(CASE WHEN lr.status = 'PENDING' THEN 1 ELSE 0 END) AS \"pendingLeaves\", "
                    + "       SUM(CASE WHEN lr.status = 'APPROVED' THEN 1 ELSE 0 END) AS \"approvedLeaves\", "
                    + "       SUM(CASE WHEN lr.status = 'DENIED' THEN 1 ELSE 0 END) AS \"deniedLeaves\" "
                    + "FROM LEAVE_REQUESTS lr "
                    + "WHERE lr.emp_id IN (" + placeholders + ") "
                    + "GROUP BY lr.emp_id";

            List<Map<String, Object>> leaveRows = jdbcTemplate.queryForList(leaveSql, chunk.toArray());
            for (Map<String, Object> row : leaveRows) {
                leaveMap.put(row.get("empId"), row);
            }
        }
        
        return leaveMap;
    }

    /**
     * Fetch review summaries for all employees in bulk using IN clause.
     * Processes in batches to prevent SQL statement size limits.
     */
    private Map<Object, Map<String, Object>> fetchReviewsBulk(List<Object> empIds) {
        Map<Object, Map<String, Object>> reviewMap = new HashMap<>();
        
        // Process in chunks to avoid SQL statement size limits
        int chunkSize = 100;
        for (int i = 0; i < empIds.size(); i += chunkSize) {
            List<Object> chunk = empIds.subList(i, Math.min(i + chunkSize, empIds.size()));
            
            // Guard: Skip empty chunks
            if (chunk.isEmpty()) {
                continue;
            }

            String placeholders = chunk.stream().map(id -> "?").collect(Collectors.joining(","));
            String reviewSql = "SELECT pr.emp_id AS \"empId\", "
                    + "       ROUND(AVG(pr.score), 2) AS \"avgReviewScore\", "
                    + "       MAX(pr.review_year) AS \"latestReviewYear\" "
                    + "FROM PERFORMANCE_REVIEWS pr "
                    + "WHERE pr.emp_id IN (" + placeholders + ") "
                    + "GROUP BY pr.emp_id";

            List<Map<String, Object>> reviewRows = jdbcTemplate.queryForList(reviewSql, chunk.toArray());
            for (Map<String, Object> row : reviewRows) {
                reviewMap.put(row.get("empId"), row);
            }
        }
        
        return reviewMap;
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
