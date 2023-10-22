/*
 * Copyright (c) 2023 Contributors to the Eclipse Foundation
 * Copyright (c) 1997-2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.catalina.authenticator;

import static com.sun.logging.LogCleanerUtil.neutralizeForLog;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;
import static org.apache.catalina.LogFacade.UNEXPECTED_ERROR_FORWARDING_TO_LOGIN_PAGE;
import static org.apache.catalina.authenticator.Constants.FORM_ACTION;
import static org.apache.catalina.authenticator.Constants.FORM_METHOD;
import static org.apache.catalina.authenticator.Constants.FORM_PRINCIPAL_NOTE;
import static org.apache.catalina.authenticator.Constants.FORM_REQUEST_NOTE;
import static org.apache.catalina.authenticator.Constants.REQ_SSO_VERSION_NOTE;
import static org.apache.catalina.authenticator.Constants.SESS_PASSWORD_NOTE;
import static org.apache.catalina.authenticator.Constants.SESS_USERNAME_NOTE;

import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;

import org.apache.catalina.HttpRequest;
import org.apache.catalina.HttpResponse;
import org.apache.catalina.Realm;
import org.apache.catalina.Session;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityConstraint;
import org.glassfish.grizzly.http.util.ByteChunk;
import org.glassfish.grizzly.http.util.CharChunk;
import org.glassfish.grizzly.http.util.MessageBytes;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * An <b>Authenticator</b> and <b>Valve</b> implementation of FORM BASED Authentication, as described in the Servlet API
 * Specification, Version 2.2.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Revision: 1.8.2.2 $ $Date: 2008/04/17 18:37:04 $
 */

public class FormAuthenticator extends AuthenticatorBase {

    // -------------------------------------------------- Instance Variables

    /**
     * Descriptive information about this implementation.
     */
    protected static final String info = "org.apache.catalina.authenticator.FormAuthenticator/1.0";

    // ---------------------------------------------------------- Properties

    /**
     * Return descriptive information about this Valve implementation.
     */
    @Override
    public String getInfo() {
        return info;
    }

    @Override
    protected String getAuthMethod() {
        return HttpServletRequest.FORM_AUTH;
    }

    // ------------------------------------------------------- Public Methods

    /**
     * Authenticate the user making this request, based on the specified login configuration. Return <code>true</code> if
     * any specified constraint has been satisfied, or <code>false</code> if we have created a response challenge already.
     *
     * @param request Request we are processing
     * @param response Response we are creating
     * @param config Login configuration describing how authentication should be performed
     *
     * @exception IOException if an input/output error occurs
     */
    @Override
    public boolean authenticate(HttpRequest request, HttpResponse response, LoginConfig config) throws IOException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request.getRequest();
        HttpServletResponse httpServletResponse = (HttpServletResponse) response.getResponse();
        Session session = null;

        String contextPath = httpServletRequest.getContextPath();
        String requestURI = request.getDecodedRequestURI();

        // Is this the action request from the login page?
        boolean loginAction = requestURI.startsWith(contextPath) && requestURI.endsWith(FORM_ACTION);

        // Have we already authenticated someone?
        Principal principal = httpServletRequest.getUserPrincipal();

        // Treat the first and any subsequent j_security_check requests the
        // same, by letting them fall through to the j_security_check
        // processing section of this method.
        if (principal != null && !loginAction) {
            if (log.isLoggable(FINE)) {
                log.log(FINE, neutralizeForLog("Already authenticated '" + principal.getName() + "'"));
            }

            String ssoId = (String) request.getNote(Constants.REQ_SSOID_NOTE);
            if (ssoId != null) {
                getSession(request, true);
            }

            return true;
        }

        // Have we authenticated this user before but have caching disabled?
        // Treat the first and any subsequent j_security_check requests the
        // same, by letting them fall through to the j_security_check
        // processing section of this method.
        if (!cache && !loginAction) {
            session = getSession(request, true);
            /*
             * Do not log session if (log.isLoggable(Level.FINE)) log.log(Level.FINE, "Checking for reauthenticate in session " +
             * session);
             */
            String username = (String) session.getNote(SESS_USERNAME_NOTE);
            char[] password = (char[]) session.getNote(SESS_PASSWORD_NOTE);
            if ((username != null) && (password != null)) {
                if (log.isLoggable(FINE)) {
                    log.log(FINE, neutralizeForLog("Reauthenticating username '" + username + "'"));
                }
                principal = context.getRealm().authenticate(request, username, password);
                if (principal != null) {
                    session.setNote(FORM_PRINCIPAL_NOTE, principal);
                    if (!matchRequest(request)) {
                        register(request, response, principal, FORM_METHOD, username, password);
                        return (true);
                    }
                }
                if (log.isLoggable(FINE)) {
                    log.log(FINE, "Reauthentication failed, proceed normally");
                }
            }
        }

        // Is this the re-submit of the original request URI after successful
        // authentication? If so, forward the *original* request instead.
        if (matchRequest(request)) {
            session = getSession(request, true);

            principal = (Principal) session.getNote(FORM_PRINCIPAL_NOTE);
            register(request, response, principal, FORM_METHOD, (String) session.getNote(SESS_USERNAME_NOTE),
                    (char[]) session.getNote(SESS_PASSWORD_NOTE));
            String ssoId = (String) request.getNote(Constants.REQ_SSOID_NOTE);
            if (ssoId != null) {
                associate(ssoId, getSsoVersion(request), session);
            }

            if (restoreRequest(request, session)) {
                log.log(FINE, "Proceed to restored request");
                return true;
            } else {
                log.log(FINE, "Restore of original request failed");
                httpServletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return false;
            }
        }

        // Acquire references to objects we will need to evaluate
        MessageBytes uriMB = MessageBytes.newInstance();
        CharChunk uriCC = uriMB.getCharChunk();
        uriCC.setLimit(-1);
        response.setContext(request.getContext());

        // No -- Save this request and redirect to the form login page
        if (!loginAction) {
            session = getSession(request, true);

            saveRequest(request, session);

            forwardToLoginPage(request, response, config);

            return false;
        }

        // Yes -- Validate the specified credentials and redirect
        // to the error page if they are not correct
        Realm realm = context.getRealm();
        String username = httpServletRequest.getParameter(Constants.FORM_USERNAME);
        String pwd = httpServletRequest.getParameter(Constants.FORM_PASSWORD);
        char[] password = ((pwd != null) ? pwd.toCharArray() : null);

        if (log.isLoggable(FINE)) {
            log.log(FINE, neutralizeForLog("Authenticating username '" + username + "'"));
        }

        principal = realm.authenticate(request, username, password);
        if (principal == null) {
            forwardToErrorPage(request, response, config);
            return false;
        }

        // Save the authenticated Principal in our session
        if (log.isLoggable(FINE)) {
            log.log(FINE, neutralizeForLog("Authentication of '" + username + "' was successful"));
        }
        if (session == null) {
            session = getSession(request, true);
        }
        session.setNote(FORM_PRINCIPAL_NOTE, principal);

        // If we are not caching, save the username and password as well
        if (!cache) {
            session.setNote(SESS_USERNAME_NOTE, username);
            session.setNote(SESS_PASSWORD_NOTE, password);
        }

        // Redirect the user to the original request URI (which will cause
        // the original request to be restored)
        requestURI = savedRequestURL(session);
        if (requestURI == null) {
            // requestURI will be null if the login form is submitted
            // directly, i.e., if there has not been any original request
            // that was stored away before the redirect to the login form was
            // issued. In this case, assume that the original request has been
            // for the context root, and have the welcome page mechanism take
            // care of it
            requestURI = httpServletRequest.getContextPath() + "/";

            register(
                request, response, principal,
                FORM_METHOD,
                (String) session.getNote(SESS_USERNAME_NOTE),
                (char[]) session.getNote(SESS_PASSWORD_NOTE));

            String ssoId = (String) request.getNote(Constants.REQ_SSOID_NOTE);
            if (ssoId != null) {
                associate(ssoId, getSsoVersion(request), session);
            }
        }

        if (log.isLoggable(FINE)) {
            log.log(FINE, neutralizeForLog("Redirecting to original '" + requestURI + "'"));
        }

        httpServletResponse.sendRedirect(httpServletResponse.encodeRedirectURL(requestURI));

        return false;
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Does this request match the saved one (so that it must be the redirect we signaled after successful authentication?
     *
     * @param request The request to be verified
     */
    protected boolean matchRequest(HttpRequest request) {
        // Has a session been created?
        Session session = getSession(request, false);
        if (session == null) {
            return (false);
        }

        // Is there a saved request?
        SavedRequest sreq = (SavedRequest) session.getNote(FORM_REQUEST_NOTE);
        // Is there a saved principal?
        if ((sreq == null) || (session.getNote(FORM_PRINCIPAL_NOTE) == null)) {
            return (false);
        }

        // Does the request URI match?
        HttpServletRequest hreq = (HttpServletRequest) request.getRequest();
        String requestURI = hreq.getRequestURI();
        if (requestURI == null) {
            return false;
        }

        return requestURI.equals(sreq.getRequestURI());

    }

    /**
     * Restore the original request from information stored in our session. If the original request is no longer present
     * (because the session timed out), return <code>false</code>; otherwise, return <code>true</code>.
     *
     * @param request The request to be restored
     * @param session The session containing the saved information
     */
    protected boolean restoreRequest(HttpRequest request, Session session) throws IOException {
        // Retrieve and remove the SavedRequest object from our session
        SavedRequest saved = (SavedRequest) session.getNote(FORM_REQUEST_NOTE);
        /*
         * PWC 6463046: Do not remove the saved request: It will be needed again in case another j_security_check is sent. The
         * saved request will be purged when the session expires. session.removeNote(Constants.FORM_REQUEST_NOTE);
         */
        session.removeNote(FORM_PRINCIPAL_NOTE);
        if (saved == null) {
            return false;
        }

        // Swallow any request body since we will be replacing it
        // Need to do this before headers are restored as AJP connector uses
        // content length header to determine how much data needs to be read for
        // request body
        byte[] buffer = new byte[4096];
        InputStream is = request.getStream();
        while (is.read(buffer) >= 0) {
            // Ignore request body
        }

        // Modify our current request to reflect the original one
        request.clearCookies();
        Iterator<Cookie> cookies = saved.getCookies();
        while (cookies.hasNext()) {
            request.addCookie(cookies.next());
        }

        String method = saved.getMethod();
        boolean cachable = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        request.clearHeaders();
        Iterator<String> names = saved.getHeaderNames();
        while (names.hasNext()) {
            String name = names.next();
            // The browser isn't expecting this conditional response now.
            // Assuming that it can quietly recover from an unexpected 412.
            // BZ 43687
            if (!("If-Modified-Since".equalsIgnoreCase(name) || (cachable && "If-None-Match".equalsIgnoreCase(name)))) {
                Iterator<String> values = saved.getHeaderValues(name);
                while (values.hasNext()) {
                    request.addHeader(name, values.next());
                }
            }
        }

        request.setContentLength(saved.getContentLenght());

        request.clearLocales();
        Iterator<Locale> locales = saved.getLocales();
        while (locales.hasNext()) {
            request.addLocale(locales.next());
        }

        request.clearParameters();
        // setQueryStringEncoding is done inside request.clearParameters

        ByteChunk body = saved.getBody();

        if (body != null) {
            request.replayPayload(body.getBytes());

            // If no content type specified, use default for POST
            String savedContentType = saved.getContentType();
            if (savedContentType == null && "POST".equalsIgnoreCase(method)) {
                savedContentType = "application/x-www-form-urlencoded";
            }

            request.setContentType(savedContentType);
        }

        request.setMethod(method);
        request.setQueryString(saved.getQueryString());

        return true;
    }

    /**
     * Called to forward to the login page. may redirect current request to HTTPS
     *
     * @param request HttpRequest we are processing
     * @param response HttpResponse we are creating
     * @param config Login configuration describing how authentication should be performed
     */
    protected void forwardToLoginPage(HttpRequest request, HttpResponse response, LoginConfig config) {
        if (isChangeSessionIdOnAuthentication() && getSession(request, false) != null) {
            request.changeSessionId();
        }

        ServletContext servletContext = context.getServletContext();
        try {
            String loginPage = config.getLoginPage();
            if (!request.getRequest().isSecure()) {
                Realm realm = context.getRealm();
                if (realm != null) {
                    SecurityConstraint[] secConstraints = realm.findSecurityConstraints(loginPage, "GET", context);
                    if (secConstraints != null && !realm.hasUserDataPermission(request, response, secConstraints, loginPage, "GET")) {
                        /*
                         * Note that hasUserDataPermission will have already issued a redirect to HTTPS unless redirects have been disabled,
                         * in which case it will have called sendError(FORBIDDEN)
                         */
                        return;
                    }
                }
            }
            RequestDispatcher disp = servletContext.getRequestDispatcher(loginPage);
            disp.forward(request.getRequest(), response.getResponse());
            // NOTE: is finishResponse necessary or is it unnecessary after forward
            response.finishResponse();
        } catch (Throwable t) {
            log.log(WARNING, UNEXPECTED_ERROR_FORWARDING_TO_LOGIN_PAGE, t);
        }
    }

    /**
     * Called to forward to the error page. may redirect current request to HTTPS
     *
     * @param request HttpRequest we are processing
     * @param response HttpResponse we are creating
     * @param config Login configuration describing how authentication should be performed
     */
    protected void forwardToErrorPage(HttpRequest request, HttpResponse response, LoginConfig config) {
        ServletContext servletContext = context.getServletContext();
        try {
            String errorPage = config.getErrorPage();
            if (!request.getRequest().isSecure()) {
                Realm realm = context.getRealm();
                if (realm != null) {
                    SecurityConstraint[] secConstraints = realm.findSecurityConstraints(errorPage, "GET", context);
                    if (secConstraints != null && !realm.hasUserDataPermission(request, response, secConstraints, errorPage, "GET")) {
                        /*
                         * Note that hasUserDataPermission will have already issued a redirect to HTTPS unless redirects have been disabled,
                         * in which case it will have called sendError(FORBIDDEN).
                         */
                        return;
                    }
                }
            }

            servletContext.getRequestDispatcher(errorPage)
                          .forward(request.getRequest(), response.getResponse());

        } catch (Throwable t) {
            log.log(WARNING, UNEXPECTED_ERROR_FORWARDING_TO_LOGIN_PAGE, t);
        }
    }

    /**
     * Save the original request information into our session.
     *
     * @param request The request to be saved
     * @param session The session to contain the saved information
     */
    protected void saveRequest(HttpRequest request, Session session) throws IOException {
        // Create and populate a SavedRequest object for this request
        HttpServletRequest hreq = (HttpServletRequest) request.getRequest();
        SavedRequest saved = new SavedRequest();
        Cookie cookies[] = hreq.getCookies();
        if (cookies != null) {
            for (Cookie element : cookies) {
                saved.addCookie(element);
            }
        }

        Enumeration names = hreq.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            Enumeration values = hreq.getHeaders(name);
            while (values.hasMoreElements()) {
                String value = (String) values.nextElement();
                saved.addHeader(name, value);
            }
        }

        saved.setContentLength(hreq.getContentLength());

        Enumeration locales = hreq.getLocales();
        while (locales.hasMoreElements()) {
            Locale locale = (Locale) locales.nextElement();
            saved.addLocale(locale);
        }

        // May need to acknowledge a 100-continue expectation
        ((HttpResponse) request.getResponse()).sendAcknowledgement();

        ByteChunk body = new ByteChunk();
        body.setLimit(request.getConnector().getMaxSavePostSize());

        byte[] buffer = new byte[4096];
        int bytesRead;
        InputStream is = request.getStream();

        while ((bytesRead = is.read(buffer)) >= 0) {
            body.append(buffer, 0, bytesRead);
        }

        // Only save the request body if there is something to save
        if (body.getLength() > 0) {
            saved.setContentType(hreq.getContentType());
            saved.setBody(body);
        }

        saved.setMethod(hreq.getMethod());
        saved.setQueryString(hreq.getQueryString());
        saved.setRequestURI(hreq.getRequestURI());

        // Stash the SavedRequest in our session for later use
        session.setNote(FORM_REQUEST_NOTE, saved);
    }

    /**
     * Return the request URI (with the corresponding query string, if any) from the saved request so that we can redirect
     * to it.
     *
     * @param session Our current session
     */
    protected String savedRequestURL(Session session) {
        SavedRequest saved = (SavedRequest) session.getNote(FORM_REQUEST_NOTE);
        if (saved == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(saved.getRequestURI());
        if (saved.getQueryString() != null) {
            sb.append('?');
            sb.append(saved.getQueryString());
        }

        return sb.toString();
    }

    private long getSsoVersion(HttpRequest request) {
        long ssoVersion = 0L;
        Long ssoVersionObj = (Long) request.getNote(REQ_SSO_VERSION_NOTE);
        if (ssoVersionObj != null) {
            ssoVersion = ssoVersionObj.longValue();
        }

        return ssoVersion;
    }
}
