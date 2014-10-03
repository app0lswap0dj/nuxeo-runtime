/*
 * Copyright (c) 2006-2013 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 */

package org.nuxeo.runtime.api;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Properties;

import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.Environment;
import org.nuxeo.common.collections.ListenerList;
import org.nuxeo.runtime.RuntimeService;
import org.nuxeo.runtime.RuntimeServiceEvent;
import org.nuxeo.runtime.RuntimeServiceListener;
import org.nuxeo.runtime.api.login.LoginAs;
import org.nuxeo.runtime.api.login.LoginService;
import org.nuxeo.runtime.trackers.files.FileEvent;
import org.nuxeo.runtime.trackers.files.FileEventTracker;

/**
 * This class is the main entry point to a Nuxeo runtime application.
 * <p>
 * It offers an easy way to create new sessions, to access system services and
 * other resources.
 * <p>
 * There are two type of services:
 * <ul>
 * <li>Global Services - these services are uniquely defined by a service
 * class, and there is an unique instance of the service in the system per
 * class.
 * <li>Local Services - these services are defined by a class and an URI. This
 * type of service allows multiple service instances for the same class of
 * services. Each instance is uniquely defined in the system by an URI.
 * </ul>
 *
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
public final class Framework {

    private static final Log log = LogFactory.getLog(Framework.class);

    /**
     * Global dev property
     *
     * @since 5.6
     * @see #isDevModeSet()
     */
    public static final String NUXEO_DEV_SYSTEM_PROP = "org.nuxeo.dev";

    /**
     * Global testing property
     *
     * @since 5.6
     * @see #isTestModeSet()
     */
    public static final String NUXEO_TESTING_SYSTEM_PROP = "org.nuxeo.runtime.testing";

    /**
     * Property to control strict runtime mode
     *
     * @since 5.6
     * @see #handleDevError(Throwable)
     */
    public static final String NUXEO_STRICT_RUNTIME_SYSTEM_PROP = "org.nuxeo.runtime.strict";

    /**
     * The runtime instance.
     */
    private static RuntimeService runtime;

    private static final ListenerList listeners = new ListenerList();

    /**
     * A class loader used to share resources between all bundles.
     * <p>
     * This is useful to put resources outside any bundle (in a directory on
     * the file system) and then refer them from XML contributions.
     * <p>
     * The resource directory used by this loader is
     * ${nuxeo_data_dir}/resources whee ${nuxeo_data_dir} is usually
     * ${nuxeo_home}/data
     */
    protected static SharedResourceLoader resourceLoader;

    /**
     * Whether or not services should be exported as OSGI services. This is
     * controlled by the ${ecr.osgi.services} property. The default is false.
     */
    protected static Boolean isOSGiServiceSupported;


    // Utility class.
    private Framework() {
    }

    public static void initialize(RuntimeService runtimeService)
            throws Exception {
        if (runtime != null) {
            throw new Exception("Nuxeo Framework was already initialized");
        }
        runtime = runtimeService;
        reloadResourceLoader();
        runtime.start();
    }

    public static void reloadResourceLoader() throws Exception {
        File rs = new File(Environment.getDefault().getData(), "resources");
        rs.mkdirs();
        resourceLoader = new SharedResourceLoader(
                new URL[] { rs.toURI().toURL() },
                Framework.class.getClassLoader());
    }

    /**
     * Reload the resources loader, keeping URLs already tracked, and adding
     * possibility to add or remove some URLs.
     * <p>
     * Useful for hot reload of jars.
     *
     * @throws MalformedURLException
     * @since 5.6
     * @throws Exception
     */
    public static void reloadResourceLoader(List<URL> urlsToAdd,
            List<URL> urlsToRemove) throws MalformedURLException {
        File rs = new File(Environment.getDefault().getData(), "resources");
        rs.mkdirs();
        URL[] existing = null;
        if (resourceLoader != null) {
            existing = resourceLoader.getURLs();
        }
        // reinit
        resourceLoader = new SharedResourceLoader(
                new URL[] { rs.toURI().toURL() },
                Framework.class.getClassLoader());
        // add back existing urls unless they should be removed, and add new
        // urls
        if (existing != null) {
            for (URL oldURL : existing) {
                if (urlsToRemove == null || !urlsToRemove.contains(oldURL)) {
                    resourceLoader.addURL(oldURL);
                }
            }
        }
        if (urlsToAdd != null) {
            for (URL newURL : urlsToAdd) {
                resourceLoader.addURL(newURL);
            }
        }
    }

    public static void shutdown() throws Exception {
        if (runtime == null) {
            throw new IllegalStateException("runtime not exist");
        }
        try {
            runtime.stop();
        } finally {
            runtime = null;
        }
    }

    /**
     * Tests whether or not the runtime was initialized.
     *
     * @return true if the runtime was initialized, false otherwise
     */
    public static synchronized boolean isInitialized() {
        return runtime != null;
    }

    public static SharedResourceLoader getResourceLoader() {
        return resourceLoader;
    }

    /**
     * Gets the runtime service instance.
     *
     * @return the runtime service instance
     */
    public static RuntimeService getRuntime() {
        return runtime;
    }

    /**
     * Gets a service given its class.
     */
    public static <T> T getService(Class<T> serviceClass) {
        ServiceProvider provider = DefaultServiceProvider.getProvider();
        if (provider != null) {
            return provider.getService(serviceClass);
        }
        checkRuntimeInitialized();
        // TODO impl a runtime service provider
        return runtime.getService(serviceClass);
    }

    /**
     * Gets a service given its class.
     */
    public static <T> T getLocalService(Class<T> serviceClass) {
        return getService(serviceClass);
    }

    /**
     * Lookup a registered object given its key.
     */
    public static Object lookup(String key) {
        return null; // TODO
    }

    /**
     * Login in the system as the system user (a pseudo-user having all
     * privileges).
     *
     * @return the login session if successful. Never returns null.
     * @throws LoginException on login failure
     */
    public static LoginContext login() throws LoginException {
        checkRuntimeInitialized();
        LoginService loginService = runtime.getService(LoginService.class);
        if (loginService != null) {
            return loginService.login();
        }
        return null;
    }

    /**
     * Login in the system as the system user (a pseudo-user having all
     * privileges). The given username will be used to identify the user id
     * that called this method.
     *
     * @param username the originating user id
     * @return the login session if successful. Never returns null.
     * @throws LoginException on login failure
     */
    public static LoginContext loginAs(String username) throws LoginException {
        checkRuntimeInitialized();
        LoginService loginService = runtime.getService(LoginService.class);
        if (loginService != null) {
            return loginService.loginAs(username);
        }
        return null;
    }

    /**
     * Login in the system as the given user without checking the password.
     *
     * @param username the user name to login as.
     * @return the login context
     * @throws LoginException if any error occurs
     * @since 5.4.2
     */
    public static LoginContext loginAsUser(String username)
            throws LoginException {
        return getLocalService(LoginAs.class).loginAs(username);
    }

    /**
     * Login in the system as the given user using the given password.
     *
     * @param username the username to login
     * @param password the password
     * @return a login session if login was successful. Never returns null.
     * @throws LoginException if login failed
     */
    public static LoginContext login(String username, Object password)
            throws LoginException {
        checkRuntimeInitialized();
        LoginService loginService = runtime.getService(LoginService.class);
        if (loginService != null) {
            return loginService.login(username, password);
        }
        return null;
    }

    /**
     * Login in the system using the given callback handler for login info
     * resolution.
     *
     * @param cbHandler used to fetch the login info
     * @return the login context
     * @throws LoginException
     */
    public static LoginContext login(CallbackHandler cbHandler)
            throws LoginException {
        checkRuntimeInitialized();
        LoginService loginService = runtime.getService(LoginService.class);
        if (loginService != null) {
            return loginService.login(cbHandler);
        }
        return null;
    }

    public static void sendEvent(RuntimeServiceEvent event) {
        Object[] listenersArray = listeners.getListeners();
        for (Object listener : listenersArray) {
            ((RuntimeServiceListener) listener).handleEvent(event);
        }
    }

    /**
     * Registers a listener to be notified about runtime events.
     * <p>
     * If the listener is already registered, do nothing.
     *
     * @param listener the listener to register
     */
    public static void addListener(RuntimeServiceListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes the given listener.
     * <p>
     * If the listener is not registered, do nothing.
     *
     * @param listener the listener to remove
     */
    public static void removeListener(RuntimeServiceListener listener) {
        listeners.remove(listener);
    }

    /**
     * Gets the given property value if any, otherwise null.
     * <p>
     * The framework properties will be searched first then if any matching
     * property is found the system properties are searched too.
     *
     * @param key the property key
     * @return the property value if any or null otherwise
     */
    public static String getProperty(String key) {
        return getProperty(key, null);
    }

    /**
     * Gets the given property value if any, otherwise returns the given
     * default value.
     * <p>
     * The framework properties will be searched first then if any matching
     * property is found the system properties are searched too.
     *
     * @param key the property key
     * @param defValue the default value to use
     * @return the property value if any otherwise the default value
     */
    public static String getProperty(String key, String defValue) {
        checkRuntimeInitialized();
        return runtime.getProperty(key, defValue);
    }

    /**
     * Gets all the framework properties. The system properties are not
     * included in the returned map.
     *
     * @return the framework properties map. Never returns null.
     */
    public static Properties getProperties() {
        checkRuntimeInitialized();
        return runtime.getProperties();
    }

    /**
     * Expands any variable found in the given expression with the value of the
     * corresponding framework property.
     * <p>
     * The variable format is ${property_key}.
     * <p>
     * System properties are also expanded.
     */
    public static String expandVars(String expression) {
        checkRuntimeInitialized();
        return runtime.expandVars(expression);
    }

    public static boolean isOSGiServiceSupported() {
        if (isOSGiServiceSupported == null) {
            isOSGiServiceSupported = Boolean.valueOf(isBooleanPropertyTrue("ecr.osgi.services"));
        }
        return isOSGiServiceSupported.booleanValue();
    }

    /**
     * Returns true if dev mode is set.
     * <p>
     * Activating this mode, some of the code may not behave as it would in
     * production, to ease up debugging and working on developing the
     * application.
     * <p>
     * For instance, it'll enable hot-reload if some packages are installed
     * while the framework is running. It will also reset some caches when that
     * happens.
     * <p>
     * Before 5.6, when activating this mode, the Runtime Framework stopped on
     * low-level errors, see {@link #handleDevError(Throwable)} but this
     * behaviour has been removed.
     */
    public static boolean isDevModeSet() {
        return isBooleanPropertyTrue(NUXEO_DEV_SYSTEM_PROP);
    }

    /**
     * Returns true if test mode is set.
     * <p>
     * Activating this mode, some of the code may not behave as it would in
     * production, to ease up testing.
     */
    public static boolean isTestModeSet() {
        return isBooleanPropertyTrue(NUXEO_TESTING_SYSTEM_PROP);
    }

    /**
     * Returns true if given property is false when compared to a boolean
     * value. Returns false if given property in unset.
     * <p>
     * Checks for the system properties if property is not found in the runtime
     * properties.
     *
     * @since 5.8
     */
    public static boolean isBooleanPropertyFalse(String propName) {
        String v = getProperty(propName);
        if (v == null) {
            v = System.getProperty(propName);
        }
        if (StringUtils.isBlank(v)) {
            return false;
        }
        return !Boolean.parseBoolean(v);
    }

    /**
     * Returns true if given property is true when compared to a boolean value.
     * <p>
     * Checks for the system properties if property is not found in the runtime
     * properties.
     *
     * @since 5.6
     */
    public static boolean isBooleanPropertyTrue(String propName) {
        String v = getProperty(propName);
        if (v == null) {
            v = System.getProperty(propName);
        }
        return Boolean.parseBoolean(v);
    }

    /**
     * Since 5.6, this method stops the application if property
     * {@link #NUXEO_STRICT_RUNTIME_SYSTEM_PROP} is set to true, and one of the
     * following errors occurred during startup.
     * <ul>
     * <li>Component XML parse error.
     * <li>Contribution to an unknown extension point.
     * <li>Component with an unknown implementation class (the implementation
     * entry exists in the XML descriptor but cannot be resolved to a class).
     * <li>Uncatched exception on extension registration / unregistration
     * (either in framework or user component code)
     * <li>Uncatched exception on component activation / deactivation (either
     * in framework or user component code)
     * <li>Broken Nuxeo-Component MANIFEST entry. (i.e. the entry cannot be
     * resolved to a resource)
     * </ul>
     * <p>
     * Before 5.6, this method stopped the application if development mode was
     * enabled (i.e. org.nuxeo.dev system property is set) but this is not the
     * case anymore to handle a dev mode that does not stop the runtime
     * framework when using hot reload.
     *
     * @param t the exception or null if none
     */
    public static void handleDevError(Throwable t) {
        if (isBooleanPropertyTrue(NUXEO_STRICT_RUNTIME_SYSTEM_PROP)) {
            System.err.println("Fatal error caught in strict "
                    + "runtime mode => exiting.");
            t.printStackTrace();
            System.exit(1);
        } else {
            log.error(t, t);
        }
    }

    /**
     * @see FileEventTracker
     * @param file The file to delete
     * @param marker the marker Object
     */
    public static void trackFile(File aFile, Object aMarker) {
        FileEvent.onFile(Framework.class, aFile, aMarker).send();
    }

    /**
     * Strategy is now always FileDeleteStrategy.FORCE unless you've specified
     * another one
     *
     * @deprecated
     * @since 6.0
     * @see #trackFile(File, Object)
     * @param file The file to delete
     * @param marker the marker Object
     * @param fileDeleteStrategy add a custom delete strategy
     */
    @Deprecated
    public static void trackFile(File file, Object marker,
            FileDeleteStrategy fileDeleteStrategy) {
        trackFile(file, marker);
    }

    /**
     * @since 5.9.6
     */
    protected static void checkRuntimeInitialized() {
        if (runtime == null) {
            throw new IllegalStateException("Runtime not initialized");
        }
    }

    public static void main(String[] args) {
    }

}
