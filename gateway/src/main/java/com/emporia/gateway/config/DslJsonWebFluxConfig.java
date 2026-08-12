package com.emporia.gateway.config;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.Decoder;
import org.springframework.core.codec.Encoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring WebFlux High-Performance Codec Configuration using DSL-JSON.
 * Replaces default Jackson ObjectMapper with zero-reflection byte-level parsing for sub-millisecond JSON SerDe.
 */
@Configuration
public class DslJsonWebFluxConfig implements WebFluxConfigurer {

    private static final DslJson<Object> dslJson = new DslJson<>(Settings.withRuntime().includeServiceLoader());

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.customCodecs().registerWithDefaultConfig(new DecoderHttpMessageReader<>(new DslJsonDecoder()));
        configurer.customCodecs().registerWithDefaultConfig(new EncoderHttpMessageWriter<>(new DslJsonEncoder()));
    }

    public static class DslJsonDecoder implements Decoder<Object> {
        private static final List<MimeType> MIME_TYPES = Collections.singletonList(MediaType.APPLICATION_JSON);

        @Override
        public boolean canDecode(ResolvableType elementType, MimeType mimeType) {
            return mimeType != null && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.asMediaType(mimeType));
        }

        @Override
        public Flux<Object> decode(org.reactivestreams.Publisher<DataBuffer> inputStream, ResolvableType elementType, MimeType mimeType, Map<String, Object> hints) {
            return decodeToMono(inputStream, elementType, mimeType, hints).flux();
        }

        @Override
        public Mono<Object> decodeToMono(org.reactivestreams.Publisher<DataBuffer> inputStream, ResolvableType elementType, MimeType mimeType, Map<String, Object> hints) {
            return DataBufferUtils.join(inputStream)
                    .flatMap(dataBuffer -> {
                        try {
                            int count = dataBuffer.readableByteCount();
                            if (count == 0) {
                                return Mono.empty();
                            }
                            byte[] bytes = new byte[count];
                            dataBuffer.read(bytes);
                            Class<?> clazz = elementType.toClass();
                            Object deserialized = dslJson.deserialize(clazz, bytes, bytes.length);
                            return deserialized != null ? Mono.just(deserialized) : Mono.empty();
                        } catch (IOException e) {
                            return Mono.error(new IllegalArgumentException("DSL-JSON deserialization failed for " + elementType, e));
                        } finally {
                            DataBufferUtils.release(dataBuffer);
                        }
                    });
        }

        @Override
        public List<MimeType> getDecodableMimeTypes() {
            return MIME_TYPES;
        }
    }

    public static class DslJsonEncoder implements Encoder<Object> {
        private static final List<MimeType> MIME_TYPES = Collections.singletonList(MediaType.APPLICATION_JSON);

        @Override
        public boolean canEncode(ResolvableType elementType, MimeType mimeType) {
            return mimeType != null && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.asMediaType(mimeType));
        }

        @Override
        public Flux<DataBuffer> encode(org.reactivestreams.Publisher<?> inputStream, org.springframework.core.io.buffer.DataBufferFactory bufferFactory, ResolvableType elementType, MimeType mimeType, Map<String, Object> hints) {
            return Flux.from(inputStream).map(value -> encodeValue(value, bufferFactory));
        }

        @Override
        public DataBuffer encodeValue(Object value, org.springframework.core.io.buffer.DataBufferFactory bufferFactory, ResolvableType valueType, MimeType mimeType, Map<String, Object> hints) {
            return encodeValue(value, bufferFactory);
        }

        private DataBuffer encodeValue(Object value, org.springframework.core.io.buffer.DataBufferFactory bufferFactory) {
            try {
                ByteArrayOutputStream os = new ByteArrayOutputStream(512);
                dslJson.serialize(value, os);
                byte[] bytes = os.toByteArray();
                DataBuffer buffer = bufferFactory.allocateBuffer(bytes.length);
                buffer.write(bytes);
                return buffer;
            } catch (IOException e) {
                throw new IllegalArgumentException("DSL-JSON serialization failed", e);
            }
        }

        @Override
        public List<MimeType> getEncodableMimeTypes() {
            return MIME_TYPES;
        }
    }
}
