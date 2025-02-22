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
package io.syncframework.netty;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.syncframework.api.FileResult;
import io.syncframework.api.Result;
import io.syncframework.api.SessionManager;
import io.syncframework.core.Application;
import io.syncframework.core.ApplicationManager;
import io.syncframework.core.ControllerBean;
import io.syncframework.core.ControllerFactory;
import io.syncframework.core.InterceptorBean;
import io.syncframework.core.InterceptorFactory;
import io.syncframework.core.Response;
import io.syncframework.core.Server;
import io.syncframework.core.Session;
import io.syncframework.core.SessionFactory;
import io.syncframework.core.SessionFactoryStatelessImpl;
import io.syncframework.responder.Responder;
import io.syncframework.responder.ResponderFactory;

/**
 * Main request handler class. Please note that this class handles partial
 * requests, so handling both small requests and file upload requests.
 *
 * @author dfroz
 */
public class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
  private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);
  private static final SessionFactory sessionStateless = new SessionFactoryStatelessImpl();
  private static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
  private static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
  private static final int HTTP_CACHE_SECONDS = 60;
  private Application application;

  public RequestHandler(Server server) {
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    try {
      String domain = getDomain(request);

      application = ApplicationManager.getApplication(domain);
      if (application == null) {
        String fileNotFound = "File not found";
        sendResponse(ctx, HttpResponseStatus.NOT_FOUND, fileNotFound);
        return;
      }

      // setting thread ClassLoader
      Thread.currentThread().setContextClassLoader(application.getClassLoader());

      if (HttpMethod.GET.equals(request.method())) {
        handleGetRequest(ctx, request);
      } else if (HttpMethod.POST.equals(request.method())) {
        handlePostRequest(ctx, request);
      } else {
        sendResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Unsupported Method: " + request.method().name());
      }
    } catch (Exception ex) {
      sendException(ctx, ex);
    }
  }

  private void handleGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    if (log.isTraceEnabled()) {
      log.info("handling GET request: " + request.uri());
    }

    RequestAdapter adapter = new RequestAdapter();
    adapter.setRequest(request);
    handleRequest(ctx, request, adapter);
  }

  private void handlePostRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    if (log.isTraceEnabled()) {
      log.trace("handling POST request: " + request.uri());
    }

    RequestAdapter adapter = new RequestAdapter();
    adapter.setRequest(request);

    String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
    StringBuilder responseContent = new StringBuilder("Received POST\n");
    if (contentType != null && contentType.startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString())) {
      QueryStringDecoder decoder = new QueryStringDecoder(request.content().toString(CharsetUtil.UTF_8), false);
      for (Map.Entry<String, java.util.List<String>> entry : decoder.parameters().entrySet()) {
        // responseContent.append(entry.getKey()).append("
        // =").append(entry.getValue()).append("\n");
        adapter.getParameters().put(entry.getKey(), entry.getValue());
      }
    } else if (contentType != null && contentType.startsWith(HttpHeaderValues.MULTIPART_FORM_DATA.toString())) {
      responseContent.append("Multipart form data detected");
    } else {
      responseContent.append("Body: ").append(request.content().toString(CharsetUtil.UTF_8)).append("\n");
    }
    // sendResponse(ctx, HttpResponseStatus.OK, responseContent.toString());
    handleRequest(ctx, request, adapter);
  }

  private void handleRequest(ChannelHandlerContext ctx, HttpRequest request, RequestAdapter adapter) throws Exception {
    final ControllerBean controller = new ControllerBean();
    ControllerFactory controllerFactory = application.getControllerFactory();

    if (!controllerFactory.find(controller, request.uri())) {
      if (log.isTraceEnabled()) {
        log.trace("no @Controller found to handle request: {}", request.uri());
      }
      sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "File not found");
      return;
    }

    SessionFactory sessionFactory = application.getSessionFactory();
    if (sessionFactory == null) {
      log.error("application malfunction detected; SessionFactory is null");
      sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Failed to locate SessionFactory for the request: " + request.uri());
      return;
    }

    ResponderFactory responderFactory = application.getResponderFactory();
    if (responderFactory == null) {
      log.error("application malfunction detected; ResponderFactory is null");
      sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Failed to locate the Responder for the request: " + request.uri());
      return;
    }

    Session session = null;
    if (controller.session() == SessionManager.DEFAULT || controller.session() == SessionManager.SECURE) {
      session = sessionFactory.find(adapter);
    } else {
      session = sessionStateless.find(adapter);
    }
    if (log.isTraceEnabled()) {
      log.trace("utilizing session: {}", session);
    }

    adapter.setSession(session);

    Response response = new Response();
    response.setSession(session);
    response.setApplication(application);

    InterceptorFactory interceptorFactory = application.getInterceptorFactory();
    InterceptorBean interceptors[] = interceptorFactory.find(controller.interceptedBy());
    if (interceptors != null) {
      for (InterceptorBean interceptor : interceptors) {
        //
        // default action is to return null; if not null, direct to response and end the
        // req/resp cycle
        //
        Result interceptorResult = interceptor.before(adapter, response);
        if (interceptorResult != null) {
          //
          // find the responder which will handle the result. populate data using the
          // response object.
          //
          Responder responder = responderFactory.find(interceptorResult);
          if (responder == null) {
            log.error("no responder encountered to handle result: " + interceptorResult);
            sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                "No responder found to handle interceptor result: " + interceptorResult);
            return;
          }
          responder.respond(response, interceptor, interceptorResult);
          if (log.isTraceEnabled()) {
            log.trace(interceptor + ".before() returned result: " + interceptorResult);
          }
          if (interceptorResult instanceof FileResult) {
            sendFile(ctx, request, response);
            return;
          } else {
            sendResponse(ctx, response);
            return;
          }
        }
      }
    }

    //
    // controller.action()
    //
    Result result = controller.action(adapter, response);
    if (result == null) {
      log.error("@Controller " + controller + " has no @Action defined to handle request: "
          + adapter.getUri());
      // not found controller's action
      sendResponse(ctx, HttpResponseStatus.NOT_FOUND,
          "@Controller " + controller + "has no @Action defined to handle request " + adapter.getUri());
      return;
    }
    if (log.isTraceEnabled()) {
      log.trace(controller + " returned result: " + result);
    }

    // interceptors after()
    if (interceptors != null) {
      for (InterceptorBean interceptor : interceptors) {
        //
        // default action is to return null; if not null, direct to response and end the
        // req/resp cycle
        //
        Result interceptorResult = interceptor.after(adapter, response);
        if (interceptorResult != null) {
          //
          // find the responder which will handle the result. populate data using the
          // response object.
          //
          Responder responder = responderFactory.find(interceptorResult);
          if (responder == null) {
            log.error("no responder encountered to handle result: " + interceptorResult);
            sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                "No responder found to handle Interceptor's result: " + interceptorResult);
            return;
          }
          if (log.isTraceEnabled()) {
            log.trace(interceptor + ".after() returned result: " + interceptorResult);
          }
          responder.respond(response, interceptor, interceptorResult);
          if (interceptorResult instanceof FileResult) {
            sendFile(ctx, request, response);
            return;
          } else {
            sendResponse(ctx, response);
            return;
          }
        }
      }
    }

    //
    // find the responder which will handle the result. populate data using the
    // response object.
    //
    Responder responder = responderFactory.find(result);
    if (responder == null) {
      log.error("no responder encountered to handle result: " + result);
      sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "No responder to handle result: " + result);
      return;
    }
    responder.respond(response, controller, result);

    if (log.isTraceEnabled()) {
      log.trace("{}: {} delivered response: {}", application, responder, result);
    }

    if (result instanceof FileResult) {
      sendFile(ctx, request, response);
      return;
    } else {
      sendResponse(ctx, response);
      return;
    }
  }

  private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content) {
    ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private void sendResponse(ChannelHandlerContext ctx, Response response) throws Exception {
    HttpResponseStatus responseStatus = HttpResponseStatus.valueOf(response.getStatus());
    ByteArrayOutputStream bos = (ByteArrayOutputStream) response.getOutputStream();
    ByteBuf buf = Unpooled.copiedBuffer(bos.toByteArray());

    // Build the response object.
    FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, responseStatus, buf);
    // default content-type header... likely to be overwritten by the Result
    // Content-Type header...
    httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
    httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());

    //
    // if response has declared specific Headers, then this may or may not override
    // the default headers
    // declared above.
    //
    if (response.getHeaders() != null) {
      if (log.isTraceEnabled()) {
        log.trace("custom response headers identified... passing to the response");
      }
      for (String header : response.getHeaders().keySet()) {
        if (log.isTraceEnabled()) {
          log.trace("setting response header: {}: {}", header, response.getHeaders().get(header));
        }
        httpResponse.headers().set(header, response.getHeaders().get(header));
      }
    }

    // Write the response.
    ctx.channel().writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
  }

  private void sendException(ChannelHandlerContext ctx, Exception ex) {
    String msg = "Internal server error " + ex.getMessage();
    try {
      sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, msg);
    } catch (Exception err) {
      log.error("oops exception caught: {}", err.getMessage());
    }
  }

  private void sendFile(ChannelHandlerContext ctx, HttpRequest request, Response response) throws Exception {
    Application application = response.getApplication();
    if (application == null) {
      log.error("no response.application has been set");
      sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "No response.application has been set");
      return;
    }

    File file = response.getFile();
    if (file == null) {
      log.error("no response.file has been set");
      sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "No response.file set");
      return;
    }
    if (!file.exists() || file.isHidden() || !file.isFile()) {
      // file not found try request dynamically
      log.error("file not found");
      sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "File returned from handler does not exist; file: " + file.getAbsolutePath());
      return;
    }

    // Cache
    String ifModifiedSince = request.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
    if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
      SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
      Date ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince);
      long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
      long fileLastModifiedSeconds = file.lastModified() / 1000;
      if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
        sendNotModified(ctx);
        return;
      }
    }

    //
    // Check if the file resides under the PUBLIC or PRIVATE folders.
    // More important point for this verification is with PUBLIC requests where
    // multiples ../../../..
    // may lead to security breach - exposing unwanted system files.
    //
    String path = file.getAbsolutePath();
    if (!path.startsWith(application.getConfig().getPublicDirectory().getAbsolutePath())
        && !path.startsWith(application.getConfig().getPrivateDirectory().getAbsolutePath())) {
      log.error("{}: file {} returned, is not located under Public or Private folders",
          application, file.getAbsolutePath());
      sendResponse(ctx, HttpResponseStatus.FORBIDDEN, "Forbidden");
    }

    @SuppressWarnings("resource")
    RandomAccessFile raf = new RandomAccessFile(file, "r");
    long fileLength = raf.length();

    if (log.isTraceEnabled()) {
      log.trace("{}: returning file: {}", application, file);
    }

    HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    HttpUtil.setContentLength(httpResponse, fileLength);
    httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, MimeUtils.getContentType(file));
    setDateAndCacheHeaders(httpResponse, file);
    //
    // if response has declared specific Headers, then this may or may not override
    // the default headers
    // declared above.
    //
    if (response.getHeaders() != null && response.getHeaders().isEmpty()) {
      if (log.isTraceEnabled()) {
        log.trace("custom response headers identified... passing to the response");
      }
      for (String header : response.getHeaders().keySet()) {
        if (log.isTraceEnabled()) {
          log.trace("setting response header: {}: {}", header, response.getHeaders().get(header));
        }
        httpResponse.headers().set(header, response.getHeaders().get(header));
      }
    }
    // connection is always CLOSE...
    httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

    // Write the initial line and the header.
    ctx.write(httpResponse);

    // Write the content.
    ChannelFuture sendFileFuture;
    ChannelFuture lastContentFuture;

    sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength), ctx.newProgressivePromise());
    lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    // Close the connection when the whole content is written out.
    lastContentFuture.addListener(ChannelFutureListener.CLOSE);

    sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
      @Override
      public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
        if (total < 0) { // total unknown
          if (log.isTraceEnabled()) {
            log.trace("{}: " + future.channel() + " transfer progress: " + progress, application);
          }
        } else {
          if (log.isTraceEnabled()) {
            log.trace("{}: " + future.channel() + " transfer progress: " + progress + " / " + total,
                application);
          }
        }
      }

      @Override
      public void operationComplete(ChannelProgressiveFuture future) {
        if (log.isTraceEnabled()) {
          log.trace("{}: " + future.channel() + " transfer complete.", application);
        }
      }
    });

    return;
  }

  private void sendNotModified(ChannelHandlerContext ctx) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

    SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
    dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
    Calendar time = new GregorianCalendar();
    response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));

    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private static void setDateAndCacheHeaders(HttpResponse response, File fileToCache) {
    SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
    dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
    Calendar time = new GregorianCalendar();
    time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
    response.headers().set(HttpHeaderNames.EXPIRES, dateFormatter.format(time.getTime()));
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
    response.headers().set(HttpHeaderNames.LAST_MODIFIED,
        dateFormatter.format(new Date(fileToCache.lastModified())));
  }

  private static String getDomain(HttpRequest request) {
    String domain = request.headers().getAsString(HttpHeaderNames.HOST);
    int p = domain.indexOf(':');
    if (p != -1) {
      domain = domain.substring(0, p);
    }
    return domain;
  }
}
