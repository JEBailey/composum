<%@page session="false" pageEncoding="utf-8"%>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2"%>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<sling:defineObjects />
<cpn:component id="browser" type="com.composum.sling.nodes.browser.Browser" scope="request">
    <ol class="breadcrumbs">
        <c:forEach var="parent" items="${browser.parents}">
            <li data-path="${parent.path}"><cpn:link href="/bin/browser.html${parent.pathEncoded}">${parent.nameEscaped}</cpn:link></li>
        </c:forEach>
        <li class="active" data-path="${browser.current.path}"><cpn:link href="/bin/browser.html${browser.current.pathEncoded}">${browser.current.nameEscaped}</cpn:link></li>
    </ol>
</cpn:component>
