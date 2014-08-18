/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign;

import static feign.FeignException.errorExecuting;
import static feign.FeignException.errorReading;
import static feign.Util.checkNotNull;
import static feign.Util.ensureClosed;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Provider;

final class SynchronousMethodHandler implements MethodHandler {

  static class Factory {

    private final Client client;
    private final Provider<Retryer> retryer;
    private final Set<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Provider<Logger.Level> logLevel;

    @Inject
    Factory(
        Client client,
        Provider<Retryer> retryer,
        Set<RequestInterceptor> requestInterceptors,
        Logger logger,
        Provider<Logger.Level> logLevel) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
    }

    public MethodHandler create(
        Target<?> target,
        MethodMetadata md,
        RequestTemplate.Factory buildTemplateFromArgs,
        Options options,
        Decoder decoder,
        ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(
          target,
          client,
          retryer,
          requestInterceptors,
          logger,
          logLevel,
          md,
          buildTemplateFromArgs,
          options,
          decoder,
          errorDecoder);
    }
  }

  private final MethodMetadata metadata;
  private final Target<?> target;
  private final Client client;
  private final Provider<Retryer> retryer;
  private final Set<RequestInterceptor> requestInterceptors;
  private final Logger logger;
  private final Provider<Logger.Level> logLevel;
  private final RequestTemplate.Factory buildTemplateFromArgs;
  private final Options options;
  private final Decoder decoder;
  private final ErrorDecoder errorDecoder;

  private SynchronousMethodHandler(
      Target<?> target,
      Client client,
      Provider<Retryer> retryer,
      Set<RequestInterceptor> requestInterceptors,
      Logger logger,
      Provider<Logger.Level> logLevel,
      MethodMetadata metadata,
      RequestTemplate.Factory buildTemplateFromArgs,
      Options options,
      Decoder decoder,
      ErrorDecoder errorDecoder) {
    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.requestInterceptors =
        checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
    this.logger = checkNotNull(logger, "logger for %s", target);
    this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.errorDecoder = checkNotNull(errorDecoder, "errorDecoder for %s", target);
    this.decoder = checkNotNull(decoder, "decoder for %s", target);
  }

  @Override
  public Object invoke(Object[] argv) throws Throwable {
    RequestTemplate template = buildTemplateFromArgs.create(argv);
    Retryer retryer = this.retryer.get();
    while (true) {
      try {
        return executeAndDecode(template);
      } catch (RetryableException e) {
        retryer.continueOrPropagate(e);
        if (logLevel.get() != Logger.Level.NONE) {
          logger.logRetry(metadata.configKey(), logLevel.get());
        }
        continue;
      }
    }
  }

  Object executeAndDecode(RequestTemplate template) throws Throwable {
    Request request = targetRequest(template);

    if (logLevel.get() != Logger.Level.NONE) {
      logger.logRequest(metadata.configKey(), logLevel.get(), request);
    }

    Response response;
    long start = System.nanoTime();
    try {
      response = client.execute(request, options);
    } catch (IOException e) {
      if (logLevel.get() != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel.get(), e, elapsedTime(start));
      }
      throw errorExecuting(request, e);
    }
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    try {
      if (logLevel.get() != Logger.Level.NONE) {
        response =
            logger.logAndRebufferResponse(
                metadata.configKey(), logLevel.get(), response, elapsedTime);
      }
      if (response.status() >= 200 && response.status() < 300) {
        if (Response.class == metadata.returnType()) {
          if (response.body() == null) {
            return response;
          }
          // Ensure the response body is disconnected
          byte[] bodyData = Util.toByteArray(response.body().asInputStream());
          return Response.create(
              response.status(), response.reason(), response.headers(), bodyData);
        } else if (void.class == metadata.returnType()) {
          return null;
        } else {
          return decode(response);
        }
      } else {
        throw errorDecoder.decode(metadata.configKey(), response);
      }
    } catch (IOException e) {
      if (logLevel.get() != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel.get(), e, elapsedTime);
      }
      throw errorReading(request, response, e);
    } finally {
      ensureClosed(response.body());
    }
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  Request targetRequest(RequestTemplate template) {
    for (RequestInterceptor interceptor : requestInterceptors) {
      interceptor.apply(template);
    }
    return target.apply(new RequestTemplate(template));
  }

  Object decode(Response response) throws Throwable {
    try {
      return decoder.decode(response, metadata.returnType());
    } catch (FeignException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DecodeException(e.getMessage(), e);
    }
  }
}