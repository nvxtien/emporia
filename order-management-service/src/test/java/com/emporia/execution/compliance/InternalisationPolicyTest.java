package com.emporia.execution.compliance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalisationPolicyTest {

    private final InternalisationPolicy policy = new InternalisationPolicy();

    /**
     * The case this exists for: listed securities in Vietnam must trade through
     * the Exchange, so matching them inside Emporia is not something the
     * operating entity can do. It has to fail the deployment, because by the
     * time it is visible in a dashboard the trades have happened.
     */
    @Test
    void refusesToStartWhenVietnamIsConfiguredAgainstInternalMatching() {
        assertThatThrownBy(() -> policy.internalisationDecision("VN", "", "exchange-core"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not permitted to internalise")
                .hasMessageContaining("venue-mode=fix");
    }

    @Test
    void vietnamMayStillRouteToAnExternalVenue() {
        assertThatCode(() -> policy.internalisationDecision("VN", "", "fix"))
                .doesNotThrowAnyException();
    }

    /**
     * The jurisdiction table is a default, not a fact the code asserts about the
     * law. An entity that is licensed to internalise says so, and that
     * declaration wins - visibly, in configuration an auditor can read.
     */
    @Test
    void anExplicitDeclarationOverridesTheJurisdictionDefault() {
        assertThatCode(() -> policy.internalisationDecision("VN", "true", "exchange-core"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> policy.internalisationDecision("US", "false", "exchange-core"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internalisation-permitted=false");
    }

    @Test
    void jurisdictionsOutsideTheTableInternaliseByDefault() {
        assertThat(policy.internalisationDecision("US", "", "exchange-core").permitted()).isTrue();
        assertThat(policy.internalisationDecision("GB", "", "exchange-core").permitted()).isTrue();
    }

    /**
     * Leaving the jurisdiction unset must not block every deployment and
     * development machine that predates this guard. The cost - that an
     * unspecified jurisdiction buys no protection - is stated in
     * CONFIGURATION.md rather than hidden here.
     */
    @Test
    void anUnspecifiedJurisdictionLeavesTheGuardInactive() {
        assertThatCode(() -> policy.internalisationDecision("unspecified", "", "exchange-core"))
                .doesNotThrowAnyException();
    }

    @Test
    void theJurisdictionCodeIsNormalised() {
        assertThatThrownBy(() -> policy.internalisationDecision("  vn  ", "", "exchange-core"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(policy.internalisationDecision(" vn ", "true", "fix").jurisdiction()).isEqualTo("VN");
    }

}
