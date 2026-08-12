package com.emporia.events.dto;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class FastOrderRequestDtoTest {

    private final DslJson<Object> dslJson = new DslJson<>(Settings.withRuntime().includeServiceLoader());

    @Test
    void testDslJsonSerializationAndDeserialization() throws IOException {
        FastOrderRequestDto original = new FastOrderRequestDto(
                "ORD-998877",
                1001L,
                "AAPL",
                "BUY",
                150250000L,
                100000000L
        );

        // Serialize DTO -> byte[]
        ByteArrayOutputStream os = new ByteArrayOutputStream(256);
        dslJson.serialize(original, os);
        byte[] jsonBytes = os.toByteArray();

        String jsonString = new String(jsonBytes);
        System.out.println("⚡ DSL-JSON Generated Output: " + jsonString);

        // Verify valid JSON format
        assertThat(jsonString).contains("\"orderId\":\"ORD-998877\"");
        assertThat(jsonString).contains("\"clientId\":1001");
        assertThat(jsonString).contains("\"priceScaled\":150250000");

        // Deserialize byte[] -> DTO
        FastOrderRequestDto restored = dslJson.deserialize(FastOrderRequestDto.class, jsonBytes, jsonBytes.length);
        assertThat(restored).isEqualTo(original);
    }
}
