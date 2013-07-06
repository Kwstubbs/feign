/*
 * Copyright 2013 Netflix, Inc.
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

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;
import static feign.Util.checkState;
import static feign.Util.resolveLastTypeParameter;
import static java.lang.String.format;

import dagger.Provides;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.codec.StringDecoder;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.inject.Inject;

@SuppressWarnings("rawtypes")
public class ReflectiveFeign extends Feign {

  private final ParseHandlersByName targetToHandlersByName;

  @Inject
  ReflectiveFeign(ParseHandlersByName targetToHandlersByName) {
    this.targetToHandlersByName = targetToHandlersByName;
  }

  /**
   * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
   * to cache the result.
   */
  @SuppressWarnings("unchecked")
  @Override
  public <T> T newInstance(Target<T> target) {
    Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
    Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
    for (Method method : target.type().getDeclaredMethods()) {
      if (method.getDeclaringClass() == Object.class) continue;
      methodToHandler.put(method, nameToHandler.get(Feign.configKey(method)));
    }
    FeignInvocationHandler handler = new FeignInvocationHandler(target, methodToHandler);
    return (T)
        Proxy.newProxyInstance(
            target.type().getClassLoader(), new Class<?>[] {target.type()}, handler);
  }

  static class FeignInvocationHandler implements InvocationHandler {

    private final Target target;
    private final Map<Method, MethodHandler> methodToHandler;

    FeignInvocationHandler(Target target, Map<Method, MethodHandler> methodToHandler) {
      this.target = checkNotNull(target, "target");
      this.methodToHandler = checkNotNull(methodToHandler, "methodToHandler for %s", target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return methodToHandler.get(method).invoke(args);
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (FeignInvocationHandler.class != obj.getClass()) return false;
      FeignInvocationHandler that = FeignInvocationHandler.class.cast(obj);
      return this.target.equals(that.target);
    }

    @Override
    public String toString() {
      return "target(" + target + ")";
    }
  }

  @dagger.Module(complete = false, injects = Feign.class, library = true)
  public static class Module {

    @Provides
    Feign provideFeign(ReflectiveFeign in) {
      return in;
    }
  }

  private static IllegalStateException noConfig(String configKey, Class<?> type) {
    return new IllegalStateException(
        format("no configuration for %s present for %s!", configKey, type.getSimpleName()));
  }

  static final class ParseHandlersByName {
    private final Contract contract;
    private final Options options;
    private final Map<Type, Encoder.Text<? super Object>> encoders =
        new HashMap<Type, Encoder.Text<? super Object>>();
    private final Encoder.Text<Map<String, ?>> formEncoder;
    private final Map<Type, Decoder.TextStream<?>> decoders =
        new HashMap<Type, Decoder.TextStream<?>>();
    private final ErrorDecoder errorDecoder;
    private final MethodHandler.Factory factory;

    @SuppressWarnings("unchecked")
    @Inject
    ParseHandlersByName(
        Contract contract,
        Options options,
        Set<Encoder> encoders,
        Set<Decoder> decoders,
        ErrorDecoder errorDecoder,
        MethodHandler.Factory factory) {
      this.contract = contract;
      this.options = options;
      this.factory = factory;
      this.errorDecoder = errorDecoder;
      for (Encoder encoder : encoders) {
        checkState(
            encoder instanceof Encoder.Text,
            "Currently, only Encoder.Text is supported.  Found: ",
            encoder);
        Type type = resolveLastTypeParameter(encoder.getClass(), Encoder.class);
        this.encoders.put(type, Encoder.Text.class.cast(encoder));
      }
      try {
        Type formEncoderType = getClass().getDeclaredField("formEncoder").getGenericType();
        Type formType = resolveLastTypeParameter(formEncoderType, Encoder.class);
        Encoder.Text<? super Object> formEncoder = this.encoders.get(formType);
        if (formEncoder == null) {
          formEncoder = this.encoders.get(Object.class);
        }
        this.formEncoder = (Encoder.Text) formEncoder;
      } catch (NoSuchFieldException e) {
        throw new AssertionError(e);
      }
      StringDecoder stringDecoder = new StringDecoder();
      this.decoders.put(void.class, stringDecoder);
      this.decoders.put(Response.class, stringDecoder);
      this.decoders.put(String.class, stringDecoder);
      for (Decoder decoder : decoders) {
        checkState(
            decoder instanceof Decoder.TextStream,
            "Currently, only Decoder.TextStream is supported.  Found: ",
            decoder);
        Type type = resolveLastTypeParameter(decoder.getClass(), Decoder.class);
        this.decoders.put(type, Decoder.TextStream.class.cast(decoder));
      }
    }

    public Map<String, MethodHandler> apply(Target key) {
      List<MethodMetadata> metadata = contract.parseAndValidatateMetadata(key.type());
      Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
      for (MethodMetadata md : metadata) {
        Decoder.TextStream decoder = decoders.get(md.decodeInto());
        if (decoder == null) {
          decoder = decoders.get(Object.class);
        }
        if (decoder == null) {
          throw new IllegalStateException(
              format(
                  "%s needs @Provides(type = Set) Decoder decoder()"
                      + "{ // Decoder.TextStream<%s> or Decoder.TextStream<Object>}",
                  md.configKey(), md.decodeInto()));
        }
        BuildTemplateByResolvingArgs buildTemplate;
        if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
          if (formEncoder == null) {
            throw new IllegalStateException(
                format(
                    "%s needs @Provides(type = Set) Encoder encoder()"
                        + "{ // Encoder.Text<Map<String, ?>> or Encoder.Text<Object>}",
                    md.configKey()));
          }
          buildTemplate = new BuildFormEncodedTemplateFromArgs(md, formEncoder);
        } else if (md.bodyIndex() != null) {
          Encoder.Text<? super Object> encoder = encoders.get(md.bodyType());
          if (encoder == null) {
            encoder = encoders.get(Object.class);
          }
          if (encoder == null) {
            throw new IllegalStateException(
                format(
                    "%s needs @Provides(type = Set) Encoder encoder()"
                        + "{ // Encoder.Text<%s> or Encoder.Text<Object>}",
                    md.bodyType(), md.decodeInto()));
          }
          buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder);
        } else {
          buildTemplate = new BuildTemplateByResolvingArgs(md);
        }
        result.put(
            md.configKey(), factory.create(key, md, buildTemplate, options, decoder, errorDecoder));
      }
      return result;
    }
  }

  private static class BuildTemplateByResolvingArgs implements MethodHandler.BuildTemplateFromArgs {
    protected final MethodMetadata metadata;

    private BuildTemplateByResolvingArgs(MethodMetadata metadata) {
      this.metadata = metadata;
    }

    public RequestTemplate apply(Object[] argv) {
      RequestTemplate mutable = new RequestTemplate(metadata.template());
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        mutable.insert(0, String.valueOf(argv[urlIndex]));
      }
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          for (String name : entry.getValue()) varBuilder.put(name, value);
        }
      }
      return resolve(argv, mutable, varBuilder);
    }

    protected RequestTemplate resolve(
        Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {
    private final Encoder.Text<Map<String, ?>> formEncoder;

    private BuildFormEncodedTemplateFromArgs(
        MethodMetadata metadata, Encoder.Text<Map<String, ?>> formEncoder) {
      super(metadata);
      this.formEncoder = formEncoder;
    }

    @Override
    protected RequestTemplate resolve(
        Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Entry<String, Object> entry : variables.entrySet()) {
        if (metadata.formParams().contains(entry.getKey()))
          formVariables.put(entry.getKey(), entry.getValue());
      }
      try {
        mutable.body(formEncoder.encode(formVariables));
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }

  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {
    private final Encoder.Text<? super Object> encoder;

    private BuildEncodedTemplateFromArgs(
        MethodMetadata metadata, Encoder.Text<? super Object> encoder) {
      super(metadata);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(
        Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      Object body = argv[metadata.bodyIndex()];
      checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      try {
        mutable.body(encoder.encode(body));
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }
}
