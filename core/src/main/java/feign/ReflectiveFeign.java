/**
 * Copyright 2012-2019 The Feign Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.template.UriUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

public class ReflectiveFeign extends Feign {

    private final ParseHandlersByName targetToHandlersByName;
    private final InvocationHandlerFactory factory;
    private final QueryMapEncoder queryMapEncoder;

    ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
                    QueryMapEncoder queryMapEncoder) {
        this.targetToHandlersByName = targetToHandlersByName;
        this.factory = factory;
        this.queryMapEncoder = queryMapEncoder;
    }

    /**
     * 创建实例
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T newInstance(Target<T> target) {
        // 将目标类型的函数构建成以 函数名字为key、MethodHandler 为value的 map
        Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
        Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
        List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();
        // 遍历目标类型的函数并将其转成以 Method 为key，MethodHandler 为value的 map
        for (Method method : target.type().getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            } else if (Util.isDefault(method)) {
                DefaultMethodHandler handler = new DefaultMethodHandler(method);
                defaultMethodHandlers.add(handler);
                methodToHandler.put(method, handler);
            } else {
                methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
            }
        }
        /**
         * 创建目标类型的代理实例
         * @see InvocationHandlerFactory.Default#create(Target, Map)
         */
        InvocationHandler handler = factory.create(target, methodToHandler);
        T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(), new Class<?>[]{target.type()}, handler);
        // 绑定缺省函数到代理实例
        for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
            defaultMethodHandler.bindTo(proxy);
        }
        return proxy;
    }

    // 目标类型的 InvocationHandler
    static class FeignInvocationHandler implements InvocationHandler {

        private final Target target;
        private final Map<Method, MethodHandler> dispatch;

        FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
            this.target = checkNotNull(target, "target");
            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
        }

        // 分发并调用相应的函数
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("equals".equals(method.getName())) {
                try {
                    Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
                    return equals(otherHandler);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else if ("hashCode".equals(method.getName())) {
                return hashCode();
            } else if ("toString".equals(method.getName())) {
                return toString();
            }
            /**
             * 分发并调用相应的函数
             * @see SynchronousMethodHandler#invoke(Object[])
             */
            return dispatch.get(method).invoke(args);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof FeignInvocationHandler) {
                FeignInvocationHandler other = (FeignInvocationHandler) obj;
                return target.equals(other.target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return target.hashCode();
        }

        @Override
        public String toString() {
            return target.toString();
        }
    }

    static final class ParseHandlersByName {

        private final Contract contract;
        private final Options options;
        private final Encoder encoder;
        private final Decoder decoder;
        private final ErrorDecoder errorDecoder;
        private final QueryMapEncoder queryMapEncoder;
        private final SynchronousMethodHandler.Factory factory;

        ParseHandlersByName(
                Contract contract,
                Options options,
                Encoder encoder,
                Decoder decoder,
                QueryMapEncoder queryMapEncoder,
                ErrorDecoder errorDecoder,
                SynchronousMethodHandler.Factory factory) {
            this.contract = contract;
            this.options = options;
            this.factory = factory;
            this.errorDecoder = errorDecoder;
            this.queryMapEncoder = queryMapEncoder;
            this.encoder = checkNotNull(encoder, "encoder");
            this.decoder = checkNotNull(decoder, "decoder");
        }

        // 解析目标类型的函数并构建成以 函数名字为key、MethodHandler 为value的 map
        public Map<String, MethodHandler> apply(Target key) {
            // 将目标类型的函数转成 MethodMetadata
            List<MethodMetadata> metadata = contract.parseAndValidatateMetadata(key.type());
            Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
            // 遍历 MethodMetadata 并将其转成 map
            for (MethodMetadata md : metadata) {
                BuildTemplateByResolvingArgs buildTemplate;
                if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
                    buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder);
                } else if (md.bodyIndex() != null) {
                    buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder);
                } else {
                    buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder);
                }
                /**
                 * @see SynchronousMethodHandler.Factory#create(Target, MethodMetadata, RequestTemplate.Factory, Options, Decoder, ErrorDecoder)
                 */
                result.put(md.configKey(), factory.create(key, md, buildTemplate, options, decoder, errorDecoder));
            }
            return result;
        }
    }

    private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

        private final QueryMapEncoder queryMapEncoder;

        protected final MethodMetadata metadata;
        private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

        private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder) {
            this.metadata = metadata;
            this.queryMapEncoder = queryMapEncoder;
            if (metadata.indexToExpander() != null) {
                indexToExpander.putAll(metadata.indexToExpander());
                return;
            }
            if (metadata.indexToExpanderClass().isEmpty()) {
                return;
            }
            for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata
                    .indexToExpanderClass().entrySet()) {
                try {
                    indexToExpander.put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
                } catch (InstantiationException e) {
                    throw new IllegalStateException(e);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public RequestTemplate create(Object[] argv) {
            // 复制 RequestTemplate
            RequestTemplate mutable = RequestTemplate.from(metadata.template());
            // -----------忽略开始-----------
            if (metadata.urlIndex() != null) {
                int urlIndex = metadata.urlIndex();
                checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
                mutable.target(String.valueOf(argv[urlIndex]));
            }
            Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
            for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
                int i = entry.getKey();
                Object value = argv[entry.getKey()];
                if (value != null) { // Null values are skipped.
                    if (indexToExpander.containsKey(i)) {
                        value = expandElements(indexToExpander.get(i), value);
                    }
                    for (String name : entry.getValue()) {
                        varBuilder.put(name, value);
                    }
                }
            }
            // -----------忽略结束-----------
            /**
             * 展开 RequestTemplate，构建请求地址、header、请求方法、body
             * @see BuildEncodedTemplateFromArgs#resolve(Object[], RequestTemplate, Map)
             * @see BuildFormEncodedTemplateFromArgs#resolve(Object[], RequestTemplate, Map)
             */
            RequestTemplate template = resolve(argv, mutable, varBuilder);
            // -----------忽略开始-----------
            if (metadata.queryMapIndex() != null) {
                // add query map parameters after initial resolve so that they take
                // precedence over any predefined values
                Object value = argv[metadata.queryMapIndex()];
                Map<String, Object> queryMap = toQueryMap(value);
                template = addQueryMapQueryParameters(queryMap, template);
            }
            // -----------忽略结束-----------
            // 添加 header 参数
            if (metadata.headerMapIndex() != null) {
                template = addHeaderMapHeaders((Map<String, Object>) argv[metadata.headerMapIndex()], template);
            }
            return template;
        }

        private Map<String, Object> toQueryMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            try {
                return queryMapEncoder.encode(value);
            } catch (EncodeException e) {
                throw new IllegalStateException(e);
            }
        }

        private Object expandElements(Expander expander, Object value) {
            if (value instanceof Iterable) {
                return expandIterable(expander, (Iterable) value);
            }
            return expander.expand(value);
        }

        private List<String> expandIterable(Expander expander, Iterable value) {
            List<String> values = new ArrayList<String>();
            for (Object element : value) {
                if (element != null) {
                    values.add(expander.expand(element));
                }
            }
            return values;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                    RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : headerMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                Object currValue = currEntry.getValue();
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null ? null : nextObject.toString());
                    }
                } else {
                    values.add(currValue == null ? null : currValue.toString());
                }

                mutable.header(currEntry.getKey(), values);
            }
            return mutable;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                           RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : queryMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                boolean encoded = metadata.queryMapEncoded();
                Object currValue = currEntry.getValue();
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null ? null
                                : encoded ? nextObject.toString()
                                : UriUtils.encode(nextObject.toString()));
                    }
                } else {
                    values.add(currValue == null ? null
                            : encoded ? currValue.toString() : UriUtils.encode(currValue.toString()));
                }

                mutable.query(encoded ? currEntry.getKey() : UriUtils.encode(currEntry.getKey()), values);
            }
            return mutable;
        }

        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            // 不细究这块代码
            // 对于只有一个 body 的参数来说，这里并没有作什么事
            return mutable.resolve(variables);
        }
    }

    private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                                 QueryMapEncoder queryMapEncoder) {
            super(metadata, queryMapEncoder);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
            for (Entry<String, Object> entry : variables.entrySet()) {
                if (metadata.formParams().contains(entry.getKey())) {
                    formVariables.put(entry.getKey(), entry.getValue());
                }
            }
            try {
                encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }

    private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                             QueryMapEncoder queryMapEncoder) {
            super(metadata, queryMapEncoder);
            this.encoder = encoder;
        }

        // 展开 RequestTemplate
        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Object body = argv[metadata.bodyIndex()];
            checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
            try {
                /**
                 * 序列化 body
                 * @see org.springframework.cloud.openfeign.support.SpringEncoder#encode
                 */
                encoder.encode(body, metadata.bodyType(), mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            // 继续展开 RequestTemplate
            return super.resolve(argv, mutable, variables);
        }
    }
}
