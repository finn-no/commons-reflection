/* Copyright (2007) Schibsted Søk AS
 *   This file is part of SESAT.
 *
 *   SESAT is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   SESAT is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with SESAT.  If not, see <http://www.gnu.org/licenses/>.
 */
package no.sesat.commons.reflect;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * <code>java.lang.reflect.Proxy</code> provides static methods for creating dynamic proxy
 * classes and instances, and it is also the superclass of all
 * dynamic proxy classes created by those methods.
 *
 * This class provides faster concurrent caching of Proxies through ConcurrentHashMaps,
 *  It delegates back to java.lang.reflect.Proxy for 
 *   the construction of new Proxies and the required synchronisation surrounding it.
 *
 * @author	@author <a href="mailto:mick@wever.org">Mck Semb Wever</a>
 * @version	$Id$
 * @see		java.lang.reflect.Proxy
 */
public final class ConcurrentProxy {
    
    /** parameter types of a proxy class constructor */
    private static final Class[] constructorParams = {InvocationHandler.class};

    /** maps a class loader to the proxy class cache for that loader */
    private static final Map<ClassLoader,Map<Object,Reference<Class>>> loaderToCache 
            = new WeakHashMap<ClassLoader,Map<Object,Reference<Class>>>();
    
    private static final ReentrantReadWriteLock loaderToCacheLock = new ReentrantReadWriteLock();

    /**
     * Prohibits instantiation.
     */
    private ConcurrentProxy() {
    }

    /**
     * Returns the <code>java.lang.Class</code> object for a proxy class
     * given a class loader and an array of interfaces.  The proxy class
     * will be defined by the specified class loader and will implement
     * all of the supplied interfaces.  If a proxy class for the same
     * permutation of interfaces has already been defined by the class
     * loader, then the existing proxy class will be returned; otherwise,
     * a proxy class for those interfaces will be generated dynamically
     * and defined by the class loader.
     *
     * <p>There are several restrictions on the parameters that may be
     * passed to <code>Proxy.getProxyClass</code>:
     *
     * <ul>
     * <li>All of the <code>Class</code> objects in the
     * <code>interfaces</code> array must represent interfaces, not
     * classes or primitive types.
     *
     * <li>No two elements in the <code>interfaces</code> array may
     * refer to identical <code>Class</code> objects.
     *
     * <li>All of the interface types must be visible by name through the
     * specified class loader.  In other words, for class loader
     * <code>cl</code> and every interface <code>i</code>, the following
     * expression must be true:
     * <pre>
     *     Class.forName(i.getName(), false, cl) == i
     * </pre>
     *
     * <li>All non-public interfaces must be in the same package;
     * otherwise, it would not be possible for the proxy class to
     * implement all of the interfaces, regardless of what package it is
     * defined in.
     *
     * <li>For any set of member methods of the specified interfaces
     * that have the same signature:
     * <ul>
     * <li>If the return type of any of the methods is a primitive
     * type or void, then all of the methods must have that same
     * return type.
     * <li>Otherwise, one of the methods must have a return type that
     * is assignable to all of the return types of the rest of the
     * methods.
     * </ul>
     *
     * <li>The resulting proxy class must not exceed any limits imposed
     * on classes by the virtual machine.  For example, the VM may limit
     * the number of interfaces that a class may implement to 65535; in
     * that case, the size of the <code>interfaces</code> array must not
     * exceed 65535.
     * </ul>
     *
     * <p>If any of these restrictions are violated,
     * <code>Proxy.getProxyClass</code> will throw an
     * <code>IllegalArgumentException</code>.  If the <code>interfaces</code>
     * array argument or any of its elements are <code>null</code>, a
     * <code>NullPointerException</code> will be thrown.
     *
     * <p>Note that the order of the specified proxy interfaces is
     * significant: two requests for a proxy class with the same combination
     * of interfaces but in a different order will result in two distinct
     * proxy classes.
     *
     * @param	loader the class loader to define the proxy class
     * @param	interfaces the list of interfaces for the proxy class
     *		to implement
     * @return	a proxy class that is defined in the specified class loader
     *		and that implements the specified interfaces
     * @throws	IllegalArgumentException if any of the restrictions on the
     *		parameters that may be passed to <code>getProxyClass</code>
     *		are violated
     * @throws	NullPointerException if the <code>interfaces</code> array
     *		argument or any of its elements are <code>null</code>
     */
    public static Class<?> getProxyClass(final ClassLoader loader, final Class<?>... interfaces) 
            throws IllegalArgumentException {
        
        // ---
        // start of copy from java.lang.reflect.Proxy
        // ---
        if (interfaces.length > 65535) {
            throw new IllegalArgumentException("interface limit exceeded");
        }

        Class proxyClass = null;

        /* collect interface names to use as key for proxy class cache */
        String[] interfaceNames = new String[interfaces.length];

        Set<Class> interfaceSet = new HashSet<Class>(); // for detecting duplicates
        for (int i = 0; i < interfaces.length; i++) {
            /*
             * Verify that the class loader resolves the name of this
             * interface to the same Class object.
             */
            String interfaceName = interfaces[i].getName();
            Class interfaceClass = null;
            try {
                interfaceClass = Class.forName(interfaceName, false, loader);
            }
            catch (ClassNotFoundException e) {
            }
            if (interfaceClass != interfaces[i]) {
                throw new IllegalArgumentException(interfaces[i] + " is not visible from class loader");
            }

            /*
             * Verify that the Class object actually represents an
             * interface.
             */
            if (!interfaceClass.isInterface()) {
                throw new IllegalArgumentException(interfaceClass.getName() + " is not an interface");
            }

            /*
             * Verify that this interface is not a duplicate.
             */
            if (interfaceSet.contains(interfaceClass)) {
                throw new IllegalArgumentException("repeated interface: " + interfaceClass.getName());
            }
            interfaceSet.add(interfaceClass);

            interfaceNames[i] = interfaceName;
        }

        /*
         * Using string representations of the proxy interfaces as
         * keys in the proxy class cache (instead of their Class
         * objects) is sufficient because we require the proxy
         * interfaces to be resolvable by name through the supplied
         * class loader, and it has the advantage that using a string
         * representation of a class makes for an implicit weak
         * reference to the class.
         */
        Object key = Arrays.asList(interfaceNames);

        /*
         * Find or create the proxy class cache for the class loader.
         */
        Map<Object,Reference<Class>> cache;
        try{
            loaderToCacheLock.readLock().lock();
            cache = loaderToCache.get(loader);
        }finally{
            loaderToCacheLock.readLock().unlock();
        }
        // window of opportunity here between locks that would result in duplicate put(..) call
        if (cache == null) {
            try{
                loaderToCacheLock.writeLock().lock();
                cache = new ConcurrentHashMap<Object,Reference<Class>>();
                loaderToCache.put(loader, cache);
            }finally{
                loaderToCacheLock.writeLock().unlock();
            }
        }
        
        // ---
        // end of copy from java.lang.reflect.Proxy
        // ---
        
        Object value = cache.get(key);
        if (value instanceof Reference) {
            proxyClass = (Class) ((Reference) value).get();
        }
        if (proxyClass == null) {
            // we haven't yet used it. delegate to the real Proxy class.
            proxyClass = java.lang.reflect.Proxy.getProxyClass(loader, interfaces);
            cache.put(key, new WeakReference<Class>(proxyClass));
        }
        return proxyClass;
        
    }

    // ---
    // start of copy from java.lang.reflect.Proxy
    // ---
    /**
     * Returns an instance of a proxy class for the specified interfaces
     * that dispatches method invocations to the specified invocation
     * handler.  This method is equivalent to:
     * <pre>
     *     Proxy.getProxyClass(loader, interfaces).
     *         getConstructor(new Class[] { InvocationHandler.class }).
     *         newInstance(new Object[] { handler });
     * </pre>
     *
     * <p><code>Proxy.newProxyInstance</code> throws
     * <code>IllegalArgumentException</code> for the same reasons that
     * <code>Proxy.getProxyClass</code> does.
     *
     * @param	loader the class loader to define the proxy class
     * @param	interfaces the list of interfaces for the proxy class
     *		to implement
     * @param   h the invocation handler to dispatch method invocations to
     * @return	a proxy instance with the specified invocation handler of a
     *		proxy class that is defined by the specified class loader
     *		and that implements the specified interfaces
     * @throws	IllegalArgumentException if any of the restrictions on the
     *		parameters that may be passed to <code>getProxyClass</code>
     *		are violated
     * @throws	NullPointerException if the <code>interfaces</code> array
     *		argument or any of its elements are <code>null</code>, or
     *		if the invocation handler, <code>h</code>, is
     *		<code>null</code>
     */
    public static Object newProxyInstance(
            final ClassLoader loader, 
            final Class<?>[] interfaces, 
            final InvocationHandler h) throws IllegalArgumentException {
        
        if (h == null) {
            throw new NullPointerException();
        }

        /*
         * Look up or generate the designated proxy class.
         */
        Class<?> cl = getProxyClass(loader, interfaces);

        /*
         * Invoke its constructor with the designated invocation handler.
         */
        try {
            Constructor<?> cons = cl.getConstructor(constructorParams);
            return (Object) cons.newInstance(new Object[] { h });
        }
        catch (NoSuchMethodException e) {
            throw new InternalError(e.toString());
        }
        catch (IllegalAccessException e) {
            throw new InternalError(e.toString());
        }
        catch (InstantiationException e) {
            throw new InternalError(e.toString());
        }
        catch (InvocationTargetException e) {
            throw new InternalError(e.toString());
        }
    }
    // ---
    // end of copy from java.lang.reflect.Proxy
    // ---

}