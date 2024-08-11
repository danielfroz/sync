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
package io.syncframework.core;

import io.syncframework.api.SessionContext;

/**
 * This SessionFactory Implementation is meant to be used when applications are not utilizing
 * sessions. New requests will lead to utilize same bogus session implementation.
 * This may speed up things and keep memory low.
 * 
 * @author dfroz
 *
 */
public class SessionFactoryStatelessImpl implements SessionFactory {
	private static final Session bogus = new StatelessSession();
	
	public SessionFactoryStatelessImpl() {
		bogus.setRecent(false);
		bogus.setCreationTime(System.currentTimeMillis());
	}

	public Session find(Request request) {
		bogus.setAccessTime(System.currentTimeMillis());
		return bogus;
	}

	public void start(ApplicationConfig config) {
		// do nothing
	}

	public void stop() {
		// do nothing
	}
	
	public String toString() {
		return "disabled";
	}
}

class StatelessSession extends Session {
	private static final long serialVersionUID = 4501346595414405798L;
	private static final SessionContext context = new BogusSessionContext();
	
	/**
	 * This method prevents the Responders to create Set-Cookie header for the clients.
	 * As none will be actually needed.
	 */
	@Override
	public boolean isRecent() {
		return false;
	}
	
	@Override
	public SessionContext getSessionContext() {
		return context;
	}
	
	@Override
	public String toString() {
		return "SessionStateless-"+context;
	}
}

class BogusSessionContext extends SessionContext {
	private static final long serialVersionUID = -432846513263222879L;
	
	@Override
	public Object put(String key, Object value) {
		return null;
	}
}


