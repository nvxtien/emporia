package com.emporia.staticdata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlpacaReferenceDataImporterTest {
    @Test
    void mapsAlpacaExchangeNamesToIsoMics() {
        assertThat(AlpacaReferenceDataImporter.exchange("NASDAQ").mic()).isEqualTo("XNAS");
        assertThat(AlpacaReferenceDataImporter.exchange("NYSE").mic()).isEqualTo("XNYS");
        assertThat(AlpacaReferenceDataImporter.exchange("ARCA").mic()).isEqualTo("ARCX");
        assertThat(AlpacaReferenceDataImporter.exchange("AMEX").mic()).isEqualTo("XASE");
        assertThat(AlpacaReferenceDataImporter.exchange("BATS").mic()).isEqualTo("BATS");
    }

    @Test
    void listingIdsAreStableAndVenueSpecific() {
        long nasdaq = AlpacaReferenceDataImporter.stableId(
                "904837e3-3b76-47ec-b432-046db621571b", "XNAS");
        assertThat(AlpacaReferenceDataImporter.stableId(
                "904837e3-3b76-47ec-b432-046db621571b", "XNAS")).isEqualTo(nasdaq);
        assertThat(AlpacaReferenceDataImporter.stableId(
                "904837e3-3b76-47ec-b432-046db621571b", "XOSR")).isNotEqualTo(nasdaq);
    }
}
