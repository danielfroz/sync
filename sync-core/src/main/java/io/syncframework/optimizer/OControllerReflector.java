/*
 * Copyright 2012-2017 SyncObjects Ltda.
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
package io.syncframework.optimizer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.syncframework.api.Action;
import io.syncframework.api.ApplicationContext;
import io.syncframework.api.Controller;
import io.syncframework.api.Converter;
import io.syncframework.api.CookieContext;
import io.syncframework.api.ErrorContext;
import io.syncframework.api.Interceptor;
import io.syncframework.api.MessageContext;
import io.syncframework.api.Parameter;
import io.syncframework.api.RequestContext;
import io.syncframework.api.Result;
import io.syncframework.api.SessionContext;
import io.syncframework.api.SessionManager;
import io.syncframework.util.StringUtils;

/**
 * ControllerReflector helps with the reflection tool of the @Controller
 * annotated classes.
 * In case of the failure with the declaration, it throws an exception reporting
 * the error.
 *
 * @author dfroz
 */
public class OControllerReflector implements Reflector {
	private static final Logger log = LoggerFactory.getLogger(OControllerReflector.class);

	private Class<?> clazz;
	private String clazzInternalName;
	private String clazzDescriptor;
	private final Map<String, Method> actions = new LinkedHashMap<String, Method>();
	private final Map<String, String> actionsType = new LinkedHashMap<String, String>();
	private final Map<String, Class<?>[]> interceptors = new LinkedHashMap<String, Class<?>[]>();
	private final Map<String, Class<?>> parameters = new LinkedHashMap<String, Class<?>>();
	private final Map<String, Method> getters = new LinkedHashMap<String, Method>();
	private final Map<String, Method> setters = new LinkedHashMap<String, Method>();
	private final Map<String, Class<?>> converters = new LinkedHashMap<String, Class<?>>();
	private String url;
	private SessionManager session;
	private String applicationContext;
	private String cookieContext;
	private String errorContext;
	private String messageContext;
	private String requestContext;
	private String sessionContext;

	public OControllerReflector(Class<?> clazz) {
		this.clazz = clazz;
		this.clazzInternalName = Type.getInternalName(clazz);
		this.clazzDescriptor = Type.getDescriptor(clazz);
	}

	/**
	 * reflect clazz
	 */
	public void reflect() throws ReflectorException {
		if (clazz == null)
			throw new IllegalArgumentException("clazz is null");

		/*
		 * Reflecting Controller Annotation
		 */
		Annotation annotation = clazz.getAnnotation(Controller.class);
		if (annotation == null)
			throw new ReflectorException("@Controller annotation not defined for class " + clazz.getName());

		Controller controllerAnnotation = (Controller) annotation;
		this.url = controllerAnnotation.url();
		this.session = controllerAnnotation.session();

		/*
		 * Reflecting @Parameter Contexts
		 */
		for (Field field : clazz.getDeclaredFields()) {
			// Parameter annotation type to Contexts are optional.
			// so no need to check whether @Parameter is present or not.
			//
			// if(field.isAnnotationPresent(Parameter.class))
			// continue;
			//

			Class<?> type = field.getType();
			if (!type.equals(ApplicationContext.class) &&
					!type.equals(CookieContext.class) &&
					!type.equals(ErrorContext.class) &&
					!type.equals(MessageContext.class) &&
					!type.equals(RequestContext.class) &&
					!type.equals(SessionContext.class)) {
				// check for Context parameters only
				continue;
			}

			if (type.equals(ApplicationContext.class)) {
				applicationContext = field.getName();
			} else if (type.equals(CookieContext.class)) {
				cookieContext = field.getName();
			} else if (type.equals(ErrorContext.class)) {
				errorContext = field.getName();
			} else if (type.equals(MessageContext.class)) {
				messageContext = field.getName();
			} else if (type.equals(RequestContext.class)) {
				requestContext = field.getName();
			} else if (type.equals(SessionContext.class)) {
				sessionContext = field.getName();
			}

			if (log.isTraceEnabled())
				log.trace("@Context " + clazz.getName() + "." + field.getName() + " loaded");
		}

		/*
		 * Reflecting Parameters
		 * 
		 * Very important to notice that we are not only reflecting the Fields
		 * but also the Getters and Setters for each field in the same operation
		 * in case that one getter or setter is NOT found, throw an Exception.
		 * 
		 * In case that the @Parameter utilizes converter
		 * (@Parameter(convert=Converter.class)), then also include
		 * it under the converters.
		 */
		for (Field field : clazz.getDeclaredFields()) {
			if (!field.isAnnotationPresent(Parameter.class))
				continue;

			Class<?> type = field.getType();
			if (type.isPrimitive()) {
				throw new ReflectorException(
						"@Parameter " + clazz.getName() + "." + field.getName() + " cannot be defined as primitive");
			}
			// check for Contexts... we already treated the contexts
			if (type.equals(ApplicationContext.class) ||
					type.equals(CookieContext.class) ||
					type.equals(ErrorContext.class) ||
					type.equals(MessageContext.class) ||
					type.equals(RequestContext.class) ||
					type.equals(SessionContext.class)) {
				// check for @Parameters only but no Contexts
				continue;
			}

			//
			// check for the converter
			//
			Parameter parameter = (Parameter) field.getAnnotation(Parameter.class);
			Class<?> converter = parameter.converter();
			if (converter != Object.class) {
				// check if Class implements Converter
				if (!Converter.class.isAssignableFrom(converter)) {
					throw new ReflectorException(converter.getName() + " is a non qualified @Converter");
				}
				converters.put(field.getName(), converter);
			}

			parameters.put(field.getName(), type);

			String getterMethodName = "get" + StringUtils.capitalize(field.getName());
			String setterMethodName = "set" + StringUtils.capitalize(field.getName());

			try {
				Method method = clazz.getDeclaredMethod(getterMethodName, new Class<?>[0]);
				getters.put(field.getName(), method);
			} catch (NoSuchMethodException ignore) {
				throw new ReflectorException("@Parameter " + clazz.getName() + "." + getterMethodName + "() not defined");
			}
			try {
				Method method = clazz.getMethod(setterMethodName, new Class<?>[] { type });
				setters.put(field.getName(), method);
			} catch (NoSuchMethodException ignore) {
				throw new ReflectorException("@Parameter " + clazz.getName() + "." + setterMethodName + "(" + type.getName()
						+ " " + field.getName() + ") not defined");
			}

			if (log.isTraceEnabled())
				log.trace("@Parameter " + clazz.getName() + "." + field.getName() + " loaded");
		}

		/*
		 * Reflecting ACTIONS
		 */
		for (Method method : clazz.getDeclaredMethods()) {
			if (!method.isAnnotationPresent(Action.class))
				continue;

			Action a = method.getAnnotation(Action.class);

			//
			// Action Content Type (type)...
			//
			actionsType.put(method.getName(), a.type());

			// check whether intercepted by @Interceptor classes
			List<Class<?>> interceptorsOnly = new LinkedList<Class<?>>();
			Class<?> interceptors[] = a.interceptedBy();
			for (int i = 0; i < interceptors.length; i++) {
				if (interceptors[i] == java.lang.Object.class) {
					// @Action() uses java.lang.Object as default Interceptor. Let's bypass this
					// case
					continue;
				}
				Interceptor interceptor = interceptors[i].getAnnotation(Interceptor.class);
				if (interceptor == null) {
					throw new ReflectorException(
							"@Action " + clazz.getName() + "." + method.getName() + "() intercepted by " + interceptors[i] +
									", which is not an @Interceptor.");
				}
				interceptorsOnly.add(interceptors[i]);
			}
			this.interceptors.put(method.getName(), interceptorsOnly.toArray(new Class<?>[0]));

			if (method.getReturnType() != Result.class || method.getParameterTypes().length != 0) {
				throw new ReflectorException(
						"@Action " + clazz.getName() + "." + method.getName() + "() not returning Result object");
			}
			actions.put(method.getName(), method);
			if (log.isTraceEnabled()) {
				log.trace("@Action " + clazz.getName() + "." + method.getName() + "() loaded");
			}
		}
	}

	public Map<String, Method> getActions() {
		return actions;
	}

	public Map<String, String> getActionsType() {
		return actionsType;
	}

	public Map<String, Class<?>[]> getInterceptors() {
		return interceptors;
	}

	public Class<?> getClazz() {
		return clazz;
	}

	public String getClazzInternalName() {
		return clazzInternalName;
	}

	public String getClazzDescriptor() {
		return clazzDescriptor;
	}

	public Map<String, Class<?>> getConverters() {
		return converters;
	}

	public Map<String, Class<?>> getParameters() {
		return parameters;
	}

	public Map<String, Method> getGetters() {
		return getters;
	}

	public Map<String, Method> getSetters() {
		return setters;
	}

	public SessionManager getSession() {
		return session;
	}

	public void setSession(SessionManager session) {
		this.session = session;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getApplicationContext() {
		return applicationContext;
	}

	public String getCookieContext() {
		return cookieContext;
	}

	public String getErrorContext() {
		return errorContext;
	}

	public String getMessageContext() {
		return messageContext;
	}

	public String getRequestContext() {
		return requestContext;
	}

	public String getSessionContext() {
		return sessionContext;
	}
}
