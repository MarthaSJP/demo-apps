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
            @RequestParam(name = "limit", required = false, defaultValue = "75") int limit,
            @RequestParam(name = "strategy", required = false, defaultValue = "optimized") String strategy) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));

        NewRelic.addCustomParameter("employeeAudit.limit", boundedLimit);
        NewRelic.addCustomParameter("employeeAudit.queryPattern", strategy);

        // デモ目的で、N+1パターンと最適化パターンを切り替え可能にする
        if ("nplus1".equalsIgnoreCase(strategy)) {
            return ResponseEntity.ok(employeeDetailAuditNPlusOne(boundedLimit));
        } else {
            return ResponseEntity.ok(employeeDetailAuditOptimized(boundedLimit));
        }
    }

    /**
     * 最適化版: N+1問題を解消したバルククエリ実装
     * 
     * 【最適化ポイント】
     * 1. CTEを使用して、必要な従業員IDを事前に特定
     * 2. 給与、休暇、レビューデータを一括取得（IN句使用）
     * 3. 100件単位のバッチ処理でメモリ使用量を制御
     * 4. emp_idのインデックスを活用した効率的なJOIN
     * 
     * 【期待される効果】
     * - クエリ実行回数: O(N*3) → O(N/100 + 1)
     * - データベース負荷: 99%以上削減
     * - レスポンスタイム: 大幅改善
     */
    private List<Map<String, Object>> employeeDetailAuditOptimized(int boundedLimit) {
        // バッチサイズ: メモリエクスプロージョン防止のため100件単位で処理
        final int BATCH_SIZE = 100;
        
        // ステップ1: 対象従業員の基本情報を取得
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
        
        // 従業員が存在しない場合は空リストを返す（空チェックのガード節）
        if (employees.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        
        // バッチ処理: 100件ごとに分割して処理
        for (int i = 0; i < employees.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, employees.size());
            List<Map<String, Object>> batch = employees.subList(i, endIndex);
            
            // バッチ内の従業員IDリストを抽出
            List<Object> empIds = batch.stream()
                    .map(emp -> emp.get("empId"))
                    .collect(Collectors.toList());
            
            // IN句用のプレースホルダーを生成（例: ?,?,?）
            String placeholders = empIds.stream()
                    .map(id -> "?")
                    .collect(Collectors.joining(","));
            
            // ステップ2: バッチ内の全従業員の給与情報を一括取得
            String salarySql = "WITH ranked_salaries AS ( "
                    + "  SELECT sh.emp_id, "
                    + "         sh.salary, "
                    + "         ROW_NUMBER() OVER (PARTITION BY sh.emp_id ORDER BY sh.effective_date DESC) AS rn "
                    + "  FROM SALARY_HISTORY sh "
                    + "  WHERE sh.emp_id IN (" + placeholders + ") "
                    + ") "
                    + "SELECT emp_id AS \"empId\", salary AS \"currentSalary\" "
                    + "FROM ranked_salaries "
                    + "WHERE rn = 1";
            
            List<Map<String, Object>> salaries = jdbcTemplate.queryForList(salarySql, empIds.toArray());
            Map<Object, Object> salaryMap = salaries.stream()
                    .collect(Collectors.toMap(
                            row -> row.get("empId"),
                            row -> row.get("currentSalary")
                    ));
            
            // ステップ3: バッチ内の全従業員の休暇情報を一括取得
            String leaveSql = "SELECT lr.emp_id AS \"empId\", "
                    + "       SUM(CASE WHEN lr.status = 'PENDING' THEN 1 ELSE 0 END) AS \"pendingLeaves\", "
                    + "       SUM(CASE WHEN lr.status = 'APPROVED' THEN 1 ELSE 0 END) AS \"approvedLeaves\", "
                    + "       SUM(CASE WHEN lr.status = 'DENIED' THEN 1 ELSE 0 END) AS \"deniedLeaves\" "
                    + "FROM LEAVE_REQUESTS lr "
                    + "WHERE lr.emp_id IN (" + placeholders + ") "
                    + "GROUP BY lr.emp_id";
            
            List<Map<String, Object>> leaves = jdbcTemplate.queryForList(leaveSql, empIds.toArray());
            Map<Object, Map<String, Object>> leaveMap = leaves.stream()
                    .collect(Collectors.toMap(
                            row -> row.get("empId"),
                            row -> row
                    ));
            
            // ステップ4: バッチ内の全従業員のレビュー情報を一括取得
            String reviewSql = "SELECT pr.emp_id AS \"empId\", "
                    + "       ROUND(AVG(pr.score), 2) AS \"avgReviewScore\", "
                    + "       MAX(pr.review_year) AS \"latestReviewYear\" "
                    + "FROM PERFORMANCE_REVIEWS pr "
                    + "WHERE pr.emp_id IN (" + placeholders + ") "
                    + "GROUP BY pr.emp_id";
            
            List<Map<String, Object>> reviews = jdbcTemplate.queryForList(reviewSql, empIds.toArray());
            Map<Object, Map<String, Object>> reviewMap = reviews.stream()
                    .collect(Collectors.toMap(
                            row -> row.get("empId"),
                            row -> row
                    ));
            
            // ステップ5: メモリ上で結果をマッピング（元の並び順を維持）
            for (Map<String, Object> employee : batch) {
                Object empId = employee.get("empId");
                Map<String, Object> result = new LinkedHashMap<>(employee);
                
                // 給与情報をマッピング
                result.put("currentSalary", salaryMap.get(empId));
                
                // 休暇情報をマッピング（データが存在しない場合は0を設定）
                Map<String, Object> leaveData = leaveMap.get(empId);
                if (leaveData != null) {
                    result.put("pendingLeaves", defaultZero(leaveData.get("pendingLeaves")));
                    result.put("approvedLeaves", defaultZero(leaveData.get("approvedLeaves")));
                    result.put("deniedLeaves", defaultZero(leaveData.get("deniedLeaves")));
                } else {
                    result.put("pendingLeaves", 0);
                    result.put("approvedLeaves", 0);
                    result.put("deniedLeaves", 0);
                }
                
                // レビュー情報をマッピング
                Map<String, Object> reviewData = reviewMap.get(empId);
                if (reviewData != null) {
                    result.put("avgReviewScore", reviewData.get("avgReviewScore"));
                    result.put("latestReviewYear", reviewData.get("latestReviewYear"));
                } else {
                    result.put("avgReviewScore", null);
                    result.put("latestReviewYear", null);
                }
                
                results.add(result);
            }
        }
        
        return results;
    }

    /**
     * N+1パターン版（デモ・比較用）
     * 
     * 【警告】このメソッドは意図的にN+1問題を発生させています。
     * 本番環境では使用しないでください。
     * 
     * 【問題点】
     * - 従業員1人につき3回のクエリを実行
     * - 75人の場合: 1 + (75 * 3) = 226回のクエリ
     * - データベースへの往復回数が膨大
     * - 全表スキャンが発生する可能性
     */
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
