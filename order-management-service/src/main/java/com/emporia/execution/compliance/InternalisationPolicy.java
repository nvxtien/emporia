package com.emporia.execution.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start a deployment that would match client orders internally in a
 * jurisdiction where that is not available to the operating entity.
 *
 * <h2>Why this is a startup failure and not a runtime check</h2>
 * <p>Internalising where the entity may not is not a bug that degrades service -
 * it is trading the firm cannot lawfully do, and every order it touches is
 * already done by the time anyone reads a dashboard. There is no safe way to
 * discover it late. Refusing to start converts it into a deployment failure,
 * which is the same trade {@code DedupIndexConfig} makes for the deduplication
 * horizon.
 *
 * <h2>Why the code does not claim to know the law</h2>
 * <p>Encoding "jurisdiction X prohibits internalisation" as a fact in source is
 * fragile in a way that is easy to miss: rules change, the claim carries no
 * record of ever having been checked by a lawyer, and a stale claim blocks
 * something lawful just as silently as a missing one permits something unlawful.
 *
 * <p>So the authority here is {@code emporia.compliance.internalisation-permitted}
 * - the operating entity <b>declaring what it is licensed to do</b>, which is the
 * same shape as the deployment declaring its venue mode. The jurisdiction table
 * below only supplies a <b>default</b> for that declaration, so that the safe
 * answer is the one you get by not thinking about it, and the unsafe answer has
 * to be written down where an auditor can see it.
 *
 * <p><b>The table is not legal advice</b> and is not a substitute for counsel in
 * the operating jurisdiction. It reflects one conclusion: listed securities in
 * Vietnam must trade through the Exchange, so a securities company may not match
 * client orders internally. Confirm before relying on it, and confirm again
 * before adding a row.
 */
@Configuration
public class InternalisationPolicy {

    private static final Logger log = LoggerFactory.getLogger(InternalisationPolicy.class);

    /**
     * Venue modes in which Emporia itself is the matching venue. {@code fix}
     * routes to somebody else's book and is not one of them; there is no
     * development stand-in left to reason about, the simulated gateway having
     * been deleted.
     */
    private static final Set<String> INTERNALISING_VENUE_MODES = Set.of("exchange-core");

    /**
     * Jurisdictions whose default is "internalisation not available". Only a
     * default - see the class javadoc.
     */
    private static final Set<String> INTERNALISATION_PROHIBITED_BY_DEFAULT = Set.of("VN");

    /**
     * Fails the context when the configured venue mode internalises and the
     * entity has not declared that it may.
     *
     * <p>Leaving {@code emporia.compliance.jurisdiction} unset leaves this guard
     * inactive, which preserves existing behaviour rather than blocking every
     * deployment and development machine that predates it. That is a deliberate
     * choice and its cost is stated in {@code CONFIGURATION.md}: an unspecified
     * jurisdiction buys no protection at all.
     */
    @Bean
    public InternalisationDecision internalisationDecision(
            @Value("${emporia.compliance.jurisdiction:unspecified}") String jurisdiction,
            @Value("${emporia.compliance.internalisation-permitted:}") String declared,
            @Value("${emporia.execution.venue-mode:exchange-core}") String venueMode) {

        String code = jurisdiction.trim().toUpperCase(Locale.ROOT);
        boolean permitted = declared.isBlank()
                ? !INTERNALISATION_PROHIBITED_BY_DEFAULT.contains(code)
                : Boolean.parseBoolean(declared.trim());

        if (!permitted && INTERNALISING_VENUE_MODES.contains(venueMode.trim())) {
            throw new IllegalStateException(
                    "emporia.execution.venue-mode=" + venueMode + " matches client orders inside Emporia, "
                            + "but this deployment is not permitted to internalise"
                            + (declared.isBlank()
                            ? " (the default for jurisdiction " + code + ")"
                            : " (emporia.compliance.internalisation-permitted=" + declared.trim() + ")")
                            + ". Route orders to an external venue with venue-mode=fix, or - if the operating "
                            + "entity is licensed to internalise here - declare it with "
                            + "emporia.compliance.internalisation-permitted=true. That declaration is a legal "
                            + "statement about the entity, not a tuning parameter.");
        }

        if (!permitted) {
            log.info("Internalisation is not permitted for jurisdiction {}; only external-venue routing is available",
                    code);
        }
        return new InternalisationDecision(code, permitted);
    }


    /**
     * The resolved answer, published as a bean so later work reads it rather
     * than re-deriving it. The quoting engine will need exactly this: quoting a
     * two-sided price is internalisation whether or not a client order ever
     * takes it.
     */
    public record InternalisationDecision(String jurisdiction, boolean permitted) { }
}
