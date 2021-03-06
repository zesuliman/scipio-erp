/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.webapp.control;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;

import org.ofbiz.base.component.ComponentConfig.WebappInfo;
import org.ofbiz.base.component.ComponentURLException.ComponentNotFoundURLException;
import org.ofbiz.base.location.FlexibleLocation;
import org.ofbiz.base.metrics.Metrics;
import org.ofbiz.base.metrics.MetricsFactory;
import org.ofbiz.base.util.Assert;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.FileUtil;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.UtilXml;
import org.ofbiz.base.util.cache.UtilCache;
import org.ofbiz.base.util.collections.MapContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * ConfigXMLReader.java - Reads and parses the XML site config files.
 */
public class ConfigXMLReader {

    public static final String module = ConfigXMLReader.class.getName();
    public static final String controllerXmlFileName = "/WEB-INF/controller.xml";
    private static final UtilCache<URL, ControllerConfig> controllerCache = UtilCache.createUtilCache("webapp.ControllerConfig");
    private static final UtilCache<String, List<ControllerConfig>> controllerSearchResultsCache = UtilCache.createUtilCache("webapp.ControllerSearchResults");
    public static final RequestResponse emptyNoneRequestResponse = RequestResponse.createEmptyNoneRequestResponse();

    public static Set<String> findControllerFilesWithRequest(String requestUri, String controllerPartialPath) throws GeneralException {
        Set<String> allControllerRequestSet = new HashSet<String>();
        if (UtilValidate.isEmpty(requestUri)) {
            return allControllerRequestSet;
        }
        String cacheId = controllerPartialPath != null ? controllerPartialPath : "NOPARTIALPATH";
        List<ControllerConfig> controllerConfigs = controllerSearchResultsCache.get(cacheId);
        if (controllerConfigs == null) {
            try {
                // find controller.xml file with webappMountPoint + "/WEB-INF" in the path
                List<File> controllerFiles = FileUtil.findXmlFiles(null, controllerPartialPath, "site-conf", "site-conf.xsd");
                controllerConfigs = new LinkedList<ControllerConfig>();
                for (File controllerFile : controllerFiles) {
                    URL controllerUrl = null;
                    try {
                        controllerUrl = controllerFile.toURI().toURL();
                    } catch (MalformedURLException mue) {
                        throw new GeneralException(mue);
                    }
                    ControllerConfig cc = ConfigXMLReader.getControllerConfig(controllerUrl);
                    controllerConfigs.add(cc);
                }
                controllerConfigs = controllerSearchResultsCache.putIfAbsentAndGet(cacheId, controllerConfigs);
            } catch (IOException e) {
                throw new GeneralException("Error finding controller XML files to lookup request references: " + e.toString(), e);
            }
        }
        if (controllerConfigs != null) {
            for (ControllerConfig cc : controllerConfigs) {
                // make sure it has the named request in it
                if (cc.requestMapMap.get(requestUri) != null) {
                    String requestUniqueId = cc.url.toExternalForm() + "#" + requestUri;
                    allControllerRequestSet.add(requestUniqueId);
                    // Debug.logInfo("========== In findControllerFilesWithRequest found controller with request here [" + requestUniqueId + "]", module);
                }
            }
        }
        return allControllerRequestSet;
    }

    public static Set<String> findControllerRequestUniqueForTargetType(String target, String urlMode) throws GeneralException {
        if (UtilValidate.isEmpty(urlMode)) {
            urlMode = "intra-app";
        }
        int indexOfDollarSignCurlyBrace = target.indexOf("${");
        int indexOfQuestionMark = target.indexOf("?");
        if (indexOfDollarSignCurlyBrace >= 0 && (indexOfQuestionMark < 0 || indexOfQuestionMark > indexOfDollarSignCurlyBrace)) {
            // we have an expanded string in the requestUri part of the target, not much we can do about that...
            return null;
        }
        if ("intra-app".equals(urlMode)) {
            // look through all controller.xml files and find those with the request-uri referred to by the target
            String requestUri = UtilHttp.getRequestUriFromTarget(target);
            Set<String> controllerLocAndRequestSet = ConfigXMLReader.findControllerFilesWithRequest(requestUri, null);
            // if (controllerLocAndRequestSet.size() > 0) Debug.logInfo("============== In findRequestNamesLinkedtoInWidget, controllerLocAndRequestSet: " + controllerLocAndRequestSet, module);
            return controllerLocAndRequestSet;
        } else if ("inter-app".equals(urlMode)) {
            String webappMountPoint = UtilHttp.getWebappMountPointFromTarget(target);
            if (webappMountPoint != null)
                webappMountPoint += "/WEB-INF";
            String requestUri = UtilHttp.getRequestUriFromTarget(target);

            Set<String> controllerLocAndRequestSet = ConfigXMLReader.findControllerFilesWithRequest(requestUri, webappMountPoint);
            // if (controllerLocAndRequestSet.size() > 0) Debug.logInfo("============== In findRequestNamesLinkedtoInWidget, controllerLocAndRequestSet: " + controllerLocAndRequestSet, module);
            return controllerLocAndRequestSet;
        } else {
            return new HashSet<String>();
        }
    }

    public static ControllerConfig getControllerConfig(WebappInfo webAppInfo) throws WebAppConfigurationException, MalformedURLException {
        Assert.notNull("webAppInfo", webAppInfo);
        String filePath = webAppInfo.getLocation().concat(controllerXmlFileName);
        File configFile = new File(filePath);
        return getControllerConfig(configFile.toURI().toURL());
    }

    public static ControllerConfig getControllerConfig(URL url) throws WebAppConfigurationException {
        ControllerConfig controllerConfig = controllerCache.get(url);
        if (controllerConfig == null) {
            controllerConfig = controllerCache.putIfAbsentAndGet(url, new ControllerConfig(url));
        }
        return controllerConfig.isNull() ? null : controllerConfig; // SCIPIO: check for special null key
    }
    
    /**
     * SCIPIO: version of getControllerConfig that supports optional loading.
     * Added 2017-05-03.
     */
    public static ControllerConfig getControllerConfig(URL url, boolean optional) throws WebAppConfigurationException {
        ControllerConfig controllerConfig = controllerCache.get(url);
        if (controllerConfig == null) {
            try {
                controllerConfig = new ControllerConfig(url);
            } catch(WebAppConfigurationException e) {
                if (optional && (e.getCause() instanceof java.io.FileNotFoundException)) {
                    if (Debug.infoOn()) {
                        Debug.logInfo("controller skipped (not found, optional): " + url.toString(), module);
                    }
                    controllerConfig = ControllerConfig.NULL_CONFIG; // special cache key
                } else {
                    throw e;
                }
            }
            controllerConfig = controllerCache.putIfAbsentAndGet(url, controllerConfig);
        }
        return controllerConfig.isNull() ? null : controllerConfig;
    }

    public static URL getControllerConfigURL(ServletContext context) {
        try {
            return context.getResource(controllerXmlFileName);
        } catch (MalformedURLException e) {
            Debug.logError(e, "Error Finding XML Config File: " + controllerXmlFileName, module);
            return null;
        }
    }

    /** Loads the XML file and returns the root element 
     * @throws WebAppConfigurationException */
    private static Element loadDocument(URL location) throws WebAppConfigurationException {
        try {
            Document document = UtilXml.readXmlDocument(location, true);
            Element rootElement = document.getDocumentElement();
            if (Debug.verboseOn())
                Debug.logVerbose("Loaded XML Config - " + location, module);
            return rootElement;
        } catch (java.io.FileNotFoundException e) { // SCIPIO: special case: let caller log this one, IF necessary
            throw new WebAppConfigurationException(e);
        } catch (Exception e) {
            //Scipio: not all components have a WebApp, so this should not be logged as an error.
            // Debug.logError(e, module);
            throw new WebAppConfigurationException(e);
        }
    }

    public static class ControllerConfig {
        // SCIPIO: special key for cache lookups that return null
        public static final ControllerConfig NULL_CONFIG = new ControllerConfig();
        
        public URL url;
        private String errorpage;
        private String protectView;
        private String owner;
        private String securityClass;
        private String defaultRequest;
        private String statusCode;
        // SCIPIO: extended info on includes needed
        //private List<URL> includes = new ArrayList<URL>();
        private List<Include> includes = new ArrayList<>();
        // SCIPIO: split-up includes
        private List<Include> includesPreLocal = new ArrayList<>();
        private List<Include> includesPostLocal = new ArrayList<>();
        private Map<String, Event> firstVisitEventList = new HashMap<String, Event>();
        private Map<String, Event> preprocessorEventList = new HashMap<String, Event>();
        private Map<String, Event> postprocessorEventList = new HashMap<String, Event>();
        private Map<String, Event> afterLoginEventList = new HashMap<String, Event>();
        private Map<String, Event> beforeLogoutEventList = new HashMap<String, Event>();
        private Map<String, String> eventHandlerMap = new HashMap<String, String>();
        private Map<String, String> viewHandlerMap = new HashMap<String, String>();
        private Map<String, RequestMap> requestMapMap = new HashMap<String, RequestMap>();
        private Map<String, ViewMap> viewMapMap = new HashMap<String, ViewMap>();
        private ViewAsJsonConfig viewAsJsonConfig; // SCIPIO: added 2017-05-15
        
        public ControllerConfig(URL url) throws WebAppConfigurationException {
            this.url = url;
            Element rootElement = loadDocument(url);
            if (rootElement != null) {
                long startTime = System.currentTimeMillis();
                loadIncludes(rootElement);
                loadGeneralConfig(rootElement);
                loadHandlerMap(rootElement);
                loadRequestMap(rootElement);
                loadViewMap(rootElement);
                if (Debug.infoOn()) {
                    double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                    String locString = this.url.toExternalForm();
                    Debug.logInfo("controller loaded: " + totalSeconds + "s, " + this.requestMapMap.size() + " requests, " + this.viewMapMap.size() + " views in " + locString, module);
                }
            }
        }
        
        private ControllerConfig() { // SCIPIO: special null config 
            this.url = null;
        }
        
        public boolean isNull() { // SCIPIO: special
            return this.url == null;
        }

        // SCIPIO: all calls below modified for more complex include options (non-recursive include)
        
        public Map<String, Event> getAfterLoginEventList() throws WebAppConfigurationException {
            MapContext<String, Event> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getAfterLoginEventList());
                    } else {
                        result.push(controllerConfig.afterLoginEventList);
                    }
                }
            }
            result.push(afterLoginEventList);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getAfterLoginEventList());
                    } else {
                        result.push(controllerConfig.afterLoginEventList);
                    }
                }
            }
            return result;
        }

        public Map<String, Event> getBeforeLogoutEventList() throws WebAppConfigurationException {
            MapContext<String, Event> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getBeforeLogoutEventList());
                    } else {
                        result.push(controllerConfig.beforeLogoutEventList);
                    }
                }
            }
            result.push(beforeLogoutEventList);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getBeforeLogoutEventList());
                    } else {
                        result.push(controllerConfig.beforeLogoutEventList);
                    }
                }
            }
            return result;
        }

        public String getDefaultRequest() throws WebAppConfigurationException {
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String defaultRequest = controllerConfig.getDefaultRequest();
                    String defaultRequest;
                    if (include.recursive) {
                        defaultRequest = controllerConfig.getDefaultRequest();
                    } else {
                        defaultRequest = controllerConfig.defaultRequest;
                    }
                    if (defaultRequest != null) {
                        return defaultRequest;
                    }
                }
            }
            if (defaultRequest != null) {
                return defaultRequest;
            }
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String defaultRequest = controllerConfig.getDefaultRequest();
                    String defaultRequest;
                    if (include.recursive) {
                        defaultRequest = controllerConfig.getDefaultRequest();
                    } else {
                        defaultRequest = controllerConfig.defaultRequest;
                    }
                    if (defaultRequest != null) {
                        return defaultRequest;
                    }
                }
            }
            return null;
        }

        public String getErrorpage() throws WebAppConfigurationException {
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String errorpage = controllerConfig.getErrorpage();
                    String errorpage;
                    if (include.recursive) {
                        errorpage = controllerConfig.getErrorpage();
                    } else {
                        errorpage = controllerConfig.errorpage;
                    }
                    if (errorpage != null) {
                        return errorpage;
                    }
                }
            }
            if (errorpage != null) {
                return errorpage;
            }
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String errorpage = controllerConfig.getErrorpage();
                    String errorpage;
                    if (include.recursive) {
                        errorpage = controllerConfig.getErrorpage();
                    } else {
                        errorpage = controllerConfig.errorpage;
                    }
                    if (errorpage != null) {
                        return errorpage;
                    }
                }
            }
            return null;
        }

        public Map<String, String> getEventHandlerMap() throws WebAppConfigurationException {
            MapContext<String, String> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getEventHandlerMap());
                    } else {
                        result.push(controllerConfig.eventHandlerMap);
                    }
                }
            }
            result.push(eventHandlerMap);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getEventHandlerMap());
                    } else {
                        result.push(controllerConfig.eventHandlerMap);
                    }
                }
            }
            return result;
        }

        public Map<String, Event> getFirstVisitEventList() throws WebAppConfigurationException {
            MapContext<String, Event> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getFirstVisitEventList());
                    } else {
                        result.push(controllerConfig.firstVisitEventList);
                    }
                }
            }
            result.push(firstVisitEventList);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getFirstVisitEventList());
                    } else {
                        result.push(controllerConfig.firstVisitEventList);
                    }
                }
            }
            return result;
        }

        public String getOwner() throws WebAppConfigurationException {
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String owner = controllerConfig.getOwner();
                    String owner;
                    if (include.recursive) {
                        owner = controllerConfig.getOwner();
                    } else {
                        owner = controllerConfig.owner;
                    }
                    if (owner != null) {
                        return owner;
                    }
                }
            }
            if (owner != null) {
                return owner;
            }
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String owner = controllerConfig.getOwner();
                    String owner;
                    if (include.recursive) {
                        owner = controllerConfig.getOwner();
                    } else {
                        owner = controllerConfig.owner;
                    }
                    if (owner != null) {
                        return owner;
                    }
                }
            }
            return null;
        }

        public Map<String, Event> getPostprocessorEventList() throws WebAppConfigurationException {
            MapContext<String, Event> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getPostprocessorEventList());
                    } else {
                        result.push(controllerConfig.postprocessorEventList);
                    }
                }
            }
            result.push(postprocessorEventList);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getPostprocessorEventList());
                    } else {
                        result.push(controllerConfig.postprocessorEventList);
                    }
                }
            }
            return result;
        }

        public Map<String, Event> getPreprocessorEventList() throws WebAppConfigurationException {
            MapContext<String, Event> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getPreprocessorEventList());
                    } else {
                        result.push(controllerConfig.preprocessorEventList);
                    }
                }
            }
            result.push(preprocessorEventList);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getPreprocessorEventList());
                    } else {
                        result.push(controllerConfig.preprocessorEventList);
                    }
                }
            }
            return result;
        }

        public String getProtectView() throws WebAppConfigurationException {
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String protectView = controllerConfig.getProtectView();
                    String protectView;
                    if (include.recursive) {
                        protectView = controllerConfig.getProtectView();
                    } else {
                        protectView = controllerConfig.protectView;
                    }
                    if (protectView != null) {
                        return protectView;
                    }
                }
            }
            if (protectView != null) {
                return protectView;
            }
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String protectView = controllerConfig.getProtectView();
                    String protectView;
                    if (include.recursive) {
                        protectView = controllerConfig.getProtectView();
                    } else {
                        protectView = controllerConfig.protectView;
                    }
                    if (protectView != null) {
                        return protectView;
                    }
                }
            }
            return null;
        }

        public Map<String, RequestMap> getRequestMapMap() throws WebAppConfigurationException {
            MapContext<String, RequestMap> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getRequestMapMap());
                    } else {
                        result.push(controllerConfig.requestMapMap);
                    }
                }
            }
            result.push(requestMapMap);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getRequestMapMap());
                    } else {
                        result.push(controllerConfig.requestMapMap);
                    }
                }
            }
            return result;
        }

        public String getSecurityClass() throws WebAppConfigurationException {
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String securityClass = controllerConfig.getSecurityClass();
                    String securityClass;
                    if (include.recursive) {
                        securityClass = controllerConfig.getSecurityClass();
                    } else {
                        securityClass = controllerConfig.securityClass;
                    }
                    if (securityClass != null) {
                        return securityClass;
                    }
                }
            }
            if (securityClass != null) {
                return securityClass;
            }
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String securityClass = controllerConfig.getSecurityClass();
                    String securityClass;
                    if (include.recursive) {
                        securityClass = controllerConfig.getSecurityClass();
                    } else {
                        securityClass = controllerConfig.securityClass;
                    }
                    if (securityClass != null) {
                        return securityClass;
                    }
                }
            }
            return null;
        }

        public String getStatusCode() throws WebAppConfigurationException {
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String statusCode = controllerConfig.getStatusCode();
                    String statusCode;
                    if (include.recursive) {
                        statusCode = controllerConfig.getStatusCode();
                    } else {
                        statusCode = controllerConfig.statusCode;
                    }
                    if (statusCode != null) {
                        return statusCode;
                    }
                }
            }
            if (statusCode != null) {
                return statusCode;
            }
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    //String statusCode = controllerConfig.getStatusCode();
                    String statusCode;
                    if (include.recursive) {
                        statusCode = controllerConfig.getStatusCode();
                    } else {
                        statusCode = controllerConfig.statusCode;
                    }
                    if (statusCode != null) {
                        return statusCode;
                    }
                }
            }
            return null;
        }

        public Map<String, String> getViewHandlerMap() throws WebAppConfigurationException {
            MapContext<String, String> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getViewHandlerMap());
                    } else {
                        result.push(controllerConfig.viewHandlerMap);
                    }
                }
            }
            result.push(viewHandlerMap);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getViewHandlerMap());
                    } else {
                        result.push(controllerConfig.viewHandlerMap);
                    }
                }
            }
            return result;
        }

        public Map<String, ViewMap> getViewMapMap() throws WebAppConfigurationException {
            MapContext<String, ViewMap> result = MapContext.getMapContext();
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getViewMapMap());
                    } else {
                        result.push(controllerConfig.viewMapMap);
                    }
                }
            }
            result.push(viewMapMap);
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    // SCIPIO: support non-recursive
                    if (include.recursive) {
                        result.push(controllerConfig.getViewMapMap());
                    } else {
                        result.push(controllerConfig.viewMapMap);
                    }
                }
            }
            return result;
        }

        /**
         * SCIPIO: returns view-as-json configuration, corresponding to site-conf.xsd view-as-json element.
         */
        public ViewAsJsonConfig getViewAsJsonConfig() throws WebAppConfigurationException {
            for (Include include : includesPostLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    ViewAsJsonConfig viewAsJsonConfig;
                    if (include.recursive) {
                        viewAsJsonConfig = controllerConfig.getViewAsJsonConfig();
                    } else {
                        viewAsJsonConfig = controllerConfig.viewAsJsonConfig;
                    }
                    if (viewAsJsonConfig != null) {
                        return viewAsJsonConfig;
                    }
                }
            }
            if (viewAsJsonConfig != null) {
                return viewAsJsonConfig;
            }
            for (Include include : includesPreLocal) {
                ControllerConfig controllerConfig = getControllerConfig(include.location, include.optional);
                if (controllerConfig != null) {
                    ViewAsJsonConfig viewAsJsonConfig;
                    if (include.recursive) {
                        viewAsJsonConfig = controllerConfig.getViewAsJsonConfig();
                    } else {
                        viewAsJsonConfig = controllerConfig.viewAsJsonConfig;
                    }
                    if (viewAsJsonConfig != null) {
                        return viewAsJsonConfig;
                    }
                }
            }
            return null;
        }
        
        /**
         * SCIPIO: returns view-as-json configuration, corresponding to site-conf.xsd view-as-json element.
         */
        public ViewAsJsonConfig getViewAsJsonConfigOrDefault() throws WebAppConfigurationException {
            ViewAsJsonConfig config = getViewAsJsonConfig();
            return config != null ? config : new ViewAsJsonConfig();
        }
        
        private void loadGeneralConfig(Element rootElement) {
            this.errorpage = UtilXml.childElementValue(rootElement, "errorpage");
            this.statusCode = UtilXml.childElementValue(rootElement, "status-code");
            Element protectElement = UtilXml.firstChildElement(rootElement, "protect");
            if (protectElement != null) {
                this.protectView = protectElement.getAttribute("view");
            }
            this.owner = UtilXml.childElementValue(rootElement, "owner");
            this.securityClass = UtilXml.childElementValue(rootElement, "security-class");
            Element defaultRequestElement = UtilXml.firstChildElement(rootElement, "default-request");
            if (defaultRequestElement != null) {
                this.defaultRequest = defaultRequestElement.getAttribute("request-uri");
            }
            // first visit event
            Element firstvisitElement = UtilXml.firstChildElement(rootElement, "firstvisit");
            if (firstvisitElement != null) {
                for (Element eventElement : UtilXml.childElementList(firstvisitElement, "event")) {
                    String eventName = eventElement.getAttribute("name");
                    if (UtilValidate.isEmpty(eventName)) {
                        eventName = eventElement.getAttribute("type") + "::" + eventElement.getAttribute("path") + "::" + eventElement.getAttribute("invoke");
                    }
                    this.firstVisitEventList.put(eventName, new Event(eventElement));
                }
            }
            // preprocessor events
            Element preprocessorElement = UtilXml.firstChildElement(rootElement, "preprocessor");
            if (preprocessorElement != null) {
                for (Element eventElement : UtilXml.childElementList(preprocessorElement, "event")) {
                    String eventName = eventElement.getAttribute("name");
                    if (UtilValidate.isEmpty(eventName)) {
                        eventName = eventElement.getAttribute("type") + "::" + eventElement.getAttribute("path") + "::" + eventElement.getAttribute("invoke");
                    }
                    this.preprocessorEventList.put(eventName, new Event(eventElement));
                }
            }
            // postprocessor events
            Element postprocessorElement = UtilXml.firstChildElement(rootElement, "postprocessor");
            if (postprocessorElement != null) {
                for (Element eventElement : UtilXml.childElementList(postprocessorElement, "event")) {
                    String eventName = eventElement.getAttribute("name");
                    if (UtilValidate.isEmpty(eventName)) {
                        eventName = eventElement.getAttribute("type") + "::" + eventElement.getAttribute("path") + "::" + eventElement.getAttribute("invoke");
                    }
                    this.postprocessorEventList.put(eventName, new Event(eventElement));
                }
            }
            // after-login events
            Element afterLoginElement = UtilXml.firstChildElement(rootElement, "after-login");
            if (afterLoginElement != null) {
                for (Element eventElement : UtilXml.childElementList(afterLoginElement, "event")) {
                    String eventName = eventElement.getAttribute("name");
                    if (UtilValidate.isEmpty(eventName)) {
                        eventName = eventElement.getAttribute("type") + "::" + eventElement.getAttribute("path") + "::" + eventElement.getAttribute("invoke");
                    }
                    this.afterLoginEventList.put(eventName, new Event(eventElement));
                }
            }
            // before-logout events
            Element beforeLogoutElement = UtilXml.firstChildElement(rootElement, "before-logout");
            if (beforeLogoutElement != null) {
                for (Element eventElement : UtilXml.childElementList(beforeLogoutElement, "event")) {
                    String eventName = eventElement.getAttribute("name");
                    if (UtilValidate.isEmpty(eventName)) {
                        eventName = eventElement.getAttribute("type") + "::" + eventElement.getAttribute("path") + "::" + eventElement.getAttribute("invoke");
                    }
                    this.beforeLogoutEventList.put(eventName, new Event(eventElement));
                }
            }
            // SCIPIO: new
            Element viewAsJsonElement = UtilXml.firstChildElement(rootElement, "view-as-json");
            if (viewAsJsonElement != null) {
                this.viewAsJsonConfig = new ViewAsJsonConfig(viewAsJsonElement);
            } else {
                this.viewAsJsonConfig = null;
            }
        }
        
        private void loadHandlerMap(Element rootElement) {
            for (Element handlerElement : UtilXml.childElementList(rootElement, "handler")) {
                String name = handlerElement.getAttribute("name");
                String type = handlerElement.getAttribute("type");
                String className = handlerElement.getAttribute("class");

                if ("view".equals(type)) {
                    this.viewHandlerMap.put(name, className);
                } else {
                    this.eventHandlerMap.put(name, className);
                }
            }
        }

        protected void loadIncludes(Element rootElement) {
            for (Element includeElement : UtilXml.childElementList(rootElement, "include")) {
                String includeLocation = includeElement.getAttribute("location");
                if (UtilValidate.isNotEmpty(includeLocation)) {
                    // SCIPIO: support non-recursive
                    boolean recursive = !"no".equals(includeElement.getAttribute("recursive"));
                    boolean optional = "true".equals(includeElement.getAttribute("optional"));
                    try {
                        URL urlLocation = FlexibleLocation.resolveLocation(includeLocation);
                        String order = includeElement.getAttribute("order");
                        Include include = new Include(urlLocation, recursive, optional, order);
                        includes.add(include);
                        if (include.isPostLocal()) {
                            includesPostLocal.add(include);
                        } else {
                            includesPreLocal.add(include);
                        }
                    } catch (ComponentNotFoundURLException mue) { // SCIPIO: 2017-08-03: special case needed for missing component
                        if (optional) {
                            if (Debug.verboseOn()) Debug.logVerbose("Skipping optional processing include at [" + includeLocation + "]: component not found", module);
                        } else {
                            Debug.logError(mue, "Error processing include at [" + includeLocation + "]: " + mue.toString(), module);
                        }
                    } catch (MalformedURLException mue) {
                        Debug.logError(mue, "Error processing include at [" + includeLocation + "]: " + mue.toString(), module); // SCIPIO: 2017-08-03: typo fix
                    }
                }
            }
        }

        private void loadRequestMap(Element root) {
            for (Element requestMapElement : UtilXml.childElementList(root, "request-map")) {
                RequestMap requestMap = new RequestMap(requestMapElement);
                this.requestMapMap.put(requestMap.uri, requestMap);
            }
        }

        private void loadViewMap(Element rootElement) {
            for (Element viewMapElement : UtilXml.childElementList(rootElement, "view-map")) {
                ViewMap viewMap = new ViewMap(viewMapElement);
                this.viewMapMap.put(viewMap.name, viewMap);
            }
        }

        // SCIPIO: Added getters for languages that can't read public properties (2017-05-08)
        
        public URL getUrl() {
            return url;
        }
        
        /**
         * SCIPIO: Include with support for non-recursive.
         */
        public static class Include {
            public final URL location;
            public final boolean recursive;
            public final boolean optional; // SCIPIO: added 2017-05-03
            public final Order order; // SCIPIO: added 2017-05-03
            
            public Include(URL location, boolean recursive, boolean optional, Order order) {
                this.location = location;
                this.recursive = recursive;
                this.optional = optional;
                this.order = order;
            }
            
            public Include(URL location, boolean recursive, boolean optional, String order) {
                this(location, recursive, optional, Order.fromName(order));
            }
            
            public Include(URL location, boolean recursive) {
                this(location, recursive, false, Order.PRE_LOCAL);
            }
            
            public boolean isPostLocal() {
                return this.order == Order.POST_LOCAL;
            }
            public enum Order {
                PRE_LOCAL,
                POST_LOCAL;
                
                public static Order fromName(String name) {
                    if (name == null || name.isEmpty() || name.equals("pre-local")) {
                        return PRE_LOCAL;
                    } else if ("post-local".equals(name)) {
                        return POST_LOCAL;
                    } else {
                        throw new IllegalArgumentException("invalid controller include order value: " + name);
                    }
                }
            }
            
            // SCIPIO: Added getters for languages that can't read public properties (2017-05-08)
            
            public URL getLocation() {
                return location;
            }

            public boolean isRecursive() {
                return recursive;
            }

            public boolean isOptional() {
                return optional;
            }

            public Order getOrder() {
                return order;
            }
        }
    }

    public static class Event {
        public String type;
        public String path;
        public String invoke;
        public boolean globalTransaction = true;
        public Metrics metrics = null;
        public Boolean transaction = null; // SCIPIO: A generic transaction flag
        public String abortTransaction = ""; // SCIPIO: Allow aborting transaction 

        public Event(Element eventElement) {
            this.type = eventElement.getAttribute("type");
            this.path = eventElement.getAttribute("path");
            this.invoke = eventElement.getAttribute("invoke");
            this.globalTransaction = !"false".equals(eventElement.getAttribute("global-transaction"));
            // Get metrics.
            Element metricsElement = UtilXml.firstChildElement(eventElement, "metric");
            if (metricsElement != null) {
                this.metrics = MetricsFactory.getInstance(metricsElement);
            }
            // SCIPIO: new attribs
            String transStr = eventElement.getAttribute("transaction");
            if ("true".equals(transStr)) {
                transaction = Boolean.TRUE;
            } else if ("false".equals(transStr)) {
                transaction = Boolean.FALSE;
            } else {
                transaction = null;
            }
            this.abortTransaction = eventElement.getAttribute("abort-transaction");
        }

        public Event(String type, String path, String invoke, boolean globalTransaction) {
            this.type = type;
            this.path = path;
            this.invoke = invoke;
            this.globalTransaction = globalTransaction;
        }

        public Event(String type, String path, String invoke, boolean globalTransaction, Metrics metrics,
                Boolean transaction, String abortTransaction) {
            super();
            this.type = type;
            this.path = path;
            this.invoke = invoke;
            this.globalTransaction = globalTransaction;
            this.transaction = transaction;
            this.abortTransaction = abortTransaction;
        }

        // SCIPIO: Added getters for languages that can't read public properties (2017-05-08)
        
        public String getType() {
            return type;
        }

        public String getPath() {
            return path;
        }

        public String getInvoke() {
            return invoke;
        }

        public boolean isGlobalTransaction() {
            return globalTransaction;
        }

        public Metrics getMetrics() {
            return metrics;
        }

        public Boolean getTransaction() {
            return transaction;
        }

        public String getAbortTransaction() {
            return abortTransaction;
        }
    }

    public static class RequestMap {
        public String uri;
        public boolean edit = true;
        public boolean trackVisit = true;
        public boolean trackServerHit = true;
        public String description;
        public Event event;
        public boolean securityHttps = false;
        public boolean securityAuth = false;
        public boolean securityCert = false;
        public boolean securityExternalView = true;
        public boolean securityDirectRequest = true;
        public Map<String, RequestResponse> requestResponseMap = new HashMap<String, RequestResponse>();
        public Metrics metrics = null;

        public RequestMap(Element requestMapElement) {
            // Get the URI info
            this.uri = requestMapElement.getAttribute("uri");
            this.edit = !"false".equals(requestMapElement.getAttribute("edit"));
            this.trackServerHit = !"false".equals(requestMapElement.getAttribute("track-serverhit"));
            this.trackVisit = !"false".equals(requestMapElement.getAttribute("track-visit"));
            // Check for security
            Element securityElement = UtilXml.firstChildElement(requestMapElement, "security");
            if (securityElement != null) {
                this.securityHttps = "true".equals(securityElement.getAttribute("https"));
                this.securityAuth = "true".equals(securityElement.getAttribute("auth"));
                this.securityCert = "true".equals(securityElement.getAttribute("cert"));
                this.securityExternalView = !"false".equals(securityElement.getAttribute("external-view"));
                this.securityDirectRequest = !"false".equals(securityElement.getAttribute("direct-request"));
            }
            // Check for event
            Element eventElement = UtilXml.firstChildElement(requestMapElement, "event");
            if (eventElement != null) {
                this.event = new Event(eventElement);
            }
            // Check for description
            this.description = UtilXml.childElementValue(requestMapElement, "description");
            // Get the response(s)
            for (Element responseElement : UtilXml.childElementList(requestMapElement, "response")) {
                RequestResponse response = new RequestResponse(responseElement);
                requestResponseMap.put(response.name, response);
            }
            // Get metrics.
            Element metricsElement = UtilXml.firstChildElement(requestMapElement, "metric");
            if (metricsElement != null) {
                this.metrics = MetricsFactory.getInstance(metricsElement);
            }
        }

        // SCIPIO: Added getters for languages that can't read public properties (2017-05-08)
        
        public String getUri() {
            return uri;
        }

        public boolean isEdit() {
            return edit;
        }

        public boolean isTrackVisit() {
            return trackVisit;
        }

        public boolean isTrackServerHit() {
            return trackServerHit;
        }

        public String getDescription() {
            return description;
        }

        public Event getEvent() {
            return event;
        }

        public boolean isSecurityHttps() {
            return securityHttps;
        }

        public boolean isSecurityAuth() {
            return securityAuth;
        }

        public boolean isSecurityCert() {
            return securityCert;
        }

        public boolean isSecurityExternalView() {
            return securityExternalView;
        }

        public boolean isSecurityDirectRequest() {
            return securityDirectRequest;
        }

        public Map<String, RequestResponse> getRequestResponseMap() {
            return requestResponseMap;
        }

        public Metrics getMetrics() {
            return metrics;
        }
    }

    public static class RequestResponse {

        public static RequestResponse createEmptyNoneRequestResponse() {
            RequestResponse requestResponse = new RequestResponse();
            requestResponse.name = "empty-none";
            requestResponse.type = "none";
            // SCIPIO: This is an error; Element.getAttribute returns empty string if missing, so this is not equivalent to
            // rest of code
            //requestResponse.value = null;
            requestResponse.value = "";
            return requestResponse;
        }

        public String name;
        public String type;
        public String value;
        public String statusCode;
        public boolean saveLastView = false;
        public boolean saveCurrentView = false;
        public boolean saveHomeView = false;
        public Map<String, String> redirectParameterMap = new HashMap<String, String>();
        public Map<String, String> redirectParameterValueMap = new HashMap<String, String>();
        public Set<String> excludeParameterSet = null; // SCIPIO: new 2017-04-24
        public String includeMode = "auto"; // SCIPIO: new 2017-04-24

        public RequestResponse() {
        }

        public RequestResponse(Element responseElement) {
            this.name = responseElement.getAttribute("name");
            this.type = responseElement.getAttribute("type");
            this.value = responseElement.getAttribute("value");
            this.statusCode = responseElement.getAttribute("status-code");
            this.saveLastView = "true".equals(responseElement.getAttribute("save-last-view"));
            this.saveCurrentView = "true".equals(responseElement.getAttribute("save-current-view"));
            this.saveHomeView = "true".equals(responseElement.getAttribute("save-home-view"));
            for (Element redirectParameterElement : UtilXml.childElementList(responseElement, "redirect-parameter")) {
                if (UtilValidate.isNotEmpty(redirectParameterElement.getAttribute("value"))) {
                    this.redirectParameterValueMap.put(redirectParameterElement.getAttribute("name"), redirectParameterElement.getAttribute("value"));
                } else {
                    String from = redirectParameterElement.getAttribute("from");
                    if (UtilValidate.isEmpty(from))
                        from = redirectParameterElement.getAttribute("name");
                    this.redirectParameterMap.put(redirectParameterElement.getAttribute("name"), from);
                }
            }
            // SCIPIO: new 2017-04-24
            Set<String> excludeParameterSet = new HashSet<>();
            for (Element redirectParametersElement : UtilXml.childElementList(responseElement, "redirect-parameters")) {
                for (Element redirectParameterElement : UtilXml.childElementList(redirectParametersElement, "param")) {
                    if ("exclude".equals(redirectParameterElement.getAttribute("mode"))) {
                        excludeParameterSet.add(redirectParameterElement.getAttribute("name"));
                    } else {
                        if (UtilValidate.isNotEmpty(redirectParameterElement.getAttribute("value"))) {
                            this.redirectParameterValueMap.put(redirectParameterElement.getAttribute("name"), redirectParameterElement.getAttribute("value"));
                        } else {
                            String from = redirectParameterElement.getAttribute("from");
                            if (UtilValidate.isEmpty(from))
                                from = redirectParameterElement.getAttribute("name");
                            this.redirectParameterMap.put(redirectParameterElement.getAttribute("name"), from);
                        }
                    }
                }
                this.includeMode = redirectParametersElement.getAttribute("include-mode");
            } 
            if (excludeParameterSet.size() > 0) {
                this.excludeParameterSet = Collections.unmodifiableSet(excludeParameterSet);
            }
        }

        // SCIPIO: Added getters for languages that can't read public properties (2017-05-08)
        
        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getValue() {
            return value;
        }

        public String getStatusCode() {
            return statusCode;
        }

        public boolean isSaveLastView() {
            return saveLastView;
        }

        public boolean isSaveCurrentView() {
            return saveCurrentView;
        }

        public boolean isSaveHomeView() {
            return saveHomeView;
        }

        public Map<String, String> getRedirectParameterMap() {
            return redirectParameterMap;
        }

        public Map<String, String> getRedirectParameterValueMap() {
            return redirectParameterValueMap;
        }

        public Set<String> getExcludeParameterSet() {
            return excludeParameterSet;
        }

        public String getIncludeMode() {
            return includeMode;
        }
    }

    public static class ViewMap {
        public String viewMap;
        public String name;
        public String page;
        public String type;
        public String info;
        public String contentType;
        public String encoding;
        public String description;
        public boolean noCache = false;

        public ViewMap(Element viewMapElement) {
            this.name = viewMapElement.getAttribute("name");
            this.page = viewMapElement.getAttribute("page");
            this.type = viewMapElement.getAttribute("type");
            this.info = viewMapElement.getAttribute("info");
            this.contentType = viewMapElement.getAttribute("content-type");
            this.noCache = "true".equals(viewMapElement.getAttribute("no-cache"));
            this.encoding = viewMapElement.getAttribute("encoding");
            this.description = UtilXml.childElementValue(viewMapElement, "description");
            if (UtilValidate.isEmpty(this.page)) {
                this.page = this.name;
            }
        }

        // SCIPIO: Added getters for languages that can't read public properties (2017-05-08)
        
        public String getViewMap() {
            return viewMap;
        }

        public String getName() {
            return name;
        }

        public String getPage() {
            return page;
        }

        public String getType() {
            return type;
        }

        public String getInfo() {
            return info;
        }

        public String getContentType() {
            return contentType;
        }

        public String getEncoding() {
            return encoding;
        }

        public String getDescription() {
            return description;
        }

        public boolean isNoCache() {
            return noCache;
        }
    }
    
    /**
     * SCIPIO: Implements "view-as-json" element in site-conf.xsd.
     * Added 2017-05-15.
     */
    public static class ViewAsJsonConfig {
        public boolean enabled;
        public boolean updateSession;
        public boolean regularLogin;
        public String jsonRequestUri;
        
        public ViewAsJsonConfig(Element element) {
            this.enabled = UtilMisc.booleanValue(element.getAttribute("enabled"), false);
            this.updateSession = UtilMisc.booleanValue(element.getAttribute("update-session"), false);
            this.regularLogin = UtilMisc.booleanValue(element.getAttribute("regular-login"), false);
            this.jsonRequestUri = element.getAttribute("json-request-uri");
            if (jsonRequestUri.isEmpty()) this.jsonRequestUri = null;
        }
        
        public ViewAsJsonConfig() {
            // all false/null by default
        }

        public boolean isEnabled() {
            return enabled;
        }
        public boolean isUpdateSession() {
            return updateSession;
        }
        public boolean isRegularLogin() {
            return regularLogin;
        }
        public String getJsonRequestUri() {
            return jsonRequestUri;
        }
        public String getJsonRequestUriAlways() throws WebAppConfigurationException {
            if (jsonRequestUri == null) throw new WebAppConfigurationException(new IllegalStateException("Cannot forward view-as-json: missing json-request-uri configuration"));
            return jsonRequestUri;
        }
    }
}
