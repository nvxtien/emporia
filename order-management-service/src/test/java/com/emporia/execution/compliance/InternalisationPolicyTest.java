package com.emporia.execution.compliance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalisationPolicyTest {

    /**
     * Both branches are exercised from either build. Whether the gateway is on
     * the classpath is a property of how the jar was built, so a test that read
     * the real classpath could only ever see one of them - and under -Dvn the
     * artifact check would fire first and mask everything else here.
     */
    private static InternalisationPolicy policyWithGateway(boolean present) {
        return new InternalisationPolicy() {
            @Override
            boolean internalisingGatewayPresent() {
                return present;
            }
        };
    }

    private final InternalisationPolicy policy = policyWithGateway(true);

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

    /**
     * The -Dvn artifact contains neither exchange-core nor the gateway, so
     * asking it to internalise has to say which build it is rather than raise a
     * ClassNotFoundException three frames inside Spring. An obscure failure
     * invites a workaround.
     */
    @Test
    void anArtifactWithoutTheGatewayRefusesAnInternalisingVenueMode() {
        assertThatThrownBy(() -> policyWithGateway(false).internalisationDecision("US", "true", "exchange-core"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dvn build")
                .hasMessageContaining("venue-mode=fix");
    }

    @Test
    void anArtifactWithoutTheGatewayStillRoutesToAnExternalVenue() {
        assertThatCode(() -> policyWithGateway(false).internalisationDecision("VN", "", "fix"))
                .doesNotThrowAnyException();
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
