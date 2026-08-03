package com.emporia.staticdata;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AlpacaReferenceDataImporterTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void rejectsMissingApiCredentials() {
        assertThatThrownBy(() -> new AlpacaReferenceDataImporter(jdbc, RestClient.builder(), "http://localhost", "", "secret", "schema", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Alpaca reference-data import requires APCA_API_KEY_ID");

        assertThatThrownBy(() -> new AlpacaReferenceDataImporter(jdbc, RestClient.builder(), "http://localhost", "key", null, "schema", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Alpaca reference-data import requires APCA_API_KEY_ID");
    }

    @Test
    void rejectsInvalidSchemaIdentifier() {
        assertThatThrownBy(() -> new AlpacaReferenceDataImporter(jdbc, RestClient.builder(), "http://localhost", "key", "secret", "invalid schema!", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Database schema must be a simple SQL identifier");
    }

    @Test
    void exchangeMappingHelper() {
        AlpacaReferenceDataImporter.Exchange nasdaq = AlpacaReferenceDataImporter.exchange("NASDAQ");
        assertThat(nasdaq.mic()).isEqualTo("XNAS");
        assertThat(nasdaq.name()).isEqualTo("Nasdaq");

        AlpacaReferenceDataImporter.Exchange nyse = AlpacaReferenceDataImporter.exchange("NYSE");
        assertThat(nyse.mic()).isEqualTo("XNYS");

        AlpacaReferenceDataImporter.Exchange arca = AlpacaReferenceDataImporter.exchange("ARCA");
        assertThat(arca.mic()).isEqualTo("ARCX");

        AlpacaReferenceDataImporter.Exchange amex = AlpacaReferenceDataImporter.exchange("AMEX");
        assertThat(amex.mic()).isEqualTo("XASE");

        AlpacaReferenceDataImporter.Exchange bats = AlpacaReferenceDataImporter.exchange("BATS");
        assertThat(bats.mic()).isEqualTo("BATS");

        AlpacaReferenceDataImporter.Exchange unknown = AlpacaReferenceDataImporter.exchange("OTHER");
        assertThat(unknown.mic()).isEqualTo("XNAS");
        assertThat(unknown.name()).isEqualTo("OTHER");

        AlpacaReferenceDataImporter.Exchange nullExchange = AlpacaReferenceDataImporter.exchange(null);
        assertThat(nullExchange.mic()).isEqualTo("XNAS");
        assertThat(nullExchange.name()).isEqualTo("US Equities");

        AlpacaReferenceDataImporter.Exchange blankExchange = AlpacaReferenceDataImporter.exchange("   ");
        assertThat(blankExchange.mic()).isEqualTo("XNAS");
        assertThat(blankExchange.name()).isEqualTo("US Equities");
    }

    @Test
    void stableIdGenerator() {
        long id1 = AlpacaReferenceDataImporter.stableId("asset-1", "XNAS");
        long id2 = AlpacaReferenceDataImporter.stableId("asset-1", "XNAS");
        long id3 = AlpacaReferenceDataImporter.stableId("asset-1", "XOSR");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1).isGreaterThanOrEqualTo(2000L);
    }

    @Test
    void assetImportableCheck() {
        AlpacaReferenceDataImporter.Asset active = new AlpacaReferenceDataImporter.Asset("id1", "AAPL", "Apple", "NASDAQ", "active", true, true);
        assertThat(active.importable()).isTrue();

        AlpacaReferenceDataImporter.Asset inactive = new AlpacaReferenceDataImporter.Asset("id1", "AAPL", "Apple", "NASDAQ", "inactive", true, true);
        assertThat(inactive.importable()).isFalse();

        AlpacaReferenceDataImporter.Asset nonTradable = new AlpacaReferenceDataImporter.Asset("id1", "AAPL", "Apple", "NASDAQ", "active", false, true);
        assertThat(nonTradable.importable()).isFalse();

        assertThat(new AlpacaReferenceDataImporter.Asset(null, "AAPL", "Apple", "NASDAQ", "active", true, true).importable()).isFalse();
        assertThat(new AlpacaReferenceDataImporter.Asset("id1", null, "Apple", "NASDAQ", "active", true, true).importable()).isFalse();
        assertThat(new AlpacaReferenceDataImporter.Asset("", "AAPL", "Apple", "NASDAQ", "active", true, true).importable()).isFalse();
        assertThat(new AlpacaReferenceDataImporter.Asset("id1", "", "Apple", "NASDAQ", "active", true, true).importable()).isFalse();
    }

    @Test
    void runImportsActiveAssets() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlpacaReferenceDataImporter importer = new AlpacaReferenceDataImporter(jdbc, builder.build(), "emporia_static_data", BigDecimal.TEN);

        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        AlpacaReferenceDataImporter.Asset[] assets = new AlpacaReferenceDataImporter.Asset[] {
                new AlpacaReferenceDataImporter.Asset("1", "AAPL", "Apple Inc", "NASDAQ", "active", true, true),
                new AlpacaReferenceDataImporter.Asset("2", "IBM", "IBM Corp", "NYSE", "active", true, false)
        };

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://localhost/assets?status=active&asset_class=us_equity"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(mapper.writeValueAsString(assets), org.springframework.http.MediaType.APPLICATION_JSON));

        importer.run(new org.springframework.boot.DefaultApplicationArguments());
        server.verify();
    }
}
