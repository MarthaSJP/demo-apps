<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ include file="header.jspf" %>

<h1>Employee Detail Audit <span class="page-badge">N+1 Query Pattern</span></h1>
<p style="color:#666; margin-top:-8px;">Detailed employee audit view combining salary, leave, and performance summaries.</p>

<c:choose>
    <c:when test="${empty employeeAuditData}">
        <div class="card empty-state"><p>No employee audit data available.</p></div>
    </c:when>
    <c:otherwise>
        <table>
            <thead>
                <tr>
                    <th>Employee ID</th>
                    <th>Name</th>
                    <th>Department</th>
                    <th>Job Title</th>
                    <th class="num">Current Salary</th>
                    <th class="num">Pending Leaves</th>
                    <th class="num">Approved Leaves</th>
                    <th class="num">Denied Leaves</th>
                    <th class="num">Avg Review</th>
                    <th class="num">Latest Review</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach var="row" items="${employeeAuditData}">
                    <tr>
                        <td><c:out value="${row.empId}"/></td>
                        <td>
                            <a class="emp-link" href="/employees/<c:out value='${row.empId}'/>">
                                <c:out value="${row.fullName}"/>
                            </a>
                        </td>
                        <td><c:out value="${row.deptName}"/></td>
                        <td><c:out value="${row.jobTitle}"/></td>
                        <td class="num">
                            <c:choose>
                                <c:when test="${empty row.currentSalary}">—</c:when>
                                <c:otherwise>
                                    <fmt:formatNumber value="${row.currentSalary}" type="currency" currencySymbol="$" maxFractionDigits="0"/>
                                </c:otherwise>
                            </c:choose>
                        </td>
                        <td class="num"><c:out value="${row.pendingLeaves}"/></td>
                        <td class="num"><c:out value="${row.approvedLeaves}"/></td>
                        <td class="num"><c:out value="${row.deniedLeaves}"/></td>
                        <td class="num">
                            <c:choose>
                                <c:when test="${empty row.avgReviewScore}">—</c:when>
                                <c:otherwise><fmt:formatNumber value="${row.avgReviewScore}" maxFractionDigits="2"/></c:otherwise>
                            </c:choose>
                        </td>
                        <td class="num">
                            <c:choose>
                                <c:when test="${empty row.latestReviewYear}">—</c:when>
                                <c:otherwise><c:out value="${row.latestReviewYear}"/></c:otherwise>
                            </c:choose>
                        </td>
                    </tr>
                </c:forEach>
            </tbody>
        </table>
    </c:otherwise>
</c:choose>

<%@ include file="footer.jspf" %>
