/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.modules.session.filter;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.SessionCookieConfig;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.geode.modules.session.internal.filter.GemfireHttpSession;
import org.apache.geode.modules.session.internal.filter.GemfireSessionManager;
import org.apache.geode.modules.session.internal.filter.SessionManager;
import org.apache.geode.modules.session.internal.filter.attributes.DeltaQueuedSessionAttributes;
import org.apache.geode.modules.session.internal.filter.attributes.DeltaSessionAttributes;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * Primary class which orchestrates everything. This is the class which gets configured in the
 * web.xml.
 */
public class SessionCachingFilter implements Filter {

  /**
   * Logger instance
   */
  private static final Logger LOG = LoggerFactory.getLogger(SessionCachingFilter.class.getName());

  /**
   * The filter configuration object we are associated with. If this value is null, this filter
   * instance is not currently configured.
   */
  private FilterConfig filterConfig = null;

  /**
   * Some containers will want to instantiate multiple instances of this filter, but we only need
   * one SessionManager
   */
  private static SessionManager manager = null;

  /**
   * Can be overridden during testing.
   */
  private static final AtomicInteger started = new AtomicInteger(
      Integer.getInteger(GeodeGlossary.GEMFIRE_PREFIX + "override.session.manager.count", 1));

  /**
   * This latch ensures that at least one thread/instance has fired up the session manager before
   * any other threads complete the init method.
   */
  private static final CountDownLatch startingLatch = new CountDownLatch(1);

  /**
   * This request wrapper class extends the support class HttpServletRequestWrapper, which
   * implements all the methods in the HttpServletRequest interface, as delegations to the wrapped
   * request. You only need to override the methods that you need to change. You can get access to
   * the wrapped request using the method getRequest()
   */
  public static class RequestWrapper extends HttpServletRequestWrapper {

    private static final String URL_SESSION_IDENTIFIER = ";jsessionid=";

    private final ResponseWrapper response;

    private boolean sessionFromCookie = false;

    private boolean sessionFromURL = false;

    private String requestedSessionId = null;

    private GemfireHttpSession session = null;

    private final SessionManager manager;

    private final HttpServletRequest outerRequest = null;

    private final ServletContext context;

    RequestWrapper(SessionManager manager, HttpServletRequest request,
        ResponseWrapper response, ServletContext context) {

      super(request);
      this.response = response;
      this.manager = manager;
      this.context = context;

      final Cookie[] cookies = request.getCookies();
      if (cookies != null) {
        for (final Cookie cookie : cookies) {
          if (cookie.getName().equalsIgnoreCase(manager.getSessionCookieName())
              && cookie.getValue().endsWith("-GF")) {
            requestedSessionId = cookie.getValue();
            sessionFromCookie = true;

            LOG.debug("Cookie contains sessionId: {}", requestedSessionId);
          }
        }
      }

      if (requestedSessionId == null) {
        requestedSessionId = extractSessionId();
        LOG.debug("Extracted sessionId from URL {}", requestedSessionId);
        if (requestedSessionId != null) {
          sessionFromURL = true;
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpSession getSession() {
      return getSession(true);
    }

    /**
     * Create our own sessions. TODO: Handle invalidated sessions
     *
     * @return a HttpSession
     */
    @Override
    public HttpSession getSession(boolean create) {
      if (session != null && session.isValid()) {
        session.setIsNew(false);
        session.updateAccessTime();
        return session;
      }

      if (requestedSessionId != null) {
        session = (GemfireHttpSession) manager.getSession(requestedSessionId);
        if (session != null) {
          session.setIsNew(false);
          // This means we've failed over to another node
          if (session.getServletContext() == null) {
            session.setServletContext(context);
          }
        }
      }

      if (session == null || !session.isValid()) {
        if (create) {
          HttpSession nativeSession = super.getSession();
          try {
            session = (GemfireHttpSession) manager.wrapSession(context,
                nativeSession.getMaxInactiveInterval());
            session.setIsNew(true);
            manager.putSession(session);
          } finally {
            nativeSession.invalidate();
          }
        } else {
          // create is false, and session is either null or not valid.
          // The spec says return a null:
          return null;
        }
      }

      if (session != null) {
        addSessionCookie(response);
        session.updateAccessTime();
      }

      return session;
    }

    private void addSessionCookie(HttpServletResponse response) {
      // Don't bother if the response is already committed
      if (response.isCommitted()) {
        return;
      }

      SessionCookieConfig cookieConfig = context.getSessionCookieConfig();
      Cookie cookie = new Cookie(manager.getSessionCookieName(), session.getId());
      cookie.setPath("".equals(getContextPath()) ? "/" : getContextPath());
      cookie.setHttpOnly(cookieConfig.isHttpOnly());
      cookie.setSecure(cookieConfig.isSecure());
      response.addCookie(cookie); // lgtm [java/insecure-cookie]
      // The lgtm alert for the above line is suppressed as a false positive, since we are simply
      // using the security setting from the context's cookie config
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRequestedSessionIdFromCookie() {
      return sessionFromCookie;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRequestedSessionIdFromURL() {
      return sessionFromURL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRequestedSessionId() {
      if (requestedSessionId != null) {
        return requestedSessionId;
      } else {
        return super.getRequestedSessionId();
      }
    }

    /*
     * Hmmm... not sure if this is right or even good to do. So, in some cases - for ex. using a
     * Spring security filter, we have 3 possible wrappers to deal with - the original, this one and
     * one created by Spring. When a servlet or JSP is forwarded to the original request is passed
     * in, but then this (the wrapped) request is used by the JSP. In some cases, the outer wrapper
     * also contains information relevant to the request - in this case security info. So here we
     * allow access to that. There's probably a better way....
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public Principal getUserPrincipal() {
      if (outerRequest != null) {
        return outerRequest.getUserPrincipal();
      } else {
        return super.getUserPrincipal();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRemoteUser() {
      if (outerRequest != null) {
        return outerRequest.getRemoteUser();
      } else {
        return super.getRemoteUser();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isUserInRole(String role) {
      if (outerRequest != null) {
        return outerRequest.isUserInRole(role);
      } else {
        return super.isUserInRole(role);
      }
    }

    //////////////////////////////////////////////////////////////
    // Private methods
    private String extractSessionId() {
      final int prefix = getRequestURL().indexOf(URL_SESSION_IDENTIFIER);
      if (prefix != -1) {
        final int start = prefix + URL_SESSION_IDENTIFIER.length();
        int suffix = getRequestURL().indexOf("?", start);
        if (suffix < 0) {
          suffix = getRequestURL().indexOf("#", start);
        }
        if (suffix <= prefix) {
          return getRequestURL().substring(start);
        }
        return getRequestURL().substring(start, suffix);
      }
      return null;
    }
  }

  /**
   * This response wrapper class extends the support class HttpServletResponseWrapper, which
   * implements all the methods in the HttpServletResponse interface, as delegations to the wrapped
   * response. You only need to override the methods that you need to change. You can get access to
   * the wrapped response using the method getResponse()
   */
  class ResponseWrapper extends HttpServletResponseWrapper {

    HttpServletResponse originalResponse;

    ResponseWrapper(HttpServletResponse response) {
      super(response);
      originalResponse = response;
    }

    @Override
    public void setHeader(String name, String value) {
      super.setHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
      super.setIntHeader(name, value);
    }
  }


  public SessionCachingFilter() {}

  /**
   * @param request The servlet request we are processing
   * @param response The servlet response we are creating
   * @param chain The filter chain we are processing
   * @throws IOException if an input/output error occurs
   * @throws ServletException if a servlet error occurs
   */
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpReq = (HttpServletRequest) request;
    HttpServletResponse httpResp = (HttpServletResponse) response;

    // Early out if this isn't the right kind of request. We might see a RequestWrapper instance
    // during a forward or include request.
    if (alreadyWrapped(httpReq)) {
      LOG.debug("Handling already-wrapped request");
      chain.doFilter(request, response);
      return;
    }

    // Create wrappers for the request and response objects.
    // Using these, you can extend the capabilities of the
    // request and response, for example, allow setting parameters
    // on the request before sending the request to the rest of the filter chain,
    // or keep track of the cookies that are set on the response.
    //
    // Caveat: some servers do not handle wrappers very well for forward or
    // include requests.

    ResponseWrapper wrappedResponse = new ResponseWrapper(httpResp);
    final RequestWrapper wrappedRequest =
        new RequestWrapper(manager, httpReq, wrappedResponse, filterConfig.getServletContext());

    Throwable problem = null;

    try {
      chain.doFilter(wrappedRequest, wrappedResponse);
    } catch (Throwable t) {
      // If an exception is thrown somewhere down the filter chain,
      // we still want to execute our after processing, and then
      // rethrow the problem after that.
      problem = t;
      LOG.error("Exception processing filter chain", t);
    }

    GemfireHttpSession session = (GemfireHttpSession) wrappedRequest.getSession(false);

    // If there was a problem, we want to rethrow it if it is
    // a known type, otherwise log it.
    if (problem != null) {
      if (problem instanceof ServletException) {
        throw (ServletException) problem;
      }
      if (problem instanceof IOException) {
        throw (IOException) problem;
      }
      sendProcessingError(problem, response);
    }

    // Commit any updates. What actually happens at that point is dependent on the type of
    // attributes defined for use by the sessions.
    if (session != null) {
      session.commit();
    }
  }

  /**
   * Test if a request has been wrapped with RequestWrapper somewhere in the chain of wrapped
   * requests.
   */
  private boolean alreadyWrapped(final ServletRequest request) {
    if (request instanceof RequestWrapper) {
      return true;
    }

    if (!(request instanceof ServletRequestWrapper)) {
      return false;
    }

    final ServletRequest nestedRequest = ((ServletRequestWrapper) request).getRequest();

    if (nestedRequest == request) {
      return false;
    }

    return alreadyWrapped(nestedRequest);
  }

  /**
   * Destroy method for this filter
   */
  @Override
  public void destroy() {
    if (manager != null) {
      manager.stop();
    }
  }

  /**
   * This is where all the initialization happens.
   *
   */
  @Override
  public void init(final FilterConfig config) {
    LOG.info("Starting Session Filter initialization");
    registerInstantiators();
    filterConfig = config;

    if (started.getAndDecrement() > 0) {
      // Allow override for testing purposes
      String managerClassStr = config.getInitParameter("session-manager-class");

      // Otherwise default
      if (managerClassStr == null) {
        managerClassStr = GemfireSessionManager.class.getName();
      }

      try {
        manager = (SessionManager) Class.forName(managerClassStr).newInstance();
        manager.start(config, getClass().getClassLoader());
      } catch (Exception ex) {
        LOG.error("Exception creating Session Manager", ex);
      }

      startingLatch.countDown();
    } else {
      try {
        startingLatch.await();
      } catch (InterruptedException iex) {
        // Ignore.
      }

      LOG.debug("SessionManager and listener initialization skipped - " + "already done.");
    }

    LOG.info("Session Filter initialization complete");
    LOG.debug("Filter class loader {}", getClass().getClassLoader());
  }

  private void registerInstantiators() {
    GemfireHttpSession.registerInstantiator();
    DeltaQueuedSessionAttributes.registerInstantiator();
    DeltaSessionAttributes.registerInstantiator();
  }

  /**
   * Return a String representation of this object.
   */
  @Override
  public String toString() {
    if (filterConfig == null) {
      return ("SessionCachingFilter()");
    }

    return ("SessionCachingFilter(" + filterConfig + ")");
  }

  private void sendProcessingError(Throwable t, ServletResponse response) {
    String stackTrace = getStackTrace(t);

    if (stackTrace != null && !stackTrace.equals("")) {
      try {
        response.setContentType("text/html");
        PrintStream ps = new PrintStream(response.getOutputStream());
        PrintWriter pw = new PrintWriter(ps);
        pw.print("<html>\n<head>\n<title>Error</title>\n</head>\n<body>\n"); // NOI18N

        // PENDING! Localize this for next official release
        pw.print("<h1>The resource did not process correctly</h1>\n<pre>\n");
        pw.print(stackTrace);
        pw.print("</pre></body>\n</html>"); // NOI18N
        pw.close();
        ps.close();
        response.getOutputStream().close();
      } catch (Exception ignored) {
      }
    } else {
      try {
        PrintStream ps = new PrintStream(response.getOutputStream());
        t.printStackTrace(ps);
        ps.close();
        response.getOutputStream().close();
      } catch (Exception ignored) {
      }
    }
  }

  private static String getStackTrace(Throwable t) {
    String stackTrace = null;
    try {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      pw.close();
      sw.close();
      stackTrace = sw.getBuffer().toString();
    } catch (Exception ignored) {
    }

    return stackTrace;
  }

  /**
   * Retrieve the SessionManager. This is only here so that tests can get access to the cache.
   */
  public static SessionManager getSessionManager() {
    return manager;
  }
}
