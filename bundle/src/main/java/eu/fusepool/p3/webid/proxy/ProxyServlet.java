/*
 * Copyright 2014 Bern University of Applied Sciences.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.fusepool.p3.webid.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.log.LogService;

/**
 * A servlet which proxies HTTP-Requests to another HTTP-Server.
 *
 * @author Pascal Mainini
 */
// refer to https://felix.apache.org/documentation/subprojects/apache-felix-http-service.html
@Component(service = Servlet.class, property = {"alias=/", "TargetBaseURI=http://localhost:8088"})
@SuppressWarnings("serial")
public class ProxyServlet extends HttpServlet {

    private static final String PROPERTY_TARGET_BASE_URI = "TargetBaseURI";

    private LogService log;
    private final CloseableHttpClient httpclient;
    private String targetBaseUri;

//////////////////////////////////////// Constructors
    /**
     * Initializes the servlet by setting up the HTTP-client.
     */
    public ProxyServlet() {
        final HttpClientBuilder hcb = HttpClientBuilder.create();
        hcb.setRedirectStrategy(new NeverRedirectStrategy());
        httpclient = hcb.build();
    }

//////////////////////////////////////// Interaction with the container
    /**
     * DS-binding for setting the log-service when it becomes available.
     *
     * @param service the log-service to set
     */
    @Reference(
            name = "log.service",
            service = LogService.class,
            unbind = "unsetLogService"
    )
    protected void setLogService(final LogService service) {
        this.log = service;
        log.log(LogService.LOG_INFO, "Obtained logservice!");
    }

    /**
     * DS-binding for removing the log-service when it's no longer available.
     *
     * @param service the log-service being removed
     */
    protected void unsetLogService(final LogService service) {
        this.log = null;
    }

    /**
     * DS-binding run on activation, configures the service
     *
     * @param properties The configuration (or none) obtained from the container
     */
    @Activate
    public void activate(final Map<String, ?> properties) {
        configure(properties);
        log.log(LogService.LOG_INFO, "Service configured.");
    }

    /**
     * DS-binding called when configuration changes, updates internal config.
     *
     * @param properties The new configuration (or none) obtained from the
     * container.
     */
    @Modified
    void modified(final Map<String, ?> properties) {
        configure(properties);
        log.log(LogService.LOG_INFO, "Configuration modified.");
    }

    /**
     * DS-binding called when the service is deactivated.
     */
    @Deactivate
    public void deactivate() {
        configure(null);
        log.log(LogService.LOG_INFO, "Service deconfigured.");
    }

//////////////////////////////////////// Service-Method
    /**
     * The service method from HttpServlet, performs handling of all
     * HTTP-requests independent of their method. Requests and responses within
     * the method can be distinguished by belonging to the "frontend" (i.e. the
     * client connecting to the proxy) or the "backend" (the server being
     * contacted on behalf of the client)
     *
     * @param frontendRequest Request coming in from the client
     * @param frontendResponse Response being returned to the client
     * @throws ServletException
     * @throws IOException
     */
    @Override
    protected void service(final HttpServletRequest frontendRequest, final HttpServletResponse frontendResponse)
            throws ServletException, IOException {
        log.log(LogService.LOG_INFO, "Proxying request: " + frontendRequest.getRemoteAddr() + ":" + frontendRequest.getRemotePort()
                + " (" + frontendRequest.getHeader("Host") + ") " + frontendRequest.getMethod() + " " + frontendRequest.getRequestURI());

        X509Certificate certChain[] = (X509Certificate[]) frontendRequest.getAttribute("javax.servlet.request.X509Certificate");
        ArrayList<String> webIdUris = new ArrayList<>();
        if (certChain != null) {
            for(X509Certificate cert : certChain) {
                try {
                    Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
                    for(List<?> entry : altNames) {
                        if((Integer) entry.get(0) == 6) {
                            webIdUris.add((String) entry.get(1));
                        }
                    }
                } catch (CertificateParsingException ex) {
                    ex.printStackTrace();
                }
            }
        }

        for(String uri : webIdUris) {
            log.log(LogService.LOG_INFO, "Found WebID: " + uri);
        }

        if (targetBaseUri == null) {
            // FIXME return status page
            return;
        }

        //////////////////// Setup backend request
        final HttpEntityEnclosingRequestBase backendRequest = new HttpEntityEnclosingRequestBase() {
            @Override
            public String getMethod() {
                return frontendRequest.getMethod();
            }
        };
        try {
            backendRequest.setURI(new URL(targetBaseUri + frontendRequest.getRequestURI()).toURI());
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }

        //////////////////// Copy headers to backend request
        final Enumeration<String> frontendHeaderNames = frontendRequest.getHeaderNames();
        while (frontendHeaderNames.hasMoreElements()) {
            final String headerName = frontendHeaderNames.nextElement();
            final Enumeration<String> headerValues = frontendRequest.getHeaders(headerName);
            while (headerValues.hasMoreElements()) {
                final String headerValue = headerValues.nextElement();
                if (!headerName.equalsIgnoreCase("Content-Length")) {
                    backendRequest.setHeader(headerName, headerValue);
                }
            }
        }

        //////////////////// Copy Entity - if any
        final byte[] inEntityBytes = IOUtils.toByteArray(frontendRequest.getInputStream());
        if (inEntityBytes.length > 0) {
            backendRequest.setEntity(new ByteArrayEntity(inEntityBytes));
        }

        //////////////////// Execute request to backend
        try (CloseableHttpResponse backendResponse = httpclient.execute(backendRequest)) {
            frontendResponse.setStatus(backendResponse.getStatusLine().getStatusCode());

            // Copy back headers
            final Header[] backendHeaders = backendResponse.getAllHeaders();
            final Set<String> backendHeaderNames = new HashSet<>(backendHeaders.length);
            for (Header header : backendHeaders) {
                if (backendHeaderNames.add(header.getName())) {
                    frontendResponse.setHeader(header.getName(), header.getValue());
                } else {
                    frontendResponse.addHeader(header.getName(), header.getValue());
                }
            }

            final ServletOutputStream outStream = frontendResponse.getOutputStream();

            // Copy back entity
            final HttpEntity entity = backendResponse.getEntity();
            if (entity != null) {
                try (InputStream inStream = entity.getContent()) {
                    IOUtils.copy(inStream, outStream);
                }
            }
            outStream.flush();
        } catch (ConnectException ex) {
            log.log(LogService.LOG_WARNING, "Exception while connecting to backend, returning 504: " + ex.getMessage());

            frontendResponse.setStatus(504);    // GATEWAY TIMEOUT
            final ServletOutputStream outStream = frontendResponse.getOutputStream();
            outStream.println("504 - Gateway timout!");
            outStream.flush();
        }
    }

//////////////////////////////////////// Helpers
    private void configure(final Map<String, ?> properties) {
        if (properties == null) {
            targetBaseUri = null;
        } else {
            log.log(LogService.LOG_INFO, "Configuring service...");
            if (properties.containsKey(PROPERTY_TARGET_BASE_URI)) {
                targetBaseUri = (String) properties.get(PROPERTY_TARGET_BASE_URI);
                log.log(LogService.LOG_INFO, "Proxy enabled, target: " + targetBaseUri);
            } else {
                targetBaseUri = null;
            }
        }
    }

    private static class NeverRedirectStrategy implements RedirectStrategy {
        @Override
        public HttpUriRequest getRedirect(final HttpRequest hr, final HttpResponse hr1, final org.apache.http.protocol.HttpContext hc) throws org.apache.http.ProtocolException {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean isRedirected(final HttpRequest hr, final HttpResponse hr1, final org.apache.http.protocol.HttpContext hc) throws org.apache.http.ProtocolException {
            return false;
        }
    }
}
