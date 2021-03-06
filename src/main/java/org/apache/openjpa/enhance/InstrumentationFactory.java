/*
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
 */
package org.apache.openjpa.enhance;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.openjpa.lib.util.JavaVendors;


/**
 * Factory for obtaining an {@link Instrumentation} instance.
 *
 * @author Marc Prud'hommeaux
 * @since 1.0.0
 */
public class InstrumentationFactory {
	private static Instrumentation _inst;
	private static boolean _dynamicallyInstall = true;
	private static final String _name = InstrumentationFactory.class.getName();
	
	/**
	 * This method is not synchronized because when the agent is loaded from
	 * getInstrumentation() that method will cause agentmain(..) to be called.
	 * Synchronizing this method would cause a deadlock.
	 *
	 * @param inst The instrumentation instance to be used by this factory.
	 */
	public static void setInstrumentation(Instrumentation inst) {
		_inst = inst;
	}
	
	/**
	 * Configures whether or not this instance should attempt to dynamically
	 * install an agent in the VM. Defaults to <code>true</code>.
	 */
	public static synchronized void setDynamicallyInstallAgent(boolean val) {
		_dynamicallyInstall = val;
	}
	
	private static Method defineClass;
	
	static {
		try {
			defineClass = ClassLoader.class.getDeclaredMethod("defineClass", byte[].class, int.class, int.class);
			defineClass.setAccessible(true);
		}
		catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static <T> Class<T> addClassToCl(ClassLoader cl, String className) throws Exception {
		InputStream classStream =
		InstrumentationFactory.class.getResourceAsStream("/" + className + ".class");
		Objects.requireNonNull(classStream);
		byte[] clazz = IOUtils.toByteArray(classStream);
		
		return (Class<T>) defineClass.invoke(cl, clazz, 0, clazz.length);
	}
	
	/**
	 * @return null if Instrumentation can not be obtained, or if any
	 * Exceptions are encountered.
	 */
	public static synchronized Instrumentation getInstrumentation(Logger LOGGER) {
		if ( _inst != null || !_dynamicallyInstall)
			return _inst;
		
		// end run()
		AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
			// Dynamic agent enhancement should only occur when the OpenJPA library is
			// loaded using the system class loader.  Otherwise, the OpenJPA
			// library may get loaded by separate, disjunct loaders, leading to linkage issues.
			try {
				ClassLoader sysCl = ClassLoader.getSystemClassLoader();
				if (!InstrumentationFactory.class.getClassLoader().equals(sysCl)) {
					LOGGER.warning("Attempting to load instrumentation on non system classloader");
					
					Class<InstrumentationFactory> remoteInst = addClassToCl(sysCl, "org/apache/openjpa/enhance/InstrumentationFactory");
					addClassToCl(sysCl, "org/apache/openjpa/lib/util/JavaVendors");
					
					_inst = (Instrumentation) remoteInst.getDeclaredMethod("getInstrumentation", Logger.class).invoke(null, LOGGER);
					return null;
				}
			} catch (Throwable t) {
				LOGGER.log(Level.SEVERE, "Error", t);
			}
			JavaVendors vendor = JavaVendors.getCurrentVendor();
			File toolsJar = null;
			// When running on IBM, the attach api classes are packaged in vm.jar which is a part
			// of the default vm classpath.
			if (vendor.isIBM() == false) {
				// If we can't find the tools.jar and we're not on IBM we can't load the agent.
				toolsJar = findToolsJar(LOGGER);
				if (toolsJar == null) {
					LOGGER.warning("Couldn't find non IBM tools.jar");
					return null;
				}
			}
			
			Class<?> vmClass = loadVMClass(toolsJar, vendor);
			if (vmClass == null) {
				LOGGER.warning("Couldn't find VM class");
				return null;
			}
			String agentPath = getAgentJar();
			if (agentPath == null) {
				LOGGER.warning("Couldn't create Agent Jar");
				return null;
			}
			loadAgent(agentPath, vmClass);
			return null;
		});
		// If the load(...) agent call was successful, this variable will no
		// longer be null.
		return _inst;
	}//end getInstrumentation()
	
	/**
	 *  The method that is called when a jar is added as an agent at runtime.
	 *  All this method does is store the {@link Instrumentation} for
	 *  later use.
	 */
	public static void agentmain(String agentArgs, Instrumentation inst) {
		InstrumentationFactory.setInstrumentation(inst);
	}
	
	/**
	 * Create a new jar file for the sole purpose of specifying an Agent-Class
	 * to load into the JVM.
	 *
	 * @return absolute path to the new jar file.
	 */
	private static String createAgentJar() throws IOException {
		File file =
		File.createTempFile(InstrumentationFactory.class.getName(), ".jar");
		file.deleteOnExit();
		
		ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(file));
		zout.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
		
		PrintWriter writer = new PrintWriter(new OutputStreamWriter(zout));
		
		writer.println("Agent-Class: " + InstrumentationFactory.class.getName());
		writer.println("Can-Redefine-Classes: true");
		// IBM doesn't support retransform
		writer.println("Can-Retransform-Classes: " + Boolean.toString(JavaVendors.getCurrentVendor().isIBM() == false));
		
		writer.close();
		
		return file.getAbsolutePath();
	}
	
	/**
	 * This private worker method attempts to find [java_home]/lib/tools.jar.
	 * Note: The tools.jar is a part of the SDK, it is not present in the JRE.
	 *
	 * @return If tools.jar can be found, a File representing tools.jar. <BR>
	 *         If tools.jar cannot be found, null.
	 */
	private static File findToolsJar(Logger LOGGER) {
		{
			File out = findToolsJar(LOGGER, new File(System.getProperty("java.home")));
			if (out != null) {
				return out;
			}
		}
		
		String bootClassPath = System.getProperty("sun.boot.class.path");
		String[] paths = bootClassPath.split(Pattern.quote(File.pathSeparator));
		for (String path : paths) {
			File jre = new File(path);
			if (jre.isFile()) {
				jre = jre.getParentFile();
			}
			File out = findToolsJar(LOGGER, jre);
			if (out != null) {
				return out;
			}
		}
		
		LOGGER.info("Couldn't find tools.jar in boot classpath or JAVA_HOME");
		LOGGER.info("Bootclasspath: " + bootClassPath);
		return null;
	}
	
	private static File findToolsJar(Logger LOGGER, File javaHomeFile) {
		File toolsJarFile = new File(javaHomeFile, "lib" + File.separator + "tools.jar");
		if (!toolsJarFile.exists()) {
			LOGGER.info(_name + ".findToolsJar() -- failed to find " + toolsJarFile.getAbsolutePath());
			// If we're on an IBM SDK, then remove /jre off of java.home and try again.
			if (javaHomeFile.getAbsolutePath().endsWith(File.separator + "jre")) {
				javaHomeFile = javaHomeFile.getParentFile();
				toolsJarFile = new File(javaHomeFile, "lib" + File.separator + "tools.jar");
				if (!toolsJarFile.exists()) {
					LOGGER.info(_name + ".findToolsJar() -- failed find " + toolsJarFile.getAbsolutePath());
					return null;
				}
			} else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
				// If we're on a Mac, then change the search path to use ../Classes/classes.jar.
				if (javaHomeFile.getAbsolutePath().endsWith(File.separator + "Home") == true) {
					javaHomeFile = javaHomeFile.getParentFile();
					toolsJarFile = new File(javaHomeFile, "Classes" + File.separator + "classes.jar");
					if (!toolsJarFile.exists()) {
						LOGGER.info(_name + ".findToolsJar() -- for Mac OS couldn't find " + toolsJarFile.getAbsolutePath());
						return null;
					}
				}
			}
		}
		
		if (!toolsJarFile.exists()) {
			LOGGER.info(_name + ".findToolsJar() -- failed to find " + toolsJarFile.getAbsolutePath());
			return null;
		} else {
			LOGGER.info(_name + ".findToolsJar() -- found " + toolsJarFile.getAbsolutePath());
			return toolsJarFile;
		}
	}
	
	/**
	 * This private worker method will return a fully qualified path to a jar
	 * that has this class defined as an Agent-Class in it's
	 * META-INF/manifest.mf file. Under normal circumstances the path should
	 * point to the OpenJPA jar. If running in a development environment a
	 * temporary jar file will be created.
	 *
	 * @return absolute path to the agent jar or null if anything unexpected
	 * happens.
	 */
	private static String getAgentJar() {
		File agentJarFile = null;
		// Find the name of the File that this class was loaded from. That
		// jar *should* be the same location as our agent.
		CodeSource cs =
		InstrumentationFactory.class.getProtectionDomain().getCodeSource();
		if (cs != null) {
			URL loc = cs.getLocation();
			if(loc!=null){
				agentJarFile = new File(loc.getFile());
			}
		}
		
		// Determine whether the File that this class was loaded from has this
		// class defined as the Agent-Class.
		boolean createJar = false;
		if (cs == null || agentJarFile == null
		|| agentJarFile.isDirectory() == true) {
			createJar = true;
		}else if(validateAgentJarManifest(agentJarFile, _name) == false){
			// We have an agentJarFile, but this class isn't the Agent-Class.
			createJar=true;
		}
		
		String agentJar;
		if (createJar == true) {
			// This can happen when running in eclipse as an OpenJPA
			// developer or for some reason the CodeSource is null. We
			// should log a warning here because this will create a jar
			// in your temp directory that doesn't always get cleaned up.
			try {
				agentJar = createAgentJar();
			} catch (IOException ioe) {
				new IllegalStateException(_name + ".getAgentJar() caught unexpected "
				+ "exception.", ioe).printStackTrace();
				agentJar = null;
			}
		} else {
			agentJar = agentJarFile.getAbsolutePath();
		}
		
		return agentJar;
	}//end getAgentJar
	
	/**
	 * Attach and load an agent class.
	 *
	 * @param agentJar absolute path to the agent jar.
	 * @param vmClass VirtualMachine.class from tools.jar.
	 */
	private static void loadAgent(String agentJar, Class<?> vmClass) {
		try {
			// first obtain the PID of the currently-running process
			// ### this relies on the undocumented convention of the
			// RuntimeMXBean's
			// ### name starting with the PID, but there appears to be no other
			// ### way to obtain the current process' id, which we need for
			// ### the attach process
			RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
			String pid = runtime.getName();
			if (pid.indexOf("@") != -1)
				pid = pid.substring(0, pid.indexOf("@"));
			
			// JDK1.6: now attach to the current VM so we can deploy a new agent
			// ### this is a Sun JVM specific feature; other JVMs may offer
			// ### this feature, but in an implementation-dependent way
			Object vm =
			vmClass.getMethod("attach", new Class<?>[] { String.class })
			.invoke(null, new Object[] { pid });
			// now deploy the actual agent, which will wind up calling
			// agentmain()
			vmClass.getMethod("loadAgent", new Class[] { String.class })
			.invoke(vm, new Object[] { agentJar });
			vmClass.getMethod("detach", new Class[] {}).invoke(vm,
			new Object[] {});
		} catch (Throwable t) {
			new IllegalStateException(_name + ".loadAgent() caught an exception", t).printStackTrace();
		}
	}
	
	/**
	 * If <b>ibm</b> is false, this private method will create a new URLClassLoader and attempt to load the
	 * com.sun.tools.attach.VirtualMachine class from the provided toolsJar file.
	 *
	 * <p>
	 * If <b>ibm</b> is true, this private method will ignore the toolsJar parameter and load the
	 * com.ibm.tools.attach.VirtualMachine class.
	 *
	 *
	 * @return The AttachAPI VirtualMachine class <br>
	 *         or null if something unexpected happened.
	 */
	private static Class<?> loadVMClass(File toolsJar, JavaVendors vendor) {
		try {
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			String cls = vendor.getVirtualMachineClassName();
			if (vendor.isIBM() == false) {
				loader = new URLClassLoader(new URL[] { toolsJar.toURI().toURL() }, loader);
			}
			return loader.loadClass(cls);
		} catch (Exception e) {
			new IllegalStateException(_name + ".loadVMClass() failed to load the VirtualMachine class", e).printStackTrace();
		}
		return null;
	}
	
	/**
	 * This private worker method will validate that the provided agentClassName
	 * is defined as the Agent-Class in the manifest file from the provided jar.
	 *
	 * @param agentJarFile
	 *            non-null agent jar file.
	 * @param agentClassName
	 *            the non-null agent class name.
	 * @return True if the provided agentClassName is defined as the Agent-Class
	 *         in the manifest from the provided agentJarFile. False otherwise.
	 */
	private static boolean validateAgentJarManifest(File agentJarFile,
	                                                String agentClassName) {
		try {
			JarFile jar = new JarFile(agentJarFile);
			Manifest manifest = jar.getManifest();
			if (manifest == null) {
				return false;
			}
			Attributes attributes = manifest.getMainAttributes();
			String ac = attributes.getValue("Agent-Class");
			if (ac != null && ac.equals(agentClassName)) {
				return true;
			}
		} catch (Exception e) {
			new IllegalStateException(_name
			+ ".validateAgentJarManifest() caught unexpected "
			+ "exception", e).printStackTrace();
		}
		return false;
	}// end validateAgentJarManifest
}
