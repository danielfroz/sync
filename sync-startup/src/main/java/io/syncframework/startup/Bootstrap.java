/*
 * Copyright 2016 SyncObjects Ltda.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syncframework.startup;

import java.io.File;
import java.lang.reflect.Method;


/**
 * Bootstrap loader for the application server. This class is in charge of setting up the base directory and load the Server implementation
 * according to the default or predefined server (-Dsync.server=<class>)
 * 
 * @author dfroz
 */
public class Bootstrap {
	private static final String SYNC_SERVER = "sync.server";
	private static final String SYNC_BASE = "sync.base";
	private static final String defaultServerFactoryClassName = "io.syncframework.netty.ServerFactory";
	private static final String serverInterfaceName = "io.syncframework.core.Server";
	private static String serverFactoryClassName = null;
	
	static {
		String s = System.getProperty(SYNC_SERVER);
		if(s == null)
			s = defaultServerFactoryClassName;
		serverFactoryClassName = s;
		
		String basedir = System.getProperty(SYNC_BASE);
		if(basedir == null) {
			String userdir = System.getProperty("user.dir");
			basedir = userdir;
		}
		
		File dir = new File(basedir);
		if(!dir.isDirectory())
			throw new RuntimeException(dir.getAbsolutePath()+" is not a valid directory");
		
		System.setProperty(SYNC_BASE, dir.getAbsolutePath());
	}

	public void start() throws Exception {
		System.out.println("Starting SYNC|Framework");
		String basedir = System.getProperty(SYNC_BASE);
				
		// lib folder
		File libraryDirectory = new File(basedir, "lib");
		if(!libraryDirectory.exists())
			throw new RuntimeException(libraryDirectory.getAbsolutePath()+" is not a valid directory");
		
		//ClassLoader parent = this.getClass().getClassLoader();
		ClassLoader parent = Thread.currentThread().getContextClassLoader();
		StandardClassLoader commonClassLoader = ClassLoaderFactory.createClassLoader(new File[] { libraryDirectory }, parent);		
		StandardClassLoader serverClassLoader = ClassLoaderFactory.createClassLoader(new File[0], commonClassLoader);
		
		Thread.currentThread().setContextClassLoader(serverClassLoader);
		
		Method method = null;
		Class<?> serverFactoryClass = serverClassLoader.loadClass(serverFactoryClassName); //Class.forName(serverFactoryClassName, false, serverClassLoader);
		method = serverFactoryClass.getMethod("getServer", new Class<?>[0]);
		Object serverObject = method.invoke(null, new Object[0]);
		
		Class<?> serverClass = serverClassLoader.loadClass(serverInterfaceName); // Class.forName(serverInterfaceName, false, serverClassLoader);
		method = serverClass.getDeclaredMethod("init", new Class<?>[0]);
		method.invoke(serverObject, new Object[0]);
	}

	public static void main(String args[]) {
		Bootstrap daemon = new Bootstrap();
		try {
			daemon.start();
		}
		catch(Exception e) {
			System.err.println("failed to start SYNC|Framework");
			e.printStackTrace();
		}
	}
}